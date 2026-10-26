package com.zaggazach.personaldifficulty;

import static com.zaggazach.personaldifficulty.PersonalDifficultyMode.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zaggazach.personaldifficulty.store.PlayerRecord;
import com.zaggazach.personaldifficulty.store.PlayerSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Format v2, migration from v1 and every data-safety rule of {@link PlayerSettings}: loading never writes, a timestamped
 * backup precedes the first v2 write, unknown data is preserved, a corrupt file is backed up and a newer-format file is
 * never overwritten, and failed writes (injected at the exact failing step) leave file and state untouched.
 */
class PlayerSettingsV2Test {
	private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID C = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

	private static final String V1 = "{\"version\":1,\"players\":{\"" + A + "\":\"ELITE\",\"" + B + "\":\"EASY\"}}";

	/** Real I/O with switchable faults at each step. */
	static final class FaultyIo implements PlayerSettings.Io {
		final AtomicBoolean failCopy = new AtomicBoolean();
		final AtomicBoolean failWrite = new AtomicBoolean();
		/** Writes the temp file fully, then fails before the atomic move (a crash/kill between the two steps). */
		final AtomicBoolean failBeforeMove = new AtomicBoolean();
		int copies;
		int writes;

		@Override
		public void copy(Path from, Path to) throws IOException {
			if (failCopy.get()) {
				throw new IOException("injected backup failure");
			}
			copies++;
			PlayerSettings.Io.REAL.copy(from, to);
		}

		@Override
		public void writeAtomically(Path target, byte[] data) throws IOException {
			if (failWrite.get()) {
				throw new IOException("injected write failure");
			}
			if (failBeforeMove.get()) {
				Files.write(target.resolveSibling(target.getFileName() + ".tmp"), data);
				throw new IOException("injected failure before the atomic move");
			}
			writes++;
			PlayerSettings.Io.REAL.writeAtomically(target, data);
		}
	}

	static PlayerSettings settings(Path file, FaultyIo io) {
		return new PlayerSettings(file, io, () -> 1_000L);
	}

	/** A fresh instance that has loaded the file: what a server restart sees. */
	static PlayerSettings reloaded(Path file) {
		PlayerSettings s = settings(file, new FaultyIo());
		s.load();
		return s;
	}

	static List<Path> backups(Path dir) throws IOException {
		try (var s = Files.list(dir)) {
			return s.filter(p -> p.getFileName().toString().contains(".backup-")).sorted().toList();
		}
	}

	private static JsonObject json(Path f) throws IOException {
		return JsonParser.parseString(Files.readString(f)).getAsJsonObject();
	}

	// ------------------------------------------------------------------ v1 -> v2 migration

	@Test
	void v1FileLoadsWithoutBeingRewrittenOrBackedUp(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		Files.writeString(f, V1);
		byte[] before = Files.readAllBytes(f);
		FaultyIo io = new FaultyIo();
		PlayerSettings s = settings(f, io);
		s.load();
		assertEquals(ELITE, s.get(A));
		assertEquals(EASY, s.get(B));
		assertEquals(NORMAL, s.get(C));
		for (UUID id : List.of(A, B)) {
			PlayerRecord r = s.record(id);
			assertFalse(r.locked());
			assertNull(r.allowed());
			assertNull(r.by());
		}
		assertArrayEquals(before, Files.readAllBytes(f), "loading never writes");
		assertEquals(0, io.writes);
		assertTrue(backups(dir).isEmpty(), "no backup is needed until something changes");
		assertTrue(s.loadWarnings().isEmpty());
		assertEquals(1, s.onDiskVersion());
	}

