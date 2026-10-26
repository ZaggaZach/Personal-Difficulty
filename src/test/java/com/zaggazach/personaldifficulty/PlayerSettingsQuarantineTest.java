package com.zaggazach.personaldifficulty;

import static com.zaggazach.personaldifficulty.PersonalDifficultyMode.*;
import static com.zaggazach.personaldifficulty.PlayerSettingsV2Test.backups;
import static com.zaggazach.personaldifficulty.PlayerSettingsV2Test.reloaded;
import static com.zaggazach.personaldifficulty.PlayerSettingsV2Test.settings;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zaggazach.personaldifficulty.PlayerSettingsV2Test.FaultyIo;
import com.zaggazach.personaldifficulty.logic.SelectionPolicy;
import com.zaggazach.personaldifficulty.logic.SelectionPolicy.Actor;
import com.zaggazach.personaldifficulty.logic.SelectionPolicy.RestrictFlag;
import com.zaggazach.personaldifficulty.logic.SelectionPolicy.RestrictStatus;
import com.zaggazach.personaldifficulty.logic.SelectionPolicy.Verdict;
import com.zaggazach.personaldifficulty.store.PlayerRecord;
import com.zaggazach.personaldifficulty.store.PlayerSettings;
import com.zaggazach.personaldifficulty.store.PlayerSettings.LoadState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The quarantine policy for unreadable stored entries, its explicit administrative repair, and the load-state
 * classification. The validation policy is a literal table here, not derived from the production parser.
 */
class PlayerSettingsQuarantineTest {
	private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID C = UUID.fromString("33333333-3333-3333-3333-333333333333");

	private static String file(UUID id, String entry) {
		return "{\"version\":2,\"players\":{\"" + id + "\":" + entry + "}}";
	}

	// ------------------------------------------------------------------ the validation policy, as a literal table

	/** entry -> the mode used for gameplay while quarantined. Only a valid {@code mode} field alone is ever used. */
	private static final Map<String, PersonalDifficultyMode> QUARANTINED = Map.ofEntries(
		Map.entry("{\"mode\":\"ELITE\",\"locked\":\"yes\"}", ELITE),
		Map.entry("{\"mode\":\"elite\",\"locked\":1}", ELITE),
		Map.entry("{\"mode\":\"HARD\",\"locked\":true,\"allowed\":[]}", HARD),
		Map.entry("{\"mode\":\"HARD\",\"allowed\":[\"HARD\",\"ARENA\"]}", HARD),
		Map.entry("{\"mode\":\"PEACEFUL\",\"locked\":null}", PEACEFUL),
		Map.entry("{\"mode\":\"WAT\",\"locked\":true}", NORMAL),
		Map.entry("{\"mode\":123,\"locked\":true}", NORMAL),
		Map.entry("{\"mode\":null}", NORMAL),
		Map.entry("\"NIGHTMARE\"", NORMAL),
		Map.entry("[\"ELITE\"]", NORMAL),
		Map.entry("7", NORMAL),
		Map.entry("null", NORMAL)
	);

	/** Accepted (not quarantined): entry -> expected (mode, locked, allowed). */
	@Test
	void theValidationPolicyAsATable(@TempDir Path dir) throws IOException {
		int n = 0;
		for (var e : QUARANTINED.entrySet()) {
			Path f = Files.createDirectory(dir.resolve("q" + n++)).resolve("players.json");
			Files.writeString(f, file(A, e.getKey()));
			PlayerSettings s = new PlayerSettings(f);
			s.load();
			assertEquals(LoadState.OK, s.loadState(), e.getKey());
			assertTrue(s.isQuarantined(A), "quarantined: " + e.getKey());
			assertEquals(e.getValue(), s.get(A), "gameplay mode salvaged only from a valid mode field: " + e.getKey());
			PlayerRecord r = s.record(A);
			assertTrue(r.quarantined() && r.locked() && r.allowed() == null, "fail closed view: " + e.getKey());
			assertFalse(s.hasExplicit(A), "not a valid record");
			assertEquals(0, s.explicitCount());
			assertEquals(1, s.quarantinedCount());
			assertEquals(1, s.loadWarnings().size());
			assertTrue(s.quarantineOf(A).orElseThrow().rawJson().contains(A.toString()), "raw entry is available for diagnosis");
		}
		// accepted shapes
		record Ok(String entry, PersonalDifficultyMode mode, boolean locked, EnumSet<PersonalDifficultyMode> allowed) {
		}
		for (Ok ok : List.of(
			new Ok("\"HARD\"", HARD, false, null),
			new Ok("\"inherit\"", NORMAL, false, null),
			new Ok("{\"mode\":\"EASY\"}", EASY, false, null),
			new Ok("{\"locked\":true}", NORMAL, true, null),
			new Ok("{\"mode\":\"ELITE\",\"locked\":false,\"allowed\":null}", ELITE, false, null),
			new Ok("{\"mode\":\"ELITE\",\"locked\":true,\"allowed\":[\"elite\",\" easy \"]}", ELITE, true, EnumSet.of(ELITE, EASY)),
			new Ok("{\"mode\":\"HARD\",\"by\":5,\"at\":\"x\",\"extra\":1}", HARD, false, null)
		)) {
			Path f = Files.createDirectory(dir.resolve("ok" + n++)).resolve("players.json");
			Files.writeString(f, file(A, ok.entry()));
			PlayerSettings s = new PlayerSettings(f);
			s.load();
			assertFalse(s.isQuarantined(A), "valid: " + ok.entry());
			assertEquals(ok.mode(), s.get(A), ok.entry());
			assertEquals(ok.locked(), s.record(A).locked(), ok.entry());
			assertEquals(ok.allowed(), s.record(A).allowed() == null ? null : EnumSet.copyOf(s.record(A).allowed()), ok.entry());
		}
	}

