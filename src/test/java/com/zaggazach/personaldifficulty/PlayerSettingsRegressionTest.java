package com.zaggazach.personaldifficulty;

import static com.zaggazach.personaldifficulty.PersonalDifficultyMode.*;
import static org.junit.jupiter.api.Assertions.*;

import com.zaggazach.personaldifficulty.logic.SelectionPolicy;
import com.zaggazach.personaldifficulty.logic.SelectionPolicy.Actor;
import com.zaggazach.personaldifficulty.store.PlayerRecord;
import com.zaggazach.personaldifficulty.store.PlayerSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for the defects found by the Phase 1 risk audit, written before the fixes:
 * invalid known fields must fail closed (never an unrestricted Normal player), a change for a player whose stored
 * entry is kept verbatim must never claim success, the format version is judged before the structure, and a corrupt or
 * unsupported file is never overwritten by an ordinary change.
 */
class PlayerSettingsRegressionTest {
	private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");

	/** Each of these makes a KNOWN field invalid (so the entry can not be trusted). */
	private static final List<String> INVALID_RECORDS = List.of(
		"{\"mode\":\"ELITE\",\"locked\":\"yes\"}",
		"{\"mode\":\"ELITE\",\"locked\":1}",
		"{\"mode\":\"ELITE\",\"locked\":\"true\"}",
		"{\"mode\":\"ELITE\",\"locked\":null}",
		"{\"mode\":\"ELITE\",\"locked\":true,\"allowed\":[]}",
		"{\"mode\":\"ELITE\",\"locked\":true,\"allowed\":\"elite\"}",
		"{\"mode\":\"ELITE\",\"locked\":true,\"allowed\":[\"ELITE\",\"ARENA\"]}",
		"{\"mode\":\"ELITE\",\"locked\":true,\"allowed\":[1]}",
		"{\"mode\":\"ELITE\",\"locked\":true,\"allowed\":{}}",
		"{\"mode\":123,\"locked\":true}",
		"{\"mode\":\"WAT\",\"locked\":true}",
		"{\"mode\":null,\"locked\":true}",
		"[\"ELITE\"]",
		"true",
		"\"NIGHTMARE\""
	);

	private static String file(String entry) {
		return "{\"version\":2,\"players\":{\"" + A + "\":" + entry + "}}";
	}

	private static List<Path> backups(Path dir) throws IOException {
		try (Stream<Path> s = Files.list(dir)) {
			return s.filter(p -> p.getFileName().toString().contains(".backup-")).sorted().toList();
		}
	}

	// ------------------------------------------------------------------ malformed records

	@Test
	void anInvalidKnownFieldNeverTurnsAPlayerIntoAnUnrestrictedOne(@TempDir Path dir) throws IOException {
		for (String entry : INVALID_RECORDS) {
			Path f = Files.createDirectory(dir.resolve("r" + Math.abs(entry.hashCode()))).resolve("players.json");
			Files.writeString(f, file(entry));
			PlayerSettings s = new PlayerSettings(f);
			s.load();
			PlayerRecord r = s.record(A);
			assertTrue(r.locked(), "fail closed: an unreadable record is treated as locked: " + entry);
			for (PersonalDifficultyMode m : PersonalDifficultyMode.values()) {
				assertTrue(SelectionPolicy.decide(Actor.PLAYER, r, m, EnumSet.allOf(PersonalDifficultyMode.class)).refused(), "self-service refused for " + m + ": " + entry);
			}
			assertFalse(s.loadWarnings().isEmpty(), "reported: " + entry);
		}
	}

	@Test
	void aChangeForAPlayerWhoseEntryIsKeptVerbatimIsRefusedNotFalselyAcknowledged(@TempDir Path dir) throws IOException {
		for (String entry : INVALID_RECORDS) {
			Path f = Files.createDirectory(dir.resolve("c" + Math.abs(entry.hashCode()))).resolve("players.json");
			String original = file(entry);
			Files.writeString(f, original);
			PlayerSettings s = new PlayerSettings(f);
			s.load();
			assertThrows(IOException.class, () -> s.set(A, HARD), "must not claim success: " + entry);
			assertThrows(IOException.class, () -> s.put(A, PlayerRecord.DEFAULT.withLocked(true, null, 1L)), "lock must not claim success: " + entry);
			assertEquals(original, Files.readString(f), "file untouched: " + entry);
			assertTrue(backups(f.getParent()).isEmpty() || backups(f.getParent()).size() == 1);
		}
	}

