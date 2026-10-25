package com.zaggazach.personaldifficulty;

import static org.junit.jupiter.api.Assertions.*;

import com.zaggazach.personaldifficulty.store.PlayerSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 1.0.0 -> 1.1.0 player-data migration. The 1.0.0 file format is {"version":1,"players":{uuid:MODE}}. */
class MigrationTest {
	private static final UUID INHERITED = UUID.fromString("00000000-0000-0000-0000-00000000000a");
	private static final UUID NORMAL = UUID.fromString("00000000-0000-0000-0000-00000000000b");
	private static final UUID EASY = UUID.fromString("00000000-0000-0000-0000-00000000000c");
	private static final UUID HARD = UUID.fromString("00000000-0000-0000-0000-00000000000d");
	private static final UUID NEWCOMER = UUID.fromString("00000000-0000-0000-0000-00000000000e");

	private static final String LEGACY = "{\"version\":1,\"players\":{"
		+ "\"" + INHERITED + "\":\"INHERIT\","
		+ "\"" + NORMAL + "\":\"NORMAL\","
		+ "\"" + EASY + "\":\"EASY\","
		+ "\"" + HARD + "\":\"HARD\"}}";

	@Test
	void everyLegacySelectionIsReadCorrectly(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		Files.writeString(f, LEGACY);
		PlayerSettings s = new PlayerSettings(f);
		s.load();
		assertEquals(PersonalDifficultyMode.NORMAL, s.get(INHERITED), "INHERIT migrates to NORMAL");
		assertEquals(PersonalDifficultyMode.NORMAL, s.get(NORMAL), "existing NORMAL survives");
		assertEquals(PersonalDifficultyMode.EASY, s.get(EASY), "existing EASY survives");
		assertEquals(PersonalDifficultyMode.HARD, s.get(HARD), "existing HARD survives");
		assertEquals(PersonalDifficultyMode.NORMAL, s.get(NEWCOMER), "a player who never chose is NORMAL");
		assertEquals(2, s.legacyNormalisedCount());
		assertTrue(s.loadWarnings().isEmpty(), s.loadWarnings().toString());
	}

	@Test
	void loadingDoesNotRewriteTheFileAndTheResultIsStableAcrossRestarts(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		Files.writeString(f, LEGACY);
		for (int restart = 0; restart < 3; restart++) {
			PlayerSettings s = new PlayerSettings(f);
			s.load();
			assertEquals(PersonalDifficultyMode.NORMAL, s.get(INHERITED), "restart " + restart);
			assertEquals(PersonalDifficultyMode.EASY, s.get(EASY), "restart " + restart);
			assertEquals(PersonalDifficultyMode.HARD, s.get(HARD), "restart " + restart);
		}
		assertEquals(LEGACY, Files.readString(f), "no structural change, so no needless rewrite on load");
	}

	@Test
	void migrationDoesNotDependOnPlayersJoining_firstSaveDropsOnlyTheRetiredValues(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		Files.writeString(f, LEGACY);
		PlayerSettings s = new PlayerSettings(f);
		s.load();
		s.set(NEWCOMER, PersonalDifficultyMode.ELITE); // an unrelated change triggers the first rewrite
		String after = Files.readString(f);
		assertFalse(after.contains("INHERIT"), after);
		PlayerSettings restarted = new PlayerSettings(f);
		restarted.load();
		assertEquals(PersonalDifficultyMode.NORMAL, restarted.get(INHERITED));
		assertEquals(PersonalDifficultyMode.EASY, restarted.get(EASY));
		assertEquals(PersonalDifficultyMode.HARD, restarted.get(HARD));
		assertEquals(PersonalDifficultyMode.ELITE, restarted.get(NEWCOMER));
		assertEquals(3, restarted.explicitCount(), "only genuine non-default selections remain");
	}

	@Test
	void newModesPersist(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		PlayerSettings s = new PlayerSettings(f);
		s.set(EASY, PersonalDifficultyMode.ELITE);
		s.set(HARD, PersonalDifficultyMode.PEACEFUL);
		PlayerSettings r = new PlayerSettings(f);
		r.load();
		assertEquals(PersonalDifficultyMode.ELITE, r.get(EASY));
		assertEquals(PersonalDifficultyMode.PEACEFUL, r.get(HARD));
	}