	@Test
	void auditOnlyFieldsNeverQuarantine_andAKnownFieldWritesOverAnInvalidAuditValue(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		Files.writeString(f, file(A, "{\"mode\":\"HARD\",\"locked\":true,\"by\":5,\"at\":\"x\"}"));
		PlayerSettings s = new PlayerSettings(f);
		s.load();
		assertFalse(s.isQuarantined(A));
		assertTrue(s.record(A).locked(), "the lock is enforced although the audit fields are junk");
		s.put(B, PlayerRecord.DEFAULT.withMode(EASY));
		JsonObject kept = JsonParser.parseString(Files.readString(f)).getAsJsonObject().getAsJsonObject("players").getAsJsonObject(A.toString());
		assertEquals(5, kept.get("by").getAsInt(), "the junk audit value is kept while nothing replaces it");
		UUID admin = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
		s.put(A, s.record(A).withLocked(false, admin, 77L));
		kept = JsonParser.parseString(Files.readString(f)).getAsJsonObject().getAsJsonObject("players").getAsJsonObject(A.toString());
		assertEquals(admin.toString(), kept.get("by").getAsString(), "a real 'by' replaces the invalid one");
		assertEquals(77L, kept.get("at").getAsLong());
	}

	// ------------------------------------------------------------------ nothing bypasses a quarantine

	@Test
	void everyActorIsRefusedForAQuarantinedPlayer_includingAdministrativeAssignment() {
		PlayerRecord q = PlayerRecord.quarantinedView(ELITE);
		for (Actor actor : Actor.values()) {
			for (PersonalDifficultyMode m : PersonalDifficultyMode.values()) {
				assertEquals(Verdict.REFUSED_INVALID_RECORD, SelectionPolicy.decide(actor, q, m, EnumSet.allOf(PersonalDifficultyMode.class)), actor + " " + m);
			}
		}
		assertTrue(SelectionPolicy.selectable(q, EnumSet.allOf(PersonalDifficultyMode.class)).isEmpty());
		for (RestrictFlag flag : RestrictFlag.values()) {
			assertEquals(RestrictStatus.REFUSED_INVALID_RECORD, SelectionPolicy.planRestrict(q, EnumSet.of(ELITE), flag, null, 1L).status());
		}
		assertTrue(Verdict.REFUSED_INVALID_RECORD.refused());
	}

	@Test
	void everyKindOfChangeIsRefusedForAQuarantinedPlayer_andTheFileIsByteIdentical(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		String content = file(A, "{\"mode\":\"ELITE\",\"locked\":\"yes\"}");
		Files.writeString(f, content);
		PlayerSettings s = new PlayerSettings(f);
		s.load();
		assertThrows(IOException.class, () -> s.set(A, HARD));
		assertThrows(IOException.class, () -> s.put(A, PlayerRecord.DEFAULT));
		assertThrows(IOException.class, () -> s.put(A, PlayerRecord.DEFAULT.withLocked(true, null, 1L)));
		assertThrows(IOException.class, () -> s.put(A, PlayerRecord.quarantinedView(ELITE)));
		assertEquals(content, Files.readString(f));
		assertEquals(ELITE, s.get(A), "still in force exactly as before");
		assertTrue(s.isQuarantined(A));
		assertTrue(backups(dir).isEmpty());
	}