	@Test
	void firstChangeBacksUpTheV1FileByteForByteThenWritesV2WithObjectRecordsForEveryPlayer(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		Files.writeString(f, V1);
		byte[] original = Files.readAllBytes(f);
		FaultyIo io = new FaultyIo();
		PlayerSettings s = settings(f, io);
		s.load();
		s.put(C, PlayerRecord.DEFAULT.withMode(HARD));

		List<Path> b = backups(dir);
		assertEquals(1, b.size());
		assertEquals("players.json.backup-1000", b.get(0).getFileName().toString());
		assertArrayEquals(original, Files.readAllBytes(b.get(0)), "the backup is the previous file, byte for byte");

		JsonObject root = json(f);
		assertEquals(2, root.get("version").getAsInt());
		JsonObject players = root.getAsJsonObject("players");
		assertEquals(3, players.size());
		for (var e : players.entrySet()) {
			assertTrue(e.getValue().isJsonObject(), "every stored player is an object record in v2: " + e);
		}
		assertEquals("ELITE", players.getAsJsonObject(A.toString()).get("mode").getAsString(), "migrated v1 data is preserved");
		assertEquals("EASY", players.getAsJsonObject(B.toString()).get("mode").getAsString());
		assertEquals("HARD", players.getAsJsonObject(C.toString()).get("mode").getAsString());
		assertEquals(2, s.onDiskVersion());
	}

	@Test
	void onlyOneBackupIsMade_andNoneForAnAlreadyV2File(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		Files.writeString(f, V1);
		FaultyIo io = new FaultyIo();
		PlayerSettings s = settings(f, io);
		s.load();
		s.put(C, PlayerRecord.DEFAULT.withMode(HARD));
		s.put(C, PlayerRecord.DEFAULT.withMode(PEACEFUL));
		s.put(A, PlayerRecord.DEFAULT.withMode(EASY));
		assertEquals(1, backups(dir).size(), "migration backup happens once, before the first v2 write");

		// a restart on the now-v2 file: further changes need no new backup
		FaultyIo io2 = new FaultyIo();
		PlayerSettings restarted = settings(f, io2);
		restarted.load();
		restarted.put(B, PlayerRecord.DEFAULT.withMode(HARD));
		assertEquals(0, io2.copies);
		assertEquals(1, backups(dir).size());
	}

	@Test
	void v1HeaderWithObjectRecordsIsReadCorrectly_asWrittenBackByVersion130(@TempDir Path dir) throws IOException {
		// What 1.3.0 produces if it ever saves over a v2 file (verified by running the 1.3.0 class): version 1, record kept verbatim.
		Path f = dir.resolve("players.json");
		Files.writeString(f, "{\"version\":1,\"players\":{\"" + C + "\":\"EASY\",\"" + A + "\":{\"mode\":\"ELITE\",\"locked\":true,\"allowed\":[\"NORMAL\",\"ELITE\"],\"by\":\"" + ADMIN + "\",\"at\":1700000000000}}}");
		FaultyIo io = new FaultyIo();
		PlayerSettings s = settings(f, io);
		s.load();
		assertTrue(s.loadWarnings().isEmpty(), s.loadWarnings().toString());
		PlayerRecord r = s.record(A);
		assertEquals(ELITE, r.mode());
		assertTrue(r.locked());
		assertEquals(EnumSet.of(NORMAL, ELITE), r.allowed());
		assertEquals(ADMIN, r.by());
		assertEquals(1700000000000L, r.at());
		assertEquals(EASY, s.get(C));
		s.put(B, PlayerRecord.DEFAULT.withMode(HARD));
		assertEquals(1, backups(dir).size(), "version 1 on disk, so the safety copy is made");
		assertEquals(2, json(f).get("version").getAsInt());
		assertTrue(json(f).getAsJsonObject("players").getAsJsonObject(A.toString()).get("locked").getAsBoolean(), "the lock survived the rewrite");
	}

	// ------------------------------------------------------------------ v2 round trip

	@Test
	void everyV2FieldSurvivesARestart(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		FaultyIo io = new FaultyIo();
		PlayerSettings s = settings(f, io);
		PlayerRecord full = new PlayerRecord(ELITE, true, EnumSet.of(NORMAL, ELITE), ADMIN, 1700000000123L, new JsonObject());
		s.put(A, full);
		s.put(B, PlayerRecord.DEFAULT.withAllowed(EnumSet.of(EASY), null, 0L)); // restriction only, no admin attribution, mode NORMAL (an exception)
		s.put(C, PlayerRecord.DEFAULT.withLocked(true, ADMIN, 5L)); // lock on the default mode
		assertTrue(backups(dir).isEmpty(), "a brand-new file has nothing to back up");

		PlayerSettings r = settings(f, new FaultyIo());
		r.load();
		assertTrue(r.loadWarnings().isEmpty(), r.loadWarnings().toString());
		assertEquals(full, r.record(A));
		assertEquals(NORMAL, r.record(B).mode());
		assertEquals(EnumSet.of(EASY), r.record(B).allowed());
		assertTrue(r.record(B).isException());
		assertTrue(r.record(C).locked());
		assertEquals(NORMAL, r.record(C).mode());
		assertEquals(3, r.explicitCount());
		assertEquals(2, r.lockedCount());
		assertEquals(2, r.restrictedCount(), "A and B have allow-lists");
	}

