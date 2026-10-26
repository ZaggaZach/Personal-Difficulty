package com.zaggazach.personaldifficulty;

import static org.junit.jupiter.api.Assertions.*;

import com.zaggazach.personaldifficulty.OverridesStoreTest.FaultyIo;
import com.zaggazach.personaldifficulty.config.ConfigState;
import com.zaggazach.personaldifficulty.config.DamageTypeCatalog;
import com.zaggazach.personaldifficulty.config.OverridesStore;
import com.zaggazach.personaldifficulty.config.PdConfig;
import com.zaggazach.personaldifficulty.config.PdConfigLoader;
import com.zaggazach.personaldifficulty.config.PropertiesLayer;
import com.zaggazach.personaldifficulty.config.SetResult;
import com.zaggazach.personaldifficulty.legacy.LegacyConfigLoader;
import com.zaggazach.personaldifficulty.store.AvailabilityPolicy;
import com.zaggazach.personaldifficulty.store.PlayerSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The runtime's configuration flow: set/unset semantics (D1), strict refusals, atomic publication, reload per layer, immutability of the properties file. */
class ConfigRuntimeTest {
	private static final DamageTypeCatalog CATALOG = () -> List.of("minecraft:fall", "minecraft:explosion", "minecraft:generic_kill", "mod:my_type");

	private record Fixture(PdRuntime rt, Path props, Path overrides, FaultyIo io) {
	}

	private static Fixture fixture(Path dir, String propertiesText, DamageTypeCatalog catalog) throws IOException {
		Path props = dir.resolve("personaldifficulty.properties");
		if (propertiesText != null) {
			Files.writeString(props, propertiesText);
		}
		PdConfigLoader.LayerResult r = PdConfigLoader.loadOrCreateLayer(props);
		PropertiesLayer layer = r.readFailed() ? PdConfigLoader.layerOf(new Properties()) : r.layer();
		FaultyIo io = new FaultyIo();
		AtomicLong clock = new AtomicLong(1000);
		Path ov = dir.resolve(OverridesStore.FILE_NAME);
		OverridesStore store = new OverridesStore(ov, io, clock::incrementAndGet, catalog::exists);
		store.load();
		PdRuntime rt = new PdRuntime(props, ConfigState.compose(layer, store.layer()), store, catalog, new PlayerSettings(null), new AvailabilityPolicy(dir.resolve("a.json")));
		return new Fixture(rt, props, ov, io);
	}

	private static Fixture fixture(Path dir, String propertiesText) throws IOException {
		return fixture(dir, propertiesText, CATALOG);
	}