	@Test
	void theQuarantineSurvivesUnrelatedSavesAndRestarts(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		String raw = "{\"mode\":\"ELITE\",\"locked\":\"yes\",\"nick\":\"z\"}";
		Files.writeString(f, file(A, raw));
		PlayerSettings s = settings(f, new FaultyIo());
		s.load();
		for (int i = 0; i < 5; i++) {
			s.put(B, PlayerRecord.DEFAULT.withMode(i % 2 == 0 ? HARD : EASY));
			PlayerSettings r = reloaded(f);
			assertTrue(r.isQuarantined(A), "still quarantined after restart " + i);
			assertEquals(ELITE, r.get(A));
			assertTrue(Files.readString(f).contains(raw), "raw entry byte for byte: " + Files.readString(f));
		}
	}

	// ------------------------------------------------------------------ explicit repair

	@Test
	void repairMakesAVerifiedBackupFirst_thenReplacesTheEntry_keepingUnknownFields(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		String content = "{\"version\":2,\"players\":{\"" + A + "\":{\"mode\":\"ELITE\",\"locked\":\"yes\",\"nickname\":\"zed\",\"future\":{\"k\":[1]}},\"" + C + "\":{\"mode\":\"HARD\"}}}";
		Files.writeString(f, content);
		PlayerSettings s = settings(f, new FaultyIo());
		s.load();
		PlayerSettings.Repair repair = s.repairQuarantined(A);
		assertNotNull(repair);
		assertNotNull(repair.backup());
		assertEquals(content, Files.readString(repair.backup()), "the backup is the file as it was, byte for byte, made BEFORE the replacement");
		assertEquals(1, backups(dir).size());
		assertEquals(List.of("nickname", "future"), repair.keptUnknownFields());

		assertFalse(s.isQuarantined(A));
		assertEquals(NORMAL, s.get(A), "repair is a reset: nothing of the untrusted entry is carried into force");
		assertFalse(s.record(A).locked());
		JsonObject players = JsonParser.parseString(Files.readString(f)).getAsJsonObject().getAsJsonObject("players");
		JsonObject now = players.getAsJsonObject(A.toString());
		assertEquals("NORMAL", now.get("mode").getAsString());
		assertEquals("zed", now.get("nickname").getAsString(), "unknown fields preserved");
		assertEquals("[1]", now.getAsJsonObject("future").get("k").toString());
		assertFalse(now.has("locked"), "the invalid value is gone from the live file (it is in the backup)");
		assertEquals("HARD", players.getAsJsonObject(C.toString()).get("mode").getAsString(), "other players untouched");

		// ordinary changes work again, and survive a restart
		s.set(A, HARD);
		assertEquals(HARD, reloaded(f).get(A));
		assertTrue(reloaded(f).loadWarnings().isEmpty());
		assertEquals(1, backups(dir).size(), "no extra backups from the later change");
	}

	@Test
	void repairOfAnEntryWithNothingElseInItRemovesIt(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		Files.writeString(f, file(A, "\"NIGHTMARE\""));
		PlayerSettings s = settings(f, new FaultyIo());
		s.load();
		PlayerSettings.Repair repair = s.repairQuarantined(A);
		assertTrue(repair.keptUnknownFields().isEmpty());
		assertEquals("{\"version\":2,\"players\":{}}", Files.readString(f));
		assertTrue(Files.readString(repair.backup()).contains("NIGHTMARE"));
		assertNull(s.repairQuarantined(A), "nothing left to repair");
	}

	@Test
	void repairOfDuplicateEntriesBacksBothUpAndKeepsOtherQuarantinedPlayersUntouched(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		UUID upper = UUID.fromString("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA");
		String content = "{\"version\":2,\"players\":{\"AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA\":{\"mode\":\"HARD\"},\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\":{\"mode\":\"EASY\"},\"" + B + "\":{\"mode\":\"ELITE\",\"locked\":\"no\"}}}";
		Files.writeString(f, content);
		PlayerSettings s = settings(f, new FaultyIo());
		s.load();
		assertEquals(2, s.quarantinedCount());
		s.repairQuarantined(upper);
		assertEquals(1, s.quarantinedCount());
		assertTrue(s.isQuarantined(B));
		String after = Files.readString(f);
		assertFalse(after.contains("\"mode\":\"HARD\"") || after.contains("\"mode\":\"EASY\""), "both duplicates replaced: " + after);
		assertTrue(after.contains("\"locked\":\"no\""), "the other quarantined entry is untouched: " + after);
		assertEquals(content, Files.readString(backups(dir).get(0)), "both duplicates are in the backup");
	}

