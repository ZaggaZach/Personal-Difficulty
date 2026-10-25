package com.zaggazach.personaldifficulty;

import static org.junit.jupiter.api.Assertions.*;

import com.zaggazach.personaldifficulty.config.PdConfig;
import com.zaggazach.personaldifficulty.config.PdConfigLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModeAndConfigTest {
	@Test
	void allModesParseAndInheritIsGone() {
		assertEquals(PersonalDifficultyMode.EASY, PersonalDifficultyMode.parse("easy").orElseThrow());
		assertEquals(PersonalDifficultyMode.HARD, PersonalDifficultyMode.parse(" HARD ").orElseThrow());
		assertEquals(PersonalDifficultyMode.NORMAL, PersonalDifficultyMode.parse("Normal").orElseThrow());
		assertEquals(PersonalDifficultyMode.ELITE, PersonalDifficultyMode.parse("Elite").orElseThrow());
		assertEquals(PersonalDifficultyMode.PEACEFUL, PersonalDifficultyMode.parse("peaceful").orElseThrow());
		assertTrue(PersonalDifficultyMode.parse("inherit").isEmpty(), "inherit is no longer a selectable mode");
		assertEquals(PersonalDifficultyMode.NORMAL, PersonalDifficultyMode.parseStored("INHERIT").orElseThrow(), "legacy stored value maps to NORMAL");
		assertEquals(PersonalDifficultyMode.NORMAL, PersonalDifficultyMode.DEFAULT);
		assertEquals(5, PersonalDifficultyMode.values().length);
		assertTrue(PersonalDifficultyMode.parse("nightmare").isEmpty());
		assertTrue(PersonalDifficultyMode.parse(null).isEmpty());
		assertTrue(java.util.Arrays.stream(PersonalDifficultyMode.values()).noneMatch(m -> m.name().equals("INHERIT")));
		assertTrue(PersonalDifficultyMode.ELITE.hasProfile());
		assertFalse(PersonalDifficultyMode.PEACEFUL.hasProfile());
		assertFalse(PersonalDifficultyMode.NORMAL.hasProfile());
		assertTrue(PersonalDifficultyMode.EASY.hasProfile());
		assertTrue(PersonalDifficultyMode.HARD.hasProfile());
	}

	@Test
	void defaultsMatchSpecification() {
		PdConfig c = PdConfig.defaults();
		assertEquals(0.60, c.easy().damageMultiplier());
		assertEquals(0.50, c.easy().hungerMultiplier());
		assertEquals(1.25, c.hard().damageMultiplier());
		assertEquals(1.25, c.hard().hungerMultiplier());
		assertEquals(1.0, c.pvpDamageMultiplier());
		assertEquals(1.0, c.selfDamageMultiplier());
		assertFalse(c.voidApplyProfile());
		assertFalse(c.adminApplyProfile());
		assertTrue(c.easyRangedMinUncertainty() > 10.0, "must be wider than vanilla Easy world spread");
		assertEquals(2.0, c.elite().damageMultiplier());
		assertEquals(2.0, c.elite().hungerMultiplier());
		assertEquals(0.60, c.eliteArmorEffectiveness());
		assertTrue(c.eliteRangedMaxUncertainty() < 2.0, "tighter than vanilla Hard world spread");
		assertTrue(c.elite().damageMultiplier() > c.hard().damageMultiplier());
		assertTrue(c.elite().hungerMultiplier() > c.hard().hungerMultiplier());
	}

	@Test
	void shippedDefaultFileParsesToExactlyTheBuiltInDefaults() {
		PdConfigLoader.Result r = PdConfigLoader.parse(PdConfigLoader.defaultFileContents());
		assertFalse(r.readFailed());
		assertTrue(r.warnings().isEmpty(), r.warnings().toString());
		assertEquals(PdConfig.defaults(), r.config());
	}

	@Test
	void emptyFileGivesDefaults() {
		PdConfigLoader.Result r = PdConfigLoader.parse("");
		assertEquals(PdConfig.defaults(), r.config());
		assertTrue(r.warnings().isEmpty());
	}

	@Test
	void invalidValuesAreRejectedOrClamped() {
		PdConfigLoader.Result r = PdConfigLoader.parse(String.join("\n",
			"easy.damage-multiplier=NaN",
			"easy.hunger-multiplier=Infinity",
			"hard.damage-multiplier=-3",
			"hard.hunger-multiplier=9999",
			"pvp.damage-multiplier=banana",
			"easy.ranged-min-uncertainty=-Infinity",
			"void.apply-profile=maybe"));
		PdConfig c = r.config();
		assertEquals(0.60, c.easy().damageMultiplier(), "NaN -> default");
		assertEquals(0.50, c.easy().hungerMultiplier(), "Infinity -> default");
		assertEquals(0.0, c.hard().damageMultiplier(), "negative clamped to 0, never negative");
		assertEquals(PdConfig.MAX_MULTIPLIER, c.hard().hungerMultiplier(), "huge clamped to max");
		assertEquals(1.0, c.pvpDamageMultiplier(), "non-number -> default");
		assertEquals(PdConfig.defaults().easyRangedMinUncertainty(), c.easyRangedMinUncertainty());
		assertFalse(c.voidApplyProfile(), "bad boolean -> default");
		assertEquals(7, r.warnings().size(), r.warnings().toString());
		assertFalse(r.readFailed());
	}

	@Test
	void damageTypeOverridesParse() {
		PdConfigLoader.Result r = PdConfigLoader.parse("easy.damage-type.minecraft.fall=0.3\nhard.damage-type.minecraft.explosion=2\neasy.damage-type.bad=1\neasy.damage-type.minecraft.fire=-5");
		assertEquals(0.3, r.config().easy().damageTypeOverrides().get("minecraft:fall"));
		assertEquals(2.0, r.config().hard().damageTypeOverrides().get("minecraft:explosion"));
		assertEquals(0.0, r.config().easy().damageTypeOverrides().get("minecraft:fire"), "negative override clamped");
		assertFalse(r.config().easy().damageTypeOverrides().containsKey("bad"));
	}

	@Test
	void malformedUnicodeEscapeFailsSafelyToDefaults() {
		PdConfigLoader.Result r = PdConfigLoader.parse("easy.damage-multiplier=\\u12");
		assertTrue(r.readFailed());
		assertEquals(PdConfig.defaults(), r.config());
	}

	@Test
	void missingFileIsCreatedWithCommentedDefaults(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("config").resolve("personaldifficulty.properties");
		PdConfigLoader.Result r = PdConfigLoader.loadOrCreate(f);
		assertTrue(Files.exists(f));
		assertTrue(Files.readString(f).contains("# Personal Difficulty"));
		assertEquals(PdConfig.defaults(), r.config());
	}

	@Test
	void reloadPolicyChangedFileIsPickedUpOnNextLoad(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("c.properties");
		Files.writeString(f, "easy.damage-multiplier=0.5\n");
		assertEquals(0.5, PdConfigLoader.load(f).config().easy().damageMultiplier());
		Files.writeString(f, "easy.damage-multiplier=0.8\n");
		assertEquals(0.8, PdConfigLoader.load(f).config().easy().damageMultiplier());
	}

	@Test
	void unreadableFileReportsReadFailed(@TempDir Path dir) {
		PdConfigLoader.Result r = PdConfigLoader.load(dir.resolve("does-not-exist.properties"));
		assertTrue(r.readFailed());
	}

	@Test
	void eliteSettingsAreValidatedLikeTheOthers() {
		PdConfigLoader.Result r = PdConfigLoader.parse(String.join("\n",
			"elite.damage-multiplier=NaN",
			"elite.hunger-multiplier=-4",
			"elite.armor-effectiveness=7",
			"elite.ranged-max-uncertainty=Infinity"));
		PdConfig c = r.config();
		assertEquals(2.0, c.elite().damageMultiplier(), "NaN -> default");
		assertEquals(0.0, c.elite().hungerMultiplier(), "negative clamped to 0");
		assertEquals(1.0, c.eliteArmorEffectiveness(), "armour effectiveness clamped to [0,1]");
		assertEquals(1.0, c.eliteRangedMaxUncertainty(), "infinity -> default");
		assertEquals(4, r.warnings().size(), r.warnings().toString());
		PdConfig custom = PdConfigLoader.parse("elite.damage-multiplier=3.5\nelite.armor-effectiveness=0.4\nelite.damage-type.minecraft.fall=1.5").config();
		assertEquals(3.5, custom.elite().damageMultiplier());
		assertEquals(0.4, custom.eliteArmorEffectiveness());
		assertEquals(1.5, custom.elite().damageTypeOverrides().get("minecraft:fall"));
	}
}