	@Test
	void onDiskShapeMatchesTheApprovedFormat(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		PlayerSettings s = settings(f, new FaultyIo());
		s.put(A, new PlayerRecord(ELITE, true, EnumSet.of(ELITE, NORMAL), ADMIN, 42L, new JsonObject()));
		assertEquals("{\"version\":2,\"players\":{\"" + A + "\":{\"mode\":\"ELITE\",\"locked\":true,\"allowed\":[\"NORMAL\",\"ELITE\"],\"by\":\"" + ADMIN + "\",\"at\":42}}}",
			Files.readString(f), "version, mode, locked, allowed (canonical order), by, at");
		s.put(A, PlayerRecord.DEFAULT.withMode(EASY));
		assertEquals("{\"version\":2,\"players\":{\"" + A + "\":{\"mode\":\"EASY\"}}}", Files.readString(f), "optional fields are omitted when unset");
	}

	@Test
	void anAllDefaultRecordRemovesTheEntry_butUnknownFieldsKeepIt(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		PlayerSettings s = settings(f, new FaultyIo());
		s.put(A, PlayerRecord.DEFAULT.withMode(HARD));
		s.put(A, PlayerRecord.DEFAULT);
		assertFalse(s.hasExplicit(A));
		assertEquals("{\"version\":2,\"players\":{}}", Files.readString(f));

		Files.writeString(f, "{\"version\":2,\"players\":{\"" + B + "\":{\"mode\":\"HARD\",\"nickname\":\"x\"}}}");
		PlayerSettings t = settings(f, new FaultyIo());
		t.load();
		t.set(B, NORMAL);
		assertTrue(t.hasExplicit(B), "a record carrying unknown fields is never silently dropped");
		assertTrue(Files.readString(f).contains("\"nickname\":\"x\""));
	}

	@Test
	void anUnchangedPutWritesNothing(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		Files.writeString(f, V1);
		FaultyIo io = new FaultyIo();
		PlayerSettings s = settings(f, io);
		s.load();
		s.set(A, ELITE); // same mode as stored
		s.put(C, PlayerRecord.DEFAULT); // nothing to store
		assertEquals(0, io.writes);
		assertEquals(0, io.copies);
		assertEquals(V1, Files.readString(f));
		assertTrue(backups(dir).isEmpty());
	}

	// ------------------------------------------------------------------ unknown data