	@Test
	void repairFailuresRollBackCompletely(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		String content = file(A, "{\"mode\":\"ELITE\",\"locked\":\"yes\"}");
		Files.writeString(f, content);
		FaultyIo io = new FaultyIo();
		PlayerSettings s = settings(f, io);
		s.load();

		io.failCopy.set(true);
		assertThrows(IOException.class, () -> s.repairQuarantined(A));
		assertTrue(s.isQuarantined(A), "no backup, no repair");
		assertEquals(content, Files.readString(f));
		assertTrue(backups(dir).isEmpty());
		io.failCopy.set(false);

		io.failWrite.set(true);
		assertThrows(IOException.class, () -> s.repairQuarantined(A));
		assertTrue(s.isQuarantined(A), "failed write: still quarantined");
		assertEquals(ELITE, s.get(A));
		assertEquals(content, Files.readString(f), "file untouched");
		assertEquals(1, backups(dir).size(), "the backup that was made stays");
		io.failWrite.set(false);

		assertNotNull(s.repairQuarantined(A), "and the retry works");
		assertFalse(s.isQuarantined(A));
	}

	@Test
	void aBackupThatDoesNotMatchTheOriginalIsDetectedAndDeleted(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		Files.writeString(f, file(A, "\"NIGHTMARE\""));
		PlayerSettings.Io truncating = new PlayerSettings.Io() {
			@Override
			public void copy(Path from, Path to) throws IOException {
				Files.writeString(to, "{"); // a bad, short copy
			}

			@Override
			public void writeAtomically(Path target, byte[] data) throws IOException {
				PlayerSettings.Io.REAL.writeAtomically(target, data);
			}
		};
		PlayerSettings s = new PlayerSettings(f, truncating, () -> 5L);
		s.load();
		IOException e = assertThrows(IOException.class, () -> s.repairQuarantined(A));
		assertTrue(e.getMessage().contains("does not match the original"), e.getMessage());
		assertTrue(backups(dir).isEmpty(), "the bad copy was removed");
		assertTrue(s.isQuarantined(A));
	}

	@Test
	void repairOnAV1FileNeedsOnlyOneBackup(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		Files.writeString(f, "{\"version\":1,\"players\":{\"" + A + "\":\"NIGHTMARE\",\"" + B + "\":\"HARD\"}}");
		PlayerSettings s = settings(f, new FaultyIo());
		s.load();
		s.repairQuarantined(A);
		assertEquals(1, backups(dir).size(), "the repair copy doubles as the migration copy (identical content is reused)");
		assertEquals(HARD, reloaded(f).get(B));
	}

	@Test
	void repairIsRefusedWhileTheFileIsBlocked(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		Files.writeString(f, "{\"version\":3,\"players\":{\"" + A + "\":\"NIGHTMARE\"}}");
		PlayerSettings s = settings(f, new FaultyIo());
		s.load();
		assertTrue(s.isQuarantined(A), "an unsupported-version file's readable entries are still judged");
		assertThrows(IOException.class, () -> s.repairQuarantined(A));
		assertEquals("{\"version\":3,\"players\":{\"" + A + "\":\"NIGHTMARE\"}}", Files.readString(f));
	}

	// ------------------------------------------------------------------ load states

