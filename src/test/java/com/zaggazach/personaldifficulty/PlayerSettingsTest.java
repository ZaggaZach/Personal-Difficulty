package com.zaggazach.personaldifficulty;

import static org.junit.jupiter.api.Assertions.*;

import com.zaggazach.personaldifficulty.store.PlayerSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerSettingsTest {
	private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");

	@Test
	void newPlayerDefaultsToInherit(@TempDir Path dir) {
		PlayerSettings s = new PlayerSettings(dir.resolve("p.json"));
		s.load();
		assertEquals(PersonalDifficultyMode.INHERIT, s.get(A));
	}

	@Test
	void twoUuidsAreIndependentAndSurviveRestart(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("w").resolve("p.json");
		PlayerSettings s = new PlayerSettings(f);
		s.set(A, PersonalDifficultyMode.EASY);
		s.set(B, PersonalDifficultyMode.NORMAL);
		assertEquals(PersonalDifficultyMode.EASY, s.get(A));
		assertEquals(PersonalDifficultyMode.NORMAL, s.get(B));
		s.set(A, PersonalDifficultyMode.HARD);
		assertEquals(PersonalDifficultyMode.NORMAL, s.get(B), "changing A never changes B");

		PlayerSettings restarted = new PlayerSettings(f); // fresh instance = server restart
		restarted.load();
		assertEquals(PersonalDifficultyMode.HARD, restarted.get(A));
		assertEquals(PersonalDifficultyMode.NORMAL, restarted.get(B), "explicit NORMAL is stored, distinct from INHERIT");
		assertTrue(restarted.loadWarnings().isEmpty());
	}

	@Test
	void inheritRemovesTheEntryAndOthersSurvive(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("p.json");
		PlayerSettings s = new PlayerSettings(f);
		s.set(A, PersonalDifficultyMode.EASY);
		s.set(B, PersonalDifficultyMode.HARD);
		s.set(A, PersonalDifficultyMode.INHERIT);
		PlayerSettings r = new PlayerSettings(f);
		r.load();
		assertEquals(PersonalDifficultyMode.INHERIT, r.get(A));
		assertEquals(PersonalDifficultyMode.HARD, r.get(B));
		assertEquals(1, r.explicitCount());
	}

	@Test
	void storedByUuidNotName(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("p.json");
		new PlayerSettings(f).set(A, PersonalDifficultyMode.EASY);
		String json = Files.readString(f);
		assertTrue(json.contains(A.toString()));
		assertTrue(json.contains("\"version\":1"));
	}

	@Test
	void malformedFileDoesNotCrashIsBackedUpAndNotOverwritten(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("p.json");
		Files.writeString(f, "{ this is not json");
		PlayerSettings s = new PlayerSettings(f);
		s.load();
		assertEquals(PersonalDifficultyMode.INHERIT, s.get(A));
		assertFalse(s.loadWarnings().isEmpty());
		try (var files = Files.list(dir)) {
			assertTrue(files.anyMatch(p -> p.getFileName().toString().contains(".corrupt-")), "original preserved");
		}
	}

	@Test
	void badEntriesAreSkippedGoodOnesKept(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("p.json");
		Files.writeString(f, "{\"version\":1,\"players\":{\"" + A + "\":\"EASY\",\"not-a-uuid\":\"HARD\",\"" + B + "\":\"WAT\"}}");
		PlayerSettings s = new PlayerSettings(f);
		s.load();
		assertEquals(PersonalDifficultyMode.EASY, s.get(A));
		assertEquals(PersonalDifficultyMode.INHERIT, s.get(B));
		assertEquals(2, s.loadWarnings().size());
	}

	@Test
	void failedSaveRollsBackSoNoModeIsReportedActiveFalsely(@TempDir Path dir) throws IOException {
		Path blocker = dir.resolve("blocker");
		Files.writeString(blocker, "i am a file, not a directory");
		PlayerSettings s = new PlayerSettings(blocker.resolve("p.json"));
		assertThrows(IOException.class, () -> s.set(A, PersonalDifficultyMode.EASY));
		assertEquals(PersonalDifficultyMode.INHERIT, s.get(A));
	}

	@Test
	void separateWorldFilesDoNotShareData(@TempDir Path dir) throws IOException {
		PlayerSettings w1 = new PlayerSettings(dir.resolve("world1/p.json"));
		PlayerSettings w2 = new PlayerSettings(dir.resolve("world2/p.json"));
		w1.set(A, PersonalDifficultyMode.EASY);
		w2.load();
		assertEquals(PersonalDifficultyMode.INHERIT, w2.get(A));
	}
}