	@Test
	void unknownEntriesFieldsAndTopLevelKeysAreKeptVerbatim(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		String futureId = "44444444-4444-4444-4444-444444444444";
		String badLock = "55555555-5555-5555-5555-555555555555";
		String emptyList = "66666666-6666-6666-6666-666666666666";
		String unknownInList = "77777777-7777-7777-7777-777777777777";
		Files.writeString(f, "{\"version\":2,\"meta\":{\"owner\":\"someone\"},\"players\":{"
			+ "\"" + A + "\":{\"mode\":\"HARD\",\"nickname\":\"zed\",\"future\":{\"x\":[1,2]}},"
			+ "\"" + futureId + "\":\"NIGHTMARE\","
			+ "\"" + badLock + "\":{\"mode\":\"EASY\",\"locked\":\"yes\"},"
			+ "\"" + emptyList + "\":{\"mode\":\"EASY\",\"allowed\":[]},"
			+ "\"" + unknownInList + "\":{\"mode\":\"EASY\",\"allowed\":[\"EASY\",\"ARENA\"]},"
			+ "\"not-a-uuid\":{\"mode\":\"HARD\"}}}");
		PlayerSettings s = settings(f, new FaultyIo());
		s.load();
		assertEquals(HARD, s.get(A));
		// 1.4.0 (audit fix): an unreadable entry is QUARANTINED: locked (fail closed), never an unrestricted default
		for (String id : List.of(futureId, badLock, emptyList, unknownInList)) {
			assertTrue(s.isQuarantined(UUID.fromString(id)), "quarantined: " + id);
			assertTrue(s.record(UUID.fromString(id)).locked(), "treated as locked: " + id);
		}
		assertEquals(NORMAL, s.get(UUID.fromString(futureId)), "unknown mode name: nothing to salvage");
		for (String id : List.of(badLock, emptyList, unknownInList)) {
			assertEquals(EASY, s.get(UUID.fromString(id)), "the entry's own valid mode field is used for gameplay only: " + id);
		}
		assertEquals(5, s.loadWarnings().size());
		s.set(B, EASY); // any change rewrites the file

		JsonObject root = json(f);
		assertEquals("someone", root.getAsJsonObject("meta").get("owner").getAsString(), "unknown top-level field kept");
		JsonObject players = root.getAsJsonObject("players");
		assertEquals("zed", players.getAsJsonObject(A.toString()).get("nickname").getAsString(), "unknown record field kept");
		assertEquals("[1,2]", players.getAsJsonObject(A.toString()).getAsJsonObject("future").get("x").toString());
		assertEquals("NIGHTMARE", players.get(futureId).getAsString());
		assertEquals("yes", players.getAsJsonObject(badLock).get("locked").getAsString());
		assertEquals(0, players.getAsJsonObject(emptyList).getAsJsonArray("allowed").size());
		assertEquals("ARENA", players.getAsJsonObject(unknownInList).getAsJsonArray("allowed").get(1).getAsString());
		assertEquals("HARD", players.getAsJsonObject("not-a-uuid").get("mode").getAsString());
		assertEquals("EASY", players.getAsJsonObject(B.toString()).get("mode").getAsString());
	}

	@Test
	void twoKeysNamingTheSamePlayerAreBothKept(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		Files.writeString(f, "{\"version\":2,\"players\":{\"AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA\":{\"mode\":\"HARD\"},\"" + ADMIN + "\":{\"mode\":\"EASY\"}}}");
		PlayerSettings s = settings(f, new FaultyIo());
		s.load();
		assertEquals(0, s.explicitCount(), "ambiguous: neither entry is trusted");
		assertTrue(s.isQuarantined(ADMIN), "the player is quarantined (locked), not given either entry's mode");
		assertEquals(1, s.loadWarnings().size());
		s.set(B, HARD);
		String text = Files.readString(f);
		assertTrue(text.contains("\"mode\":\"HARD\"") && text.contains("\"mode\":\"EASY\""), "neither duplicate was lost: " + text);
		assertThrows(IOException.class, () -> s.set(ADMIN, HARD), "and a change for that player is refused, not falsely acknowledged");
	}

	@Test
	void v2RecordWithoutAModeButWithALockIsKept(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		Files.writeString(f, "{\"version\":2,\"players\":{\"" + A + "\":{\"locked\":true,\"allowed\":null}}}");
		PlayerSettings s = settings(f, new FaultyIo());
		s.load();
		assertTrue(s.record(A).locked());
		assertEquals(NORMAL, s.record(A).mode());
		assertNull(s.record(A).allowed(), "a JSON null allow-list means: follow the server policy");
	}

	// ------------------------------------------------------------------ failed writes

	@Test
	void failedBackupRefusesTheChange_fileAndStateUntouched_thenRetrySucceeds(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		Files.writeString(f, V1);
		FaultyIo io = new FaultyIo();
		PlayerSettings s = settings(f, io);
		s.load();
		io.failCopy.set(true);
		IOException e = assertThrows(IOException.class, () -> s.set(C, HARD));
		assertTrue(e.getMessage().contains("injected backup failure"));
		assertEquals(V1, Files.readString(f), "never migrated without the safety copy");
		assertEquals(NORMAL, s.get(C), "the change is not reported active");
		assertEquals(0, io.writes);
		assertTrue(backups(dir).isEmpty());

		io.failCopy.set(false);
		s.set(C, HARD);
		assertEquals(HARD, s.get(C));
		assertEquals(1, backups(dir).size());
		assertEquals(2, json(f).get("version").getAsInt());
	}