	@Test
	void everyFileConditionHasItsOwnStateAndOnlyTheSafeOnesAreWritable(@TempDir Path dir) throws IOException {
		record Case(String name, String content, LoadState state, boolean writable, boolean backup) {
		}
		for (Case c : List.of(
			new Case("valid v2", "{\"version\":2,\"players\":{}}", LoadState.OK, true, false),
			new Case("valid v1", "{\"version\":1,\"players\":{}}", LoadState.OK, true, false),
			new Case("empty file", "", LoadState.EMPTY_FILE, true, false),
			new Case("blank file", "  \n ", LoadState.EMPTY_FILE, true, false),
			new Case("garbage", "{ nope", LoadState.CORRUPT, false, true),
			new Case("array", "[]", LoadState.CORRUPT, false, true),
			new Case("no version", "{\"players\":{}}", LoadState.CORRUPT, false, true),
			new Case("v2 without players", "{\"version\":2}", LoadState.CORRUPT, false, true),
			new Case("v2 players is an array", "{\"version\":2,\"players\":[]}", LoadState.CORRUPT, false, true),
			new Case("future v3", "{\"version\":3,\"players\":{}}", LoadState.UNSUPPORTED_VERSION, false, true),
			new Case("future v3 restructured", "{\"version\":3,\"records\":[]}", LoadState.UNSUPPORTED_VERSION, false, true),
			new Case("future huge", "{\"version\":2147483647,\"players\":{}}", LoadState.UNSUPPORTED_VERSION, false, true),
			new Case("fractional version", "{\"version\":2.5,\"players\":{}}", LoadState.UNSUPPORTED_VERSION, false, true),
			new Case("string version", "{\"version\":\"2\",\"players\":{}}", LoadState.UNSUPPORTED_VERSION, false, true),
			new Case("version zero", "{\"version\":0,\"players\":{}}", LoadState.UNSUPPORTED_VERSION, false, true)
		)) {
			Path f = Files.createDirectory(dir.resolve("s" + Math.abs(c.name().hashCode()))).resolve("players.json");
			Files.writeString(f, c.content());
			PlayerSettings s = new PlayerSettings(f);
			s.load();
			assertEquals(c.state(), s.loadState(), c.name());
			assertEquals(c.writable(), s.writeBlockedReason() == null, c.name());
			assertEquals(c.backup() ? 1 : 0, backups(f.getParent()).size(), c.name());
			assertEquals(c.content(), Files.readString(f), "load never touches the file: " + c.name());
			if (!c.writable()) {
				assertThrows(IOException.class, () -> s.set(A, HARD), c.name());
				assertEquals(c.content(), Files.readString(f), c.name());
				assertFalse(s.loadWarnings().isEmpty(), "reported: " + c.name());
				assertTrue(s.writeBlockedReason().contains("admin reload"), "tells the administrator how to recover: " + s.writeBlockedReason());
			}
		}
		PlayerSettings none = new PlayerSettings(dir.resolve("missing.json"));
		none.load();
		assertEquals(LoadState.NOT_PRESENT, none.loadState());
		assertNull(none.writeBlockedReason());
	}

	@Test
	void anEmptyFileIsWritableAndNeedsNoBackup(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		Files.writeString(f, "");
		PlayerSettings s = settings(f, new FaultyIo());
		s.load();
		s.set(A, HARD);
		assertEquals(HARD, reloaded(f).get(A));
		assertTrue(backups(dir).isEmpty(), "an empty file holds nothing worth a backup");
	}

	@Test
	void aDirectoryAtThePathIsReportedAsADirectory_andRecoveryIsPossible(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		Files.createDirectory(f);
		PlayerSettings s = settings(f, new FaultyIo());
		s.load();
		assertEquals(LoadState.NOT_A_FILE, s.loadState());
		assertTrue(s.writeBlockedReason().contains("is a directory"), s.writeBlockedReason());
		assertTrue(s.loadWarnings().get(0).contains("nothing was backed up"), s.loadWarnings().toString());
		assertThrows(IOException.class, () -> s.set(A, HARD));
		assertFalse(s.reloadIfBlocked());
		Files.delete(f);
		assertTrue(s.reloadIfBlocked());
		s.set(A, HARD);
		assertEquals(HARD, reloaded(f).get(A));
	}

	@Test
	void aHealthyStateIsNotReloaded(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		PlayerSettings s = settings(f, new FaultyIo());
		s.set(A, ELITE);
		assertTrue(s.reloadIfBlocked(), "nothing is blocked: nothing to reload");
		assertEquals(ELITE, s.get(A));
		assertEquals(LoadState.NOT_PRESENT, s.loadState(), "state untouched (the instance never loaded a file)");
	}

	// ------------------------------------------------------------------ the file changes behind the mod's back