	@Test
	void anUnrelatedChangeKeepsTheMalformedEntryVerbatim(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		String bad = "{\"mode\":\"ELITE\",\"locked\":\"yes\",\"nickname\":\"zed\"}";
		Files.writeString(f, file(bad));
		PlayerSettings s = new PlayerSettings(f);
		s.load();
		s.set(B, HARD);
		String after = Files.readString(f);
		assertTrue(after.contains(bad), "kept byte for byte: " + after);
		assertTrue(after.contains("\"mode\":\"HARD\""));
	}

	// ------------------------------------------------------------------ version first

	@Test
	void theVersionIsJudgedBeforeTheStructure_anUnsupportedFileIsNeverOverwritten(@TempDir Path dir) throws IOException {
		List<String> files = List.of(
			"{\"version\":3,\"players\":{}}",
			"{\"version\":3,\"records\":[]}",
			"{\"version\":3,\"players\":[]}",
			"{\"version\":99}",
			"{\"version\":2.5,\"players\":{}}",
			"{\"version\":\"3\",\"players\":{}}",
			"{\"version\":null,\"players\":{}}",
			"{\"version\":0,\"players\":{}}",
			"{\"version\":-1,\"players\":{}}"
		);
		for (String content : files) {
			Path f = Files.createDirectory(dir.resolve("v" + Math.abs(content.hashCode()))).resolve("players.json");
			Files.writeString(f, content);
			PlayerSettings s = new PlayerSettings(f);
			s.load();
			assertThrows(IOException.class, () -> s.set(B, HARD), "refused: " + content);
			assertEquals(content, Files.readString(f), "byte-identical: " + content);
			assertEquals(1, backups(f.getParent()).size(), "backup exists: " + content);
			assertEquals(content, Files.readString(backups(f.getParent()).get(0)), "backup matches: " + content);
		}
	}

	// ------------------------------------------------------------------ corrupt

	@Test
	void aCorruptFileIsNeverOverwrittenByAnOrdinaryChange(@TempDir Path dir) throws IOException {
		for (String content : List.of("{ not json", "{\"version\":2,\"players\":{\"" + A + "\":{\"mode\":\"EL", "[]", "42", "{\"players\":{}}", "{\"version\":2,\"players\":[]}")) {
			Path f = Files.createDirectory(dir.resolve("k" + Math.abs(content.hashCode()))).resolve("players.json");
			Files.writeString(f, content);
			PlayerSettings s = new PlayerSettings(f);
			s.load();
			assertThrows(IOException.class, () -> s.set(B, HARD), "refused: " + content);
			assertEquals(content, Files.readString(f), "original untouched: " + content);
			assertEquals(NORMAL, s.get(B), "refused change not reported active");
			assertEquals(content, Files.readString(backups(f.getParent()).get(0)), "backup matches: " + content);
		}
	}

	@Test
	void restartingOnAnUnfixedCorruptFileDoesNotPileUpBackups(@TempDir Path dir) throws IOException, InterruptedException {
		Path f = dir.resolve("players.json");
		Files.writeString(f, "{ not json");
		for (int i = 0; i < 4; i++) {
			new PlayerSettings(f).load();
			Thread.sleep(3);
		}
		assertEquals(1, backups(dir).size(), "identical content is backed up once");
	}

	@Test
	void aDirectoryAtTheSettingsPathIsReportedAsSuchAndNothingIsClaimedBackedUp(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		Files.createDirectory(f);
		PlayerSettings s = new PlayerSettings(f);
		s.load();
		assertFalse(s.loadWarnings().isEmpty());
		assertTrue(s.loadWarnings().get(0).contains("directory"), s.loadWarnings().toString());
		assertFalse(s.loadWarnings().get(0).contains("original kept"), "no false claim of a backup: " + s.loadWarnings());
		assertTrue(backups(dir).isEmpty());
		assertThrows(IOException.class, () -> s.set(B, HARD));
		assertTrue(Files.isDirectory(f));
	}
}