	@Test
	void failedWriteAfterTheBackupRollsBack_originalIntact_retryDoesNotDuplicateTheBackup(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		Files.writeString(f, V1);
		FaultyIo io = new FaultyIo();
		PlayerSettings s = settings(f, io);
		s.load();
		io.failWrite.set(true);
		assertThrows(IOException.class, () -> s.set(A, PEACEFUL));
		assertEquals(V1, Files.readString(f), "the old file is untouched");
		assertEquals(ELITE, s.get(A), "in-memory state rolled back to what is on disk");
		assertEquals(1, backups(dir).size(), "the safety copy was already made");

		io.failWrite.set(false);
		s.set(A, PEACEFUL);
		assertEquals(PEACEFUL, s.get(A));
		assertEquals(1, backups(dir).size(), "a retry must not pile up backups");
		assertEquals("PEACEFUL", json(f).getAsJsonObject("players").getAsJsonObject(A.toString()).get("mode").getAsString());
	}

	@Test
	void failureBetweenWritingTheTempFileAndTheMoveLeavesTheOldFileAndANextWriteStillWorks(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		Files.writeString(f, "{\"version\":2,\"players\":{\"" + A + "\":{\"mode\":\"ELITE\"}}}");
		String before = Files.readString(f);
		FaultyIo io = new FaultyIo();
		PlayerSettings s = settings(f, io);
		s.load();
		io.failBeforeMove.set(true);
		assertThrows(IOException.class, () -> s.put(A, new PlayerRecord(ELITE, true, null, ADMIN, 9L, new JsonObject())));
		assertEquals(before, Files.readString(f), "readers only ever see the complete old file or the complete new file");
		assertFalse(s.record(A).locked(), "lock was not reported active");
		assertTrue(Files.exists(dir.resolve("players.json.tmp")), "leftover temp file from the simulated crash");

		io.failBeforeMove.set(false);
		s.put(A, new PlayerRecord(ELITE, true, null, ADMIN, 9L, new JsonObject()));
		PlayerSettings restarted = settings(f, new FaultyIo());
		restarted.load();
		assertTrue(restarted.record(A).locked());
	}

	@Test
	void unwritableLocationRollsBackAndNeverClaimsTheChange(@TempDir Path dir) throws IOException {
		Path blocker = dir.resolve("blocker");
		Files.writeString(blocker, "i am a file, not a directory");
		PlayerSettings s = new PlayerSettings(blocker.resolve("players.json"));
		assertThrows(IOException.class, () -> s.put(A, new PlayerRecord(HARD, true, EnumSet.of(HARD), null, 0L, new JsonObject())));
		assertEquals(PlayerRecord.DEFAULT, s.record(A));
		assertEquals(0, s.explicitCount());
	}

	// ------------------------------------------------------------------ corrupt, truncated, unreadable

	@Test
	void corruptFileIsBackedUpAtLoad_noChangeCanOverwriteIt_andAFixedFileIsReloadedWithoutRestart(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		String garbage = "{\"version\":2,\"players\":{\"" + A + "\":{\"mode\":\"EL";
		Files.writeString(f, garbage);
		FaultyIo io = new FaultyIo();
		PlayerSettings s = settings(f, io);
		s.load();
		assertFalse(s.loadWarnings().isEmpty());
		assertTrue(s.loadWarnings().get(0).contains("original kept as players.json.backup-1000"), s.loadWarnings().toString());
		assertEquals(garbage, Files.readString(f), "load itself never overwrites");
		assertEquals(garbage, Files.readString(backups(dir).get(0)));
		assertNotNull(s.writeBlockedReason(), "1.4.0 audit fix: a corrupt file blocks writes, so no ordinary change can destroy it");
		assertEquals(PlayerSettings.LoadState.CORRUPT, s.loadState());

		IOException e = assertThrows(IOException.class, () -> s.set(C, HARD));
		assertTrue(e.getMessage().contains("corrupt") || e.getMessage().contains("not valid JSON"), e.getMessage());
		assertEquals(garbage, Files.readString(f), "the corrupt original is untouched by an unrelated change");
		assertEquals(1, backups(dir).size());
		assertEquals(garbage, Files.readString(backups(dir).get(0)), "and still recoverable from the backup");
		assertFalse(s.reloadIfBlocked(), "still corrupt: still blocked");
		assertEquals(1, backups(dir).size(), "reloading an unchanged corrupt file does not make another backup");

		// recovery without a restart: the administrator repairs the file, then reloads
		Files.writeString(f, V1);
		assertTrue(s.reloadIfBlocked());
		assertNull(s.writeBlockedReason());
		assertEquals(ELITE, s.get(A));
		s.set(C, HARD);
		assertEquals(HARD, reloaded(f).record(C).mode());
		assertEquals(garbage, Files.readString(backups(dir).get(0)), "the corrupt original's backup is still there");
	}