	private static String sha(Path p) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
	}

	private static List<Path> withInfix(Path dir, String infix) throws IOException {
		try (Stream<Path> s = Files.list(dir)) {
			return s.filter(p -> p.getFileName().toString().contains(infix)).sorted().toList();
		}
	}

	private static SetResult set(Fixture f, String key, String value) {
		return f.rt().setValue(key, value);
	}

	// ------------------------------------------------------------------ basic set / unset and immutability of the properties file

	@Test
	void setAppliesAtOnce_unsetRestores_andThePropertiesFileIsNeverTouched(@TempDir Path dir) throws Exception {
		Fixture f = fixture(dir, "easy.damage-multiplier=0.5\nelite.priority.enabled=false\n");
		String hash = sha(f.props());
		PdConfig before = f.rt().config();
		assertEquals(0.5, before.easy().damageMultiplier());

		SetResult r = set(f, "easy.damage-multiplier", "0.7");
		assertEquals(SetResult.Kind.APPLIED, r.kind(), r.message());
		assertEquals(0.7, f.rt().config().easy().damageMultiplier(), "applied at once, no restart");
		assertEquals(ConfigState.Source.OVERRIDE, f.rt().state().sourceOf(com.zaggazach.personaldifficulty.config.SettingRegistry.find("easy.damage-multiplier").orElseThrow()));
		assertTrue(r.message().contains("override") && r.message().contains("was 0.5 from properties"), r.message());
		assertEquals("{\"version\":1,\"values\":{\"easy.damage-multiplier\":0.7}}", Files.readString(f.overrides()));

		SetResult u = f.rt().unsetValue("easy.damage-multiplier");
		assertEquals(SetResult.Kind.APPLIED, u.kind(), u.message());
		assertEquals(0.5, f.rt().config().easy().damageMultiplier(), "falls back to the properties value");
		assertTrue(u.message().contains("0.5 (properties)"), u.message());
		assertEquals(hash, sha(f.props()), "personaldifficulty.properties is byte-identical after set and unset");
		f.rt().reload();
		assertEquals(hash, sha(f.props()), "...and after a reload");
	}

	@Test
	void noOverridesFileMeansIdenticalBehaviourToTheLegacyLoader_andNoFileIsCreated(@TempDir Path dir) throws Exception {
		String text = "easy.damage-multiplier=0.5\nhard.hunger-multiplier=99\nelite.effects.enabled=maybe\neasy.damage-type.minecraft.fall=0.3\n";
		Fixture f = fixture(dir, text);
		assertEquals(LegacyConfigLoader.parse(text).config(), f.rt().config());
		f.rt().reload();
		assertEquals(LegacyConfigLoader.parse(text).config(), f.rt().config());
		assertFalse(Files.exists(f.overrides()), "reading creates no overrides file");
		assertTrue(withInfix(dir, ".backup-").isEmpty() && withInfix(dir, ".evidence-").isEmpty());
	}

	// ------------------------------------------------------------------ D1: a value equal to the lower layer removes the override

	@Test
	void aValueEqualToTheLowerLayerIsNeverStored_itRemovesTheOverrideInstead(@TempDir Path dir) throws Exception {
		Fixture f = fixture(dir, "easy.damage-multiplier=0.5\n");
		// 1. equal to the properties value, nothing stored yet: nothing to do, nothing written
		SetResult same = set(f, "easy.damage-multiplier", "0.5");
		assertEquals(SetResult.Kind.UNCHANGED, same.kind(), same.message());
		assertTrue(same.message().contains("from the properties file"), same.message());
		assertFalse(Files.exists(f.overrides()));
		// 2. a different value is stored
		assertEquals(SetResult.Kind.APPLIED, set(f, "easy.damage-multiplier", "0.7").kind());
		assertEquals(0.7, f.rt().config().easy().damageMultiplier());
		// 3. setting the lower layer's value again removes the override (it does NOT pin the value)
		SetResult back = set(f, "easy.damage-multiplier", "0.50");
		assertEquals(SetResult.Kind.APPLIED, back.kind(), back.message());
		assertTrue(back.message().contains("override was removed") && back.message().contains("later edits of the properties file will apply"), back.message());
		assertEquals("{\"version\":1,\"values\":{}}", Files.readString(f.overrides()));
		assertEquals(0.5, f.rt().config().easy().damageMultiplier());
		assertEquals(ConfigState.Source.PROPERTIES, f.rt().state().sourceOf(com.zaggazach.personaldifficulty.config.SettingRegistry.find("easy.damage-multiplier").orElseThrow()));
		// 4. so a later properties edit applies
		Files.writeString(f.props(), "easy.damage-multiplier=0.9\n");
		f.rt().reload();
		assertEquals(0.9, f.rt().config().easy().damageMultiplier(), "an equal value was not pinned against the properties file");
	}

	@Test
	void equalToTheDefaultWhenNothingIsInPropertiesIsEqualToo_forEveryType(@TempDir Path dir) throws Exception {
		Fixture f = fixture(dir, "");
		assertEquals(SetResult.Kind.UNCHANGED, set(f, "easy.damage-multiplier", "0.6").kind());
		assertEquals(SetResult.Kind.UNCHANGED, set(f, "elite.priority.scan-interval-ticks", "10").kind());
		assertEquals(SetResult.Kind.UNCHANGED, set(f, "elite.priority.enabled", "TRUE").kind());
		assertEquals(SetResult.Kind.UNCHANGED, set(f, "pvp.damage-multiplier", "1.0").kind());
		assertEquals(SetResult.Kind.UNCHANGED, set(f, "void.apply-profile", "false").kind());
		assertFalse(Files.exists(f.overrides()));
		assertEquals(SetResult.Kind.APPLIED, set(f, "easy.damage-multiplier", "0.61").kind());
		assertEquals(SetResult.Kind.APPLIED, set(f, "elite.priority.scan-interval-ticks", "11").kind());
		assertEquals(SetResult.Kind.APPLIED, set(f, "elite.priority.enabled", "false").kind());
		assertEquals(Set.of("easy.damage-multiplier", "elite.priority.scan-interval-ticks", "elite.priority.enabled"), f.rt().overrides().layer().scalars().keySet());
		// putting each back to the default removes them again
		set(f, "easy.damage-multiplier", "0.6");
		set(f, "elite.priority.scan-interval-ticks", "10");
		set(f, "elite.priority.enabled", "true");
		assertTrue(f.rt().overrides().layer().scalars().isEmpty());
	}

	@Test
	void anOverrideThatBecomesEqualAfterAPropertiesEditRemainsAnOverrideUntilExplicitlyUnset(@TempDir Path dir) throws Exception {
		Fixture f = fixture(dir, "easy.damage-multiplier=0.5\n");
		set(f, "easy.damage-multiplier", "0.7");
		Files.writeString(f.props(), "easy.damage-multiplier=0.7\n"); // the properties file now says the same
		f.rt().reload();
		var def = com.zaggazach.personaldifficulty.config.SettingRegistry.find("easy.damage-multiplier").orElseThrow();
		assertEquals(ConfigState.Source.OVERRIDE, f.rt().state().sourceOf(def), "still reported as an override");
		assertEquals("{\"version\":1,\"values\":{\"easy.damage-multiplier\":0.7}}", Files.readString(f.overrides()), "reload did not rewrite the file");
		assertEquals(SetResult.Kind.APPLIED, f.rt().unsetValue("easy.damage-multiplier").kind());
		assertEquals(ConfigState.Source.PROPERTIES, f.rt().state().sourceOf(def));
		assertEquals(0.7, f.rt().config().easy().damageMultiplier());

		// an explicit `set` of the value now equal to the lower layer is the D1 case and removes the override as well
		set(f, "easy.damage-multiplier", "0.8");
		Files.writeString(f.props(), "easy.damage-multiplier=0.8\n");
		f.rt().reload();
		assertEquals(ConfigState.Source.OVERRIDE, f.rt().state().sourceOf(def));
		SetResult explicit = set(f, "easy.damage-multiplier", "0.8");
		assertEquals(SetResult.Kind.APPLIED, explicit.kind(), explicit.message());
		assertTrue(explicit.message().contains("override was removed"), explicit.message());
		assertEquals(ConfigState.Source.PROPERTIES, f.rt().state().sourceOf(def));
	}

	@Test
	void damageTypeEntries_lowerLayerIsThePropertiesEntryOrAbsent(@TempDir Path dir) throws Exception {
		Fixture f = fixture(dir, "easy.damage-type.minecraft.fall=0.3\n");
		// equal to the properties entry: nothing stored
		assertEquals(SetResult.Kind.UNCHANGED, set(f, "easy.damage-type.minecraft.fall", "0.3").kind());
		// a different value is stored and wins
		assertEquals(SetResult.Kind.APPLIED, set(f, "easy.damage-type.minecraft.fall", "0.5").kind());
		assertEquals(0.5, f.rt().config().easy().damageTypeOverrides().get("minecraft:fall"));
		// back to the properties value: the override is removed
		assertTrue(set(f, "easy.damage-type.minecraft.fall", "0.3").message().contains("override was removed"));
		assertEquals(0.3, f.rt().config().easy().damageTypeOverrides().get("minecraft:fall"));
		// no properties entry: ANY valid value differs from "absent", even one equal to the profile multiplier
		SetResult fresh = set(f, "hard.damage-type.minecraft.explosion", "1.25");
		assertEquals(SetResult.Kind.APPLIED, fresh.kind(), fresh.message());
		assertEquals(1.25, f.rt().config().hard().damageTypeOverrides().get("minecraft:explosion"));
		assertTrue(fresh.message().contains("no entry"), fresh.message());
		// unset removes it; unsetting again is a no-op
		assertEquals(SetResult.Kind.APPLIED, f.rt().unsetValue("hard.damage-type.minecraft.explosion").kind());
		assertTrue(f.rt().config().hard().damageTypeOverrides().isEmpty());
		assertEquals(SetResult.Kind.UNCHANGED, f.rt().unsetValue("hard.damage-type.minecraft.explosion").kind());
	}

	// ------------------------------------------------------------------ refusals change nothing

	@Test
	void everyRefusedSetChangesNothingAndNamesTheAllowedRange(@TempDir Path dir) throws Exception {
		Fixture f = fixture(dir, "easy.damage-multiplier=0.5\n");
		ConfigState before = f.rt().state();
		PdConfig configBefore = f.rt().config();
		record Bad(String key, String value, String mustContain) {
		}
		for (Bad b : List.of(
			new Bad("easy.damage-multiplier", "11", "a number from 0.0 to 10.0"),
			new Bad("easy.damage-multiplier", "-1", "a number from 0.0 to 10.0"),
			new Bad("easy.damage-multiplier", "abc", "a number from 0.0 to 10.0"),
			new Bad("easy.damage-multiplier", "NaN", "a number from 0.0 to 10.0"),
			new Bad("elite.priority.scan-interval-ticks", "4", "a whole number from 5 to 40"),
			new Bad("elite.priority.scan-interval-ticks", "10.5", "whole number"),
			new Bad("elite.effects.enabled", "yes", "true or false"),
			new Bad("easy.damage-multipliers", "1", "Did you mean: easy.damage-multiplier"),
			new Bad("totally.unknown", "1", "Unknown setting"),
			new Bad("", "1", "Unknown setting"),
			new Bad("normal.damage-type.minecraft.fall", "1", "easy, hard or elite"),
			new Bad("easy.damage-type.Minecraft.fall", "1", "namespace"),
			new Bad("easy.damage-type.minecraft.my/type", "1", "personaldifficulty.properties"),
			new Bad("easy.damage-type.minecraft", "1", "<namespace>.<path>"),
			new Bad("easy.damage-type.minecraft.fall", "11", "a number from 0.0 to 10.0"),
			new Bad("easy.damage-type.minecraft.fall", "x", "a number from 0.0 to 10.0"),
			new Bad("easy.damage-type.minecraft.falll", "1", "Unknown damage type"),
			new Bad("easy.damage-type.minecraft.fal", "1", "Did you mean: minecraft.fall"),
			new Bad("easy.damage-type.nomod.nothing", "1", "Unknown damage type")
		)) {
			SetResult r = f.rt().setValue(b.key(), b.value());
			assertEquals(SetResult.Kind.REFUSED, r.kind(), b + " -> " + r.message());
			assertTrue(r.message().contains(b.mustContain()), b + " -> " + r.message());
		}
		assertSame(before, f.rt().state(), "no refused set publishes anything");
		assertSame(configBefore, f.rt().config());
		assertFalse(Files.exists(f.overrides()), "and nothing was written");
		assertEquals(SetResult.Kind.REFUSED, f.rt().unsetValue("nonsense").kind());
		assertEquals(SetResult.Kind.REFUSED, f.rt().unsetValue("normal.damage-type.minecraft.fall").kind());
		assertSame(before, f.rt().state());
	}

	// ------------------------------------------------------------------ E17: a failed store cannot change the runtime

	@Test
	void aFailedStoreOperationNeverChangesTheActiveConfiguration(@TempDir Path dir) throws Exception {
		Fixture f = fixture(dir, "");
		assertEquals(SetResult.Kind.APPLIED, set(f, "pvp.damage-multiplier", "2.0").kind());
		ConfigState before = f.rt().state();
		PdConfig configBefore = f.rt().config();
		String fileBefore = Files.readString(f.overrides());
		int backupsBefore = withInfix(dir, ".backup-").size();

		f.io().failCopy.set(true);
		SetResult r1 = set(f, "pvp.damage-multiplier", "3.0");
		f.io().failCopy.set(false);
		f.io().failWrite.set(true);
		SetResult r2 = set(f, "pvp.damage-multiplier", "3.0");
		f.io().failWrite.set(false);
		f.io().failBeforeMove.set(true);
		SetResult r3 = f.rt().unsetValue("pvp.damage-multiplier");
		f.io().failBeforeMove.set(false);
		for (SetResult r : List.of(r1, r2, r3)) {
			assertEquals(SetResult.Kind.REFUSED, r.kind(), r.message());
			assertTrue(r.message().contains("nothing was changed"), r.message());
		}
		assertSame(before, f.rt().state(), "the published configuration is the same object");
		assertSame(configBefore, f.rt().config());
		assertEquals(2.0, f.rt().config().pvpDamageMultiplier());
		assertEquals(fileBefore, Files.readString(f.overrides()), "the file is byte-identical");
		assertEquals(2.0, f.rt().overrides().layer().scalars().get("pvp.damage-multiplier"));
		assertTrue(withInfix(dir, ".backup-").size() <= backupsBefore + 1, "a failed write leaves at most the one verified safety copy");

		// recovery: the very same requests succeed once the fault is gone
		assertEquals(SetResult.Kind.APPLIED, set(f, "pvp.damage-multiplier", "3.0").kind());
		assertEquals(3.0, f.rt().config().pvpDamageMultiplier());
	}

	@Test
	void aReadOnlyOrBlockedLocationRefusesAndKeepsTheConfiguration(@TempDir Path dir) throws Exception {
		Path blocker = dir.resolve("blocker");
		Files.writeString(blocker, "a file, not a directory");
		Path props = dir.resolve("p.properties");
		Files.writeString(props, "");
		OverridesStore store = new OverridesStore(blocker.resolve("o.json"));
		store.load();
		PdRuntime rt = new PdRuntime(props, ConfigState.compose(PdConfigLoader.loadLayer(props).layer(), store.layer()), store, CATALOG, new PlayerSettings(null), new AvailabilityPolicy(dir.resolve("a.json")));
		ConfigState before = rt.state();
		SetResult r = rt.setValue("pvp.damage-multiplier", "2");
		assertEquals(SetResult.Kind.REFUSED, r.kind());
		assertSame(before, rt.state());
	}

	// ------------------------------------------------------------------ corrupt overrides at startup and recovery (D-f, D-d)

	@Test
	void aCorruptOverridesFileAtStartupLeavesDefaultsPlusProperties_refusesChanges_andReloadRecovers(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve(OverridesStore.FILE_NAME), "{ not json");
		Fixture f = fixture(dir, "easy.damage-multiplier=0.5\n");
		assertEquals(0.5, f.rt().config().easy().damageMultiplier(), "defaults plus properties; the server still starts");
		assertNotNull(f.rt().overrides().blockedReason());
		SetResult r = set(f, "pvp.damage-multiplier", "2.0");
		assertEquals(SetResult.Kind.REFUSED, r.kind());
		assertTrue(r.message().contains("admin reload"), r.message());
		assertEquals("{ not json", Files.readString(f.overrides()), "never overwritten");
		assertEquals(1, withInfix(dir, ".evidence-").size());

		PdRuntime.Reload still = f.rt().reload();
		assertTrue(still.overridesFailed() && still.anyFailed());
		assertNotNull(still.overridesBlocked());
		assertEquals(1, withInfix(dir, ".evidence-").size(), "no new evidence for identical content");

		Files.writeString(f.overrides(), "{\"version\":1,\"values\":{\"pvp.damage-multiplier\":2.5}}");
		PdRuntime.Reload fixed = f.rt().reload();
		assertFalse(fixed.anyFailed());
		assertEquals(2.5, f.rt().config().pvpDamageMultiplier(), "the repaired overrides are in force");
		assertEquals(SetResult.Kind.APPLIED, set(f, "pvp.damage-multiplier", "3.0").kind(), "and changes work again");
	}

	@Test
	void aFailedLayerReloadKeepsThatLayersLastGoodData_theOtherLayerStillUpdates(@TempDir Path dir) throws Exception {
		Fixture f = fixture(dir, "easy.damage-multiplier=0.5\nhard.damage-multiplier=1.5\n");
		set(f, "pvp.damage-multiplier", "2.0");
		// the overrides file goes bad AND the properties file changes
		Files.writeString(f.overrides(), "{ garbage");
		Files.writeString(f.props(), "easy.damage-multiplier=0.8\nhard.damage-multiplier=1.5\n");
		PdRuntime.Reload r = f.rt().reload();
		assertTrue(r.overridesFailed());
		assertEquals(0.8, f.rt().config().easy().damageMultiplier(), "the properties layer was re-read");
		assertEquals(2.0, f.rt().config().pvpDamageMultiplier(), "the overrides keep their last good data");
		assertNotNull(f.rt().overrides().blockedReason());

		// now the properties file becomes unreadable (malformed escape) while the overrides are repaired
		Files.writeString(f.props(), "easy.damage-multiplier=\\u12\n");
		Files.writeString(f.overrides(), "{\"version\":1,\"values\":{\"pvp.damage-multiplier\":4.0}}");
		PdRuntime.Reload r2 = f.rt().reload();
		assertTrue(r2.config().readFailed());
		assertFalse(r2.overridesFailed());
		assertEquals(0.8, f.rt().config().easy().damageMultiplier(), "the properties layer keeps its last good values");
		assertEquals(4.0, f.rt().config().pvpDamageMultiplier(), "the repaired overrides were adopted");
	}

	@Test
	void anEditedOverridesFileIsNotOverwrittenUntilReload(@TempDir Path dir) throws Exception {
		Fixture f = fixture(dir, "");
		set(f, "pvp.damage-multiplier", "2.0");
		Files.writeString(f.overrides(), "{\"version\":1,\"values\":{\"pvp.damage-multiplier\":6.0}}");
		SetResult r = set(f, "self.damage-multiplier", "2.0");
		assertEquals(SetResult.Kind.REFUSED, r.kind());
		assertTrue(r.message().contains("changed outside the mod"), r.message());
		assertEquals(2.0, f.rt().config().pvpDamageMultiplier(), "the active configuration is unchanged until reload");
		assertFalse(f.rt().reload().anyFailed());
		assertEquals(6.0, f.rt().config().pvpDamageMultiplier());
		assertEquals(SetResult.Kind.APPLIED, set(f, "self.damage-multiplier", "2.0").kind());
	}

	// ------------------------------------------------------------------ E27 at the runtime level

	@Test
	void anInvalidStoredValueIsIgnoredUntilAnExplicitValidSetReplacesIt(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve(OverridesStore.FILE_NAME), "{\"version\":1,\"values\":{\"pvp.damage-multiplier\":\"banana\",\"self.damage-multiplier\":3.0}}");
		Fixture f = fixture(dir, "pvp.damage-multiplier=1.5\n");
		assertEquals(1.5, f.rt().config().pvpDamageMultiplier(), "the invalid override is ignored: the lower layer's value is used");
		assertEquals(3.0, f.rt().config().selfDamageMultiplier(), "the unrelated valid override is active");
		SetResult r = set(f, "pvp.damage-multiplier", "2.5");
		assertEquals(SetResult.Kind.APPLIED, r.kind(), r.message());
		assertTrue(r.message().contains("was not in use") && r.message().contains("banana") && r.message().contains("verified backup"), r.message());
		assertEquals(2.5, f.rt().config().pvpDamageMultiplier());
		assertTrue(withInfix(dir, ".backup-").stream().anyMatch(p -> {
			try {
				return Files.readString(p).contains("banana");
			} catch (IOException e) {
				return false;
			}
		}), "the previous file, with the invalid value, is in a backup");
	}

	// ------------------------------------------------------------------ timing text, reload report, dedupe (D6)

	@Test
	void theAppliedMessageStatesWhenTheChangeTakesEffect(@TempDir Path dir) throws Exception {
		Fixture f = fixture(dir, "");
		assertTrue(set(f, "pvp.damage-multiplier", "2.0").message().contains("applies immediately"));
		assertTrue(set(f, "elite.priority.scan-interval-ticks", "20").message().contains("next scan (within 20 ticks)"));
	}

	@Test
	void reloadWarningsAreDeduplicatedWithinOneReloadAndReportedAgainByTheNext(@TempDir Path dir) throws Exception {
		Fixture f = fixture(dir, "hard.hunger-multiplier=abc\n");
		PdRuntime.Reload first = f.rt().reload();
		long count = first.warnings().stream().filter(w -> w.startsWith("hard.hunger-multiplier=abc")).count();
		assertEquals(1, count);
		assertEquals(first.warnings().size(), new java.util.HashSet<>(first.warnings()).size());
		PdRuntime.Reload second = f.rt().reload();
		assertEquals(1, second.warnings().stream().filter(w -> w.startsWith("hard.hunger-multiplier=abc")).count(), "a later reload reports the same problem again");

		// two layers reporting the identical text appear once
		AvailabilityPolicy.Parsed av = new AvailabilityPolicy.Parsed(Set.of(), List.of("same problem", "other"), false);
		PdRuntime.Reload merged = new PdRuntime.Reload(new PdConfigLoader.Result(PdConfig.defaults(), List.of("same problem"), false), av, OverridesStore.ReloadOutcome.ADOPTED, List.of("same problem", "third"), null);
		assertEquals(List.of("same problem", "other", "third"), merged.warnings());
	}
}