	@Test
	void unrecognisedEntriesAreKeptVerbatimAcrossSaves(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		String future = "00000000-0000-0000-0000-0000000000ff";
		Files.writeString(f, "{\"version\":1,\"players\":{\"" + EASY + "\":\"EASY\",\"" + future + "\":\"NIGHTMARE\",\"not-a-uuid\":\"HARD\"}}");
		PlayerSettings s = new PlayerSettings(f);
		s.load();
		assertEquals(2, s.loadWarnings().size());
		s.set(HARD, PersonalDifficultyMode.HARD);
		String after = Files.readString(f);
		assertTrue(after.contains("NIGHTMARE") && after.contains(future), "unknown mode kept, not discarded: " + after);
		assertTrue(after.contains("not-a-uuid"), "unreadable key kept, not discarded: " + after);
		assertTrue(after.contains(EASY.toString()));
	}

	@Test
	void partiallyCorruptFileKeepsEveryRecoverableEntry(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		Files.writeString(f, "{\"version\":1,\"players\":{\"" + EASY + "\":\"EASY\",\"" + HARD + "\":123,\"" + NORMAL + "\":null,\"" + INHERITED + "\":\"hard\"}}");
		PlayerSettings s = new PlayerSettings(f);
		s.load();
		assertEquals(PersonalDifficultyMode.EASY, s.get(EASY));
		assertEquals(PersonalDifficultyMode.HARD, s.get(INHERITED), "lower-case value is still recognised");
		assertEquals(PersonalDifficultyMode.NORMAL, s.get(HARD), "a numeric value is not understood -> default, but preserved on disk");
		s.set(NEWCOMER, PersonalDifficultyMode.EASY);
		assertTrue(Files.readString(f).contains("123"), "the unreadable entry is not silently destroyed");
	}

	@Test
	void truncatedOrGarbageFileIsBackedUpNotOverwritten(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		Files.writeString(f, "{\"version\":1,\"players\":{\"" + EASY + "\":\"EA");
		PlayerSettings s = new PlayerSettings(f);
		s.load();
		assertEquals(PersonalDifficultyMode.NORMAL, s.get(EASY));
		assertFalse(s.loadWarnings().isEmpty());
		try (var files = Files.list(dir)) {
			assertTrue(files.anyMatch(p -> p.getFileName().toString().contains(".backup-")), "original preserved");
		}
	}

	@Test
	void emptyAndMissingFilesAreFine(@TempDir Path dir) throws IOException {
		PlayerSettings missing = new PlayerSettings(dir.resolve("none.json"));
		missing.load();
		assertTrue(missing.loadWarnings().isEmpty());
		Path empty = dir.resolve("empty.json");
		Files.writeString(empty, "");
		PlayerSettings s = new PlayerSettings(empty);
		s.load();
		assertEquals(PersonalDifficultyMode.NORMAL, s.get(EASY));
	}

	@Test
	void newerFormatIsReadBestEffortAndBackedUp(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		Files.writeString(f, "{\"version\":99,\"players\":{\"" + EASY + "\":\"EASY\"}}");
		PlayerSettings s = new PlayerSettings(f);
		s.load();
		assertEquals(PersonalDifficultyMode.EASY, s.get(EASY));
		assertFalse(s.loadWarnings().isEmpty());
		try (var files = Files.list(dir)) {
			assertTrue(files.anyMatch(p -> p.getFileName().toString().contains(".backup-")));
		}
	}

	@Test
	void multipleUuidsStayCorrectlyAssociated(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("players.json");
		PlayerSettings s = new PlayerSettings(f);
		s.set(EASY, PersonalDifficultyMode.PEACEFUL);
		s.set(HARD, PersonalDifficultyMode.ELITE);
		s.set(NORMAL, PersonalDifficultyMode.EASY);
		s.set(EASY, PersonalDifficultyMode.HARD);
		PlayerSettings r = new PlayerSettings(f);
		r.load();
		assertEquals(PersonalDifficultyMode.HARD, r.get(EASY));
		assertEquals(PersonalDifficultyMode.ELITE, r.get(HARD));
		assertEquals(PersonalDifficultyMode.EASY, r.get(NORMAL));
		assertEquals(PersonalDifficultyMode.NORMAL, r.get(INHERITED));
	}
}