	@Test
	void everyTruncationPointOfAValidV2FileIsHandledWithoutAnExceptionOrLoss(@TempDir Path dir) throws IOException {
		String valid = "{\"version\":2,\"players\":{\"" + A + "\":{\"mode\":\"ELITE\",\"locked\":true,\"allowed\":[\"NORMAL\",\"ELITE\"]}}}";
		for (int cut = 0; cut < valid.length(); cut++) {
			Path d = Files.createDirectory(dir.resolve("cut" + cut));
			Path f = d.resolve("players.json");
			String prefix = valid.substring(0, cut);
			Files.writeString(f, prefix);
			PlayerSettings s = settings(f, new FaultyIo());
			s.load(); // must not throw
			assertEquals(prefix, Files.readString(f), "load never modifies the file (cut " + cut + ")");
			if (!s.loadWarnings().isEmpty() && s.loadWarnings().get(0).contains("original kept")) {
				assertEquals(prefix, Files.readString(backups(d).get(0)), "backup holds the truncated original (cut " + cut + ")");
			}
		}
	}

	@Test
	void ifTheBackupOfACorruptFileFailsTheFileIsNeverOverwritten(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		Files.writeString(f, "{ not json");
		FaultyIo io = new FaultyIo();
		io.failCopy.set(true);
		PlayerSettings s = settings(f, io);
		s.load();
		assertNotNull(s.writeBlockedReason());
		IOException e = assertThrows(IOException.class, () -> s.set(A, HARD));
		assertTrue(e.getMessage().contains("no backup could be made"), e.getMessage());
		assertTrue(s.loadWarnings().get(0).contains("backup failed"), s.loadWarnings().toString());
		assertEquals("{ not json", Files.readString(f));
		assertEquals(NORMAL, s.get(A));
		assertTrue(backups(dir).isEmpty());

		// once copies work again a reload keeps the original safe (still blocked, because it is still corrupt)
		io.failCopy.set(false);
		assertFalse(s.reloadIfBlocked());
		assertEquals("{ not json", Files.readString(backups(dir).get(0)));
		// and after the file is repaired, writing works
		Files.writeString(f, V1);
		assertTrue(s.reloadIfBlocked());
		s.set(A, HARD);
		assertEquals(HARD, reloaded(f).get(A));
	}

	@Test
	void restoringTheBackupBringsEverythingBack(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		Files.writeString(f, V1);
		PlayerSettings s = settings(f, new FaultyIo());
		s.load();
		s.put(A, new PlayerRecord(ELITE, true, EnumSet.of(ELITE), ADMIN, 1L, new JsonObject()));
		Path backup = backups(dir).get(0);
		Files.copy(backup, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING); // the documented downgrade/restore step
		PlayerSettings restored = settings(f, new FaultyIo());
		restored.load();
		assertEquals(ELITE, restored.get(A));
		assertEquals(EASY, restored.get(B));
		assertFalse(restored.record(A).locked());
	}

