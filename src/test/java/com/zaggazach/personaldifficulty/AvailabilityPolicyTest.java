package com.zaggazach.personaldifficulty;

import static org.junit.jupiter.api.Assertions.*;

import com.zaggazach.personaldifficulty.store.AvailabilityPolicy;
import com.zaggazach.personaldifficulty.store.AvailabilityPolicy.Change;
import com.zaggazach.personaldifficulty.store.PlayerSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AvailabilityPolicyTest {
	@Test
	void allFiveEnabledByDefault(@TempDir Path dir) {
		AvailabilityPolicy p = new AvailabilityPolicy(dir.resolve("a.json"));
		p.load();
		for (PersonalDifficultyMode m : PersonalDifficultyMode.values()) {
			assertTrue(p.isEnabled(m), m.name());
		}
		assertEquals(5, p.enabledModes().size());
		assertTrue(p.loadWarnings().isEmpty());
	}

	@Test
	void everyDifficultyCanBeDisabledAndReEnabledIndependently(@TempDir Path dir) throws IOException {
		AvailabilityPolicy p = new AvailabilityPolicy(dir.resolve("a.json"));
		for (PersonalDifficultyMode m : PersonalDifficultyMode.values()) {
			assertEquals(Change.CHANGED, p.setEnabled(m, false), "disable " + m);
			assertFalse(p.isEnabled(m));
			for (PersonalDifficultyMode other : PersonalDifficultyMode.values()) {
				if (other != m) {
					assertTrue(p.isEnabled(other), other + " must be unaffected by disabling " + m);
				}
			}
			assertEquals(Change.UNCHANGED, p.setEnabled(m, false), "second disable is a no-op");
			assertEquals(Change.CHANGED, p.setEnabled(m, true), "enable " + m);
			assertTrue(p.isEnabled(m));
			assertEquals(Change.UNCHANGED, p.setEnabled(m, true));
		}
	}

	@Test
	void policySurvivesRestart(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("config").resolve("a.json");
		AvailabilityPolicy p = new AvailabilityPolicy(f);
		p.setEnabled(PersonalDifficultyMode.ELITE, false);
		p.setEnabled(PersonalDifficultyMode.PEACEFUL, false);
		AvailabilityPolicy restarted = new AvailabilityPolicy(f);
		restarted.load();
		assertFalse(restarted.isEnabled(PersonalDifficultyMode.ELITE));
		assertFalse(restarted.isEnabled(PersonalDifficultyMode.PEACEFUL));
		assertTrue(restarted.isEnabled(PersonalDifficultyMode.EASY));
		assertEquals("{\"version\":1,\"disabled\":[\"elite\",\"peaceful\"]}", Files.readString(f));
	}

	@Test
	void reEnablingNeedsNoRestart(@TempDir Path dir) throws IOException {
		AvailabilityPolicy p = new AvailabilityPolicy(dir.resolve("a.json"));
		p.setEnabled(PersonalDifficultyMode.HARD, false);
		assertFalse(p.isEnabled(PersonalDifficultyMode.HARD));
		p.setEnabled(PersonalDifficultyMode.HARD, true);
		assertTrue(p.isEnabled(PersonalDifficultyMode.HARD), "live, same instance");
	}

	@Test
	void theLastEnabledDifficultyCannotBeDisabled(@TempDir Path dir) throws IOException {
		AvailabilityPolicy p = new AvailabilityPolicy(dir.resolve("a.json"));
		for (PersonalDifficultyMode m : new PersonalDifficultyMode[] {PersonalDifficultyMode.NORMAL, PersonalDifficultyMode.EASY, PersonalDifficultyMode.HARD, PersonalDifficultyMode.ELITE}) {
			assertEquals(Change.CHANGED, p.setEnabled(m, false));
		}
		assertEquals(Change.REFUSED_LAST_ENABLED, p.setEnabled(PersonalDifficultyMode.PEACEFUL, false));
		assertTrue(p.isEnabled(PersonalDifficultyMode.PEACEFUL));
		assertEquals(1, p.enabledModes().size());
	}

	@Test
	void operatorCanRecoverFromDisablingMostDifficulties(@TempDir Path dir) throws IOException {
		AvailabilityPolicy p = new AvailabilityPolicy(dir.resolve("a.json"));
		p.setEnabled(PersonalDifficultyMode.NORMAL, false);
		p.setEnabled(PersonalDifficultyMode.EASY, false);
		p.setEnabled(PersonalDifficultyMode.HARD, false);
		p.setEnabled(PersonalDifficultyMode.ELITE, false);
		assertEquals(Change.CHANGED, p.enableAll());
		assertEquals(5, p.enabledModes().size());
		assertEquals(Change.UNCHANGED, p.enableAll());
		AvailabilityPolicy restarted = new AvailabilityPolicy(dir.resolve("a.json"));
		restarted.load();
		assertEquals(5, restarted.enabledModes().size(), "recovery is persisted");
	}

	@Test
	void fileThatDisablesEverythingIsRejectedAndBackedUp(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("a.json");
		Files.writeString(f, "{\"version\":1,\"disabled\":[\"normal\",\"easy\",\"hard\",\"elite\",\"peaceful\"]}");
		AvailabilityPolicy p = new AvailabilityPolicy(f);
		p.load();
		assertEquals(5, p.enabledModes().size(), "never leaves the command system unusable");
		assertFalse(p.loadWarnings().isEmpty());
		try (var files = Files.list(dir)) {
			assertTrue(files.anyMatch(x -> x.getFileName().toString().contains(".backup-")));
		}
	}

	@Test
	void malformedFilesFailSafeToAllEnabled(@TempDir Path dir) throws IOException {
		for (String bad : new String[] {"{ nope", "[]", "\"x\"", "{\"disabled\":\"elite\"}", "{\"disabled\":{}}", ""}) {
			Path f = dir.resolve("a-" + Math.abs(bad.hashCode()) + ".json");
			Files.writeString(f, bad);
			AvailabilityPolicy p = new AvailabilityPolicy(f);
			p.load();
			assertEquals(5, p.enabledModes().size(), "input: " + bad);
		}
	}

	@Test
	void unknownNamesAreIgnoredWithAWarningAndKnownOnesStillApply(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("a.json");
		Files.writeString(f, "{\"version\":1,\"disabled\":[\"elite\",\"nightmare\",\"INHERIT\"]}");
		AvailabilityPolicy p = new AvailabilityPolicy(f);
		p.load();
		assertFalse(p.isEnabled(PersonalDifficultyMode.ELITE));
		assertEquals(4, p.enabledModes().size());
		assertEquals(2, p.loadWarnings().size(), "unknown names are reported at startup, valid ones still apply");
		assertEquals(2, AvailabilityPolicy.parse(Files.readString(f)).warnings().size());
	}

	@Test
	void missingDisabledKeyMeansEverythingEnabled() {
		AvailabilityPolicy.Parsed parsed = AvailabilityPolicy.parse("{\"version\":1}");
		assertFalse(parsed.failed());
		assertTrue(parsed.disabled().isEmpty());
	}

	@Test
	void reloadAppliesAValidEditAndKeepsThePreviousPolicyOnABadOne(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("a.json");
		AvailabilityPolicy p = new AvailabilityPolicy(f);
		p.setEnabled(PersonalDifficultyMode.ELITE, false);
		Files.writeString(f, "{\"version\":1,\"disabled\":[\"hard\"]}");
		assertFalse(p.reload().failed());
		assertTrue(p.isEnabled(PersonalDifficultyMode.ELITE));
		assertFalse(p.isEnabled(PersonalDifficultyMode.HARD));
		Files.writeString(f, "{ broken");
		assertTrue(p.reload().failed());
		assertFalse(p.isEnabled(PersonalDifficultyMode.HARD), "bad edit does not change the live policy");
		Files.writeString(f, "{\"disabled\":[\"normal\",\"easy\",\"hard\",\"elite\",\"peaceful\"]}");
		assertTrue(p.reload().failed());
		assertFalse(p.isEnabled(PersonalDifficultyMode.HARD), "an all-disabled edit is rejected too");
	}

	@Test
	void failedWriteRollsBackSoThePolicyIsNeverFalselyActive(@TempDir Path dir) throws IOException {
		Path blocker = dir.resolve("blocker");
		Files.writeString(blocker, "file, not directory");
		AvailabilityPolicy p = new AvailabilityPolicy(blocker.resolve("a.json"));
		assertThrows(IOException.class, () -> p.setEnabled(PersonalDifficultyMode.ELITE, false));
		assertTrue(p.isEnabled(PersonalDifficultyMode.ELITE));
	}

	@Test
	void policyAndPlayerSelectionsAreIndependent(@TempDir Path dir) throws IOException {
		UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");
		PlayerSettings settings = new PlayerSettings(dir.resolve("players.json"));
		settings.set(player, PersonalDifficultyMode.ELITE);
		AvailabilityPolicy p = new AvailabilityPolicy(dir.resolve("a.json"));
		p.setEnabled(PersonalDifficultyMode.ELITE, false);
		assertEquals(PersonalDifficultyMode.ELITE, settings.get(player), "disabling a mode never erases a saved selection");
		assertEquals(1, settings.countUsing(PersonalDifficultyMode.ELITE));
		p.setEnabled(PersonalDifficultyMode.ELITE, true);
		assertEquals(PersonalDifficultyMode.ELITE, settings.get(player));
	}
}
