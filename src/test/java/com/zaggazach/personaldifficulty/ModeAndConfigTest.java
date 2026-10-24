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
	void allModesParseAndInheritDiffersFromNormal() {
		assertEquals(PersonalDifficultyMode.EASY, PersonalDifficultyMode.parse("easy").orElseThrow());
		assertEquals(PersonalDifficultyMode.HARD, PersonalDifficultyMode.parse(" HARD ").orElseThrow());
		assertEquals(PersonalDifficultyMode.NORMAL, PersonalDifficultyMode.parse("Normal").orElseThrow());
		assertEquals(PersonalDifficultyMode.INHERIT, PersonalDifficultyMode.parse("inherit").orElseThrow());
		assertTrue(PersonalDifficultyMode.parse("nightmare").isEmpty());
		assertTrue(PersonalDifficultyMode.parse(null).isEmpty());
		assertNotEquals(PersonalDifficultyMode.INHERIT, PersonalDifficultyMode.NORMAL);
		assertFalse(PersonalDifficultyMode.INHERIT.hasProfile());
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
}