	@Test
	void versionValuesThatCannotBeInterpretedAreUnsupported_backedUpOnce_andNeverOverwritten(@TempDir Path dir) throws IOException {
		for (String version : List.of("\"two\"", "0", "-3", "1.5", "null", "[2]", "true")) {
			Path d = Files.createDirectory(dir.resolve("v" + Math.abs(version.hashCode())));
			Path f = d.resolve("players.json");
			String content = "{\"version\":" + version + ",\"players\":{\"" + A + "\":\"EASY\"}}";
			Files.writeString(f, content);
			FaultyIo io = new FaultyIo();
			PlayerSettings s = settings(f, io);
			s.load();
			assertEquals(PlayerSettings.LoadState.UNSUPPORTED_VERSION, s.loadState(), version);
			assertEquals(NORMAL, s.get(A), "an uninterpretable file is not interpreted at all: " + version);
			assertEquals(1, s.loadWarnings().size(), version);
			assertTrue(s.loadWarnings().get(0).contains("cannot be interpreted"), s.loadWarnings().toString());
			assertEquals(1, backups(d).size(), "copy made at load for version " + version);
			assertThrows(IOException.class, () -> s.set(B, HARD));
			assertEquals(content, Files.readString(f), "never overwritten: " + version);
			assertEquals(1, backups(d).size(), "not copied a second time for version " + version);
		}
	}

	// ------------------------------------------------------------------ newer format

	@Test
	void newerFormatIsReadBestEffortBackedUpAndNeverOverwritten(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		String v3 = "{\"version\":3,\"players\":{\"" + A + "\":{\"mode\":\"ELITE\",\"locked\":true,\"flux\":1}},\"extra\":true}";
		Files.writeString(f, v3);
		FaultyIo io = new FaultyIo();
		PlayerSettings s = settings(f, io);
		s.load();
		assertEquals(ELITE, s.get(A), "what is understood is still readable (and still enforced)");
		assertTrue(s.record(A).locked());
		assertEquals(1, backups(dir).size());
		assertEquals(v3, Files.readString(backups(dir).get(0)));
		assertNotNull(s.writeBlockedReason());
		assertEquals(PlayerSettings.LoadState.UNSUPPORTED_VERSION, s.loadState());
		assertTrue(s.loadWarnings().get(0).contains("format version 3, newer than"), s.loadWarnings().toString());

		IOException e = assertThrows(IOException.class, () -> s.set(C, HARD));
		assertTrue(e.getMessage().contains("format version 3, newer than"), e.getMessage());
		assertThrows(IOException.class, () -> s.put(A, PlayerRecord.DEFAULT));
		assertEquals(v3, Files.readString(f), "the newer file is byte-identical: never overwritten");
		assertEquals(NORMAL, s.get(C), "refused change is not reported active");
		assertEquals(ELITE, s.get(A), "refused change is rolled back");
		assertEquals(0, io.writes);
	}

	@Test
	void afterTheNewerFileIsReplacedByACompatibleOneAReloadRestoresWriting(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		Files.writeString(f, "{\"version\":3,\"players\":{}}");
		PlayerSettings s = settings(f, new FaultyIo());
		s.load();
		assertThrows(IOException.class, () -> s.set(A, HARD));
		Files.writeString(f, V1);
		s.load();
		assertNull(s.writeBlockedReason());
		s.set(C, HARD);
		assertEquals(HARD, reloaded(f).get(C));
	}

	// ------------------------------------------------------------------ memory-only

	@Test
	void memoryOnlyInstanceNeverTouchesDisk(@TempDir Path dir) throws IOException {
		PlayerSettings s = new PlayerSettings(null);
		s.load();
		s.put(A, new PlayerRecord(HARD, true, EnumSet.of(HARD), null, 0L, new JsonObject()));
		assertEquals(HARD, s.get(A));
		try (var files = Files.list(dir)) {
			assertEquals(0, files.count());
		}
		assertEquals(new ArrayList<String>(), s.loadWarnings());
	}

	@Test
	void changeCountAdvancesOnEveryRealChangeAndNotOnNoOps(@TempDir Path dir) throws IOException {
		PlayerSettings s = settings(dir.resolve("p.json"), new FaultyIo());
		int c0 = s.changeCount();
		s.set(A, HARD);
		int c1 = s.changeCount();
		assertTrue(c1 > c0);
		s.set(A, HARD);
		assertEquals(c1, s.changeCount(), "a no-op does not invalidate caches");
		s.put(A, s.record(A).withLocked(true, null, 0L));
		assertTrue(s.changeCount() > c1);
		assertEquals("{\"version\":2,\"players\":{\"" + A + "\":{\"mode\":\"HARD\",\"locked\":true}}}", new String(Files.readAllBytes(dir.resolve("p.json")), StandardCharsets.UTF_8));
	}
}