	@Test
	void aFileEditedWhileTheServerRunsIsNeverOverwrittenButCanBeReloadedOnPurpose(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		Files.writeString(f, "{\"version\":2,\"players\":{\"" + A + "\":{\"mode\":\"ELITE\"}}}");
		PlayerSettings s = settings(f, new FaultyIo());
		s.load();
		s.set(C, EASY); // our own write: not an external change
		s.set(C, HARD);
		String external = "{\"version\":2,\"players\":{\"" + B + "\":{\"mode\":\"HARD\",\"locked\":true}}}";
		Files.writeString(f, external);

		IOException e = assertThrows(IOException.class, () -> s.set(C, PEACEFUL));
		assertTrue(e.getMessage().contains("changed outside the mod"), e.getMessage());
		assertEquals(external, Files.readString(f), "the externally edited file is untouched");
		assertEquals(LoadState.EXTERNALLY_CHANGED, s.loadState());
		assertEquals(1, backups(dir).size(), "a copy of what was found is kept");
		assertEquals(external, Files.readString(backups(dir).get(0)));
		assertEquals(HARD, s.get(C), "the refused change is not reported active; memory is as before");
		assertEquals(ELITE, s.get(A), "memory still holds the pre-edit state until the administrator reloads");
		assertThrows(IOException.class, () -> s.set(A, EASY), "still refusing");
		assertEquals(1, backups(dir).size(), "no pile-up of backups on repeated attempts");

		assertTrue(s.reloadIfBlocked(), "the edited file is valid: loading it is the administrator's explicit act");
		assertEquals(HARD, s.get(B));
		assertTrue(s.record(B).locked());
		assertEquals(NORMAL, s.get(A), "the file's content is now in force");
		s.set(C, PEACEFUL);
		assertEquals(PEACEFUL, reloaded(f).get(C));
		assertEquals(HARD, reloaded(f).get(B), "the externally added entry survived");
	}

	@Test
	void aFileCorruptedWhileTheServerRunsKeepsTheInMemoryLocksAndStaysBlocked(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		PlayerSettings s = settings(f, new FaultyIo());
		s.put(A, new PlayerRecord(ELITE, true, null, null, 0L, new JsonObject()));
		String good = Files.readString(f);
		Files.writeString(f, "{ truncated by something");

		assertThrows(IOException.class, () -> s.set(B, HARD));
		assertEquals("{ truncated by something", Files.readString(f), "the corrupt file is preserved as found");
		assertEquals("{ truncated by something", Files.readString(backups(dir).get(0)));
		assertFalse(s.reloadIfBlocked(), "the new content is corrupt: reload must refuse it");
		assertTrue(s.record(A).locked(), "and the running server does NOT drop its locks because the file went bad");
		assertEquals(ELITE, s.get(A));
		assertNotNull(s.writeBlockedReason());
		assertEquals(1, backups(dir).size());

		Files.writeString(f, good); // the administrator restores a good file
		assertTrue(s.reloadIfBlocked());
		assertTrue(s.record(A).locked());
		s.set(B, HARD);
		assertEquals(HARD, reloaded(f).get(B));
	}

	@Test
	void aFileThatAppearedBehindTheModsBackIsNotOverwrittenEither_butADeletedOneIsRecreated(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		PlayerSettings s = settings(f, new FaultyIo());
		s.load(); // no file
		Files.writeString(f, "{\"version\":2,\"players\":{\"" + B + "\":\"HARD\"}}");
		assertThrows(IOException.class, () -> s.set(A, HARD));
		assertEquals("{\"version\":2,\"players\":{\"" + B + "\":\"HARD\"}}", Files.readString(f));

		PlayerSettings t = settings(dir.resolve("other.json"), new FaultyIo());
		t.set(A, ELITE);
		Files.delete(dir.resolve("other.json"));
		t.set(B, HARD); // nothing is destroyed by recreating from memory
		assertEquals(ELITE, reloaded(dir.resolve("other.json")).get(A));
	}

	@Test
	void rewritingAFileWithIdenticalContentIsNotAnExternalChange(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		PlayerSettings s = settings(f, new FaultyIo());
		s.set(A, HARD);
		Files.writeString(f, Files.readString(f)); // e.g. a backup tool or an editor that saves without changing anything
		s.set(B, EASY);
		assertEquals(EASY, reloaded(f).get(B));
	}

	@Test
	void aFutureVersionFileStillEnforcesTheLocksItCanUnderstand_readOnly(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		Files.writeString(f, "{\"version\":3,\"players\":{\"" + A + "\":{\"mode\":\"ELITE\",\"locked\":true}}}");
		PlayerSettings s = settings(f, new FaultyIo());
		s.load();
		assertTrue(s.record(A).locked());
		assertEquals(ELITE, s.get(A));
		assertThrows(IOException.class, () -> s.put(A, s.record(A).withLocked(false, null, 1L)), "the lock can not be removed through a blocked file");
		assertTrue(s.record(A).locked());
	}
}
