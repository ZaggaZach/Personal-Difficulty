package com.zaggazach.personaldifficulty;

import static org.junit.jupiter.api.Assertions.*;

import com.zaggazach.personaldifficulty.config.ConfigState;
import com.zaggazach.personaldifficulty.config.ConfigState.Source;
import com.zaggazach.personaldifficulty.config.FamilyKey;
import com.zaggazach.personaldifficulty.config.OverridesLayer;
import com.zaggazach.personaldifficulty.config.PdConfig;
import com.zaggazach.personaldifficulty.config.PdConfigLoader;
import com.zaggazach.personaldifficulty.config.PropertiesLayer;
import com.zaggazach.personaldifficulty.config.SettingDef;
import com.zaggazach.personaldifficulty.config.SettingRegistry;
import com.zaggazach.personaldifficulty.legacy.LegacyConfigLoader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Layering: defaults, then properties, then overrides; the source of each value; and "no overrides means identical behaviour". */
class ConfigLayersTest {
	private static PropertiesLayer layer(String text) {
		return PdConfigLoader.parseLayer(text).layer();
	}

	private static SettingDef def(String key) {
		return SettingRegistry.find(key).orElseThrow();
	}

	private static OverridesLayer overrides(Map<String, Object> scalars, Map<String, Map<String, Double>> family) {
		return new OverridesLayer(scalars, family, List.of());
	}

	@Test
	void anEmptyOverridesLayerYieldsExactlyTheLegacyConfigForManyInputs() {
		List<String> texts = List.of(
			"", "easy.damage-multiplier=0.5\n", "hard.hunger-multiplier=99\nelite.priority.scan-interval-ticks=7.5\n", "elite.effects.enabled=maybe\nelite.ai.target-memory-multiplier=NaN\n",
			"easy.damage-type.minecraft.fall=0.3\nelite.damage-type.minecraft.explosion=0.4\nhard.damage-type.ns.a.b=2\n", PdConfigLoader.defaultFileContents(),
			"ranged.skeletons=false\nvoid.apply-profile=true\nadmin.apply-profile=true\nenvironmental.apply-profile=false\n", "pvp.damage-multiplier=-1\nself.damage-multiplier=1e400\n"
		);
		for (String text : texts) {
			ConfigState state = ConfigState.compose(layer(text), OverridesLayer.EMPTY);
			assertEquals(LegacyConfigLoader.parse(text).config(), state.effective(), "text: " + text);
			assertEquals(PdConfigLoader.parse(text).config(), state.effective());
		}
	}

	@Test
	void precedenceAndSourcesForScalars() {
		SettingDef easy = def("easy.damage-multiplier");
		// default only
		ConfigState onlyDefault = ConfigState.compose(layer(""), OverridesLayer.EMPTY);
		assertEquals(0.60, onlyDefault.effectiveValue(easy));
		assertEquals(Source.DEFAULT, onlyDefault.sourceOf(easy));
		// properties beat the default
		ConfigState props = ConfigState.compose(layer("easy.damage-multiplier=0.5"), OverridesLayer.EMPTY);
		assertEquals(0.5, props.effectiveValue(easy));
		assertEquals(Source.PROPERTIES, props.sourceOf(easy));
		assertEquals(0.5, props.effective().easy().damageMultiplier());
		// an override beats both, and the lower value stays visible
		ConfigState over = ConfigState.compose(layer("easy.damage-multiplier=0.5"), overrides(Map.of("easy.damage-multiplier", 0.7), Map.of()));
		assertEquals(0.7, over.effectiveValue(easy));
		assertEquals(Source.OVERRIDE, over.sourceOf(easy));
		assertEquals(0.5, over.lowerValue(easy));
		assertEquals(0.7, over.effective().easy().damageMultiplier());
		// an override over a default
		ConfigState overDefault = ConfigState.compose(layer(""), overrides(Map.of("easy.damage-multiplier", 0.7), Map.of()));
		assertEquals(Source.OVERRIDE, overDefault.sourceOf(easy));
		assertEquals(0.60, overDefault.lowerValue(easy));
	}

	@Test
	void aPropertiesValueThatFellBackToTheDefaultIsLabelledDefault_aClampedOneProperties() {
		SettingDef hunger = def("hard.hunger-multiplier");
		ConfigState invalid = ConfigState.compose(layer("hard.hunger-multiplier=abc"), OverridesLayer.EMPTY);
		assertEquals(1.25, invalid.effectiveValue(hunger));
		assertEquals(Source.DEFAULT, invalid.sourceOf(hunger));
		ConfigState clamped = ConfigState.compose(layer("hard.hunger-multiplier=99"), OverridesLayer.EMPTY);
		assertEquals(PdConfig.MAX_MULTIPLIER, clamped.effectiveValue(hunger));
		assertEquals(Source.PROPERTIES, clamped.sourceOf(hunger));
		ConfigState blank = ConfigState.compose(layer("hard.hunger-multiplier=   "), OverridesLayer.EMPTY);
		assertEquals(Source.DEFAULT, blank.sourceOf(hunger));
	}

	@Test
	void everyScalarKeyCanBeOverriddenAndOnlyThatKeyChanges() {
		PdConfig base = ConfigState.compose(layer(""), OverridesLayer.EMPTY).effective();
		for (SettingDef d : SettingRegistry.scalars()) {
			Object changed = switch (d.type()) {
				case BOOL -> !((Boolean) d.defaultValue());
				case INT -> ((Integer) d.defaultValue()) == (int) d.max() ? (int) d.min() : (int) d.max();
				case DOUBLE -> ((Double) d.defaultValue()) == d.max() ? d.min() : d.max();
			};
			ConfigState s = ConfigState.compose(layer(""), overrides(Map.of(d.key(), changed), Map.of()));
			assertEquals(changed, d.read().apply(s.effective()), d.key() + " is applied to PdConfig");
			assertNotEquals(base, s.effective(), d.key());
			for (SettingDef other : SettingRegistry.scalars()) {
				if (other != d) {
					assertEquals(other.read().apply(base), other.read().apply(s.effective()), "overriding " + d.key() + " must not change " + other.key());
				}
			}
		}
	}

	@Test
	void damageTypeEntriesMergeWithTheOverrideWinning() {
		PropertiesLayer props = layer("easy.damage-type.minecraft.fall=0.3\neasy.damage-type.minecraft.explosion=0.4\nelite.damage-type.minecraft.fall=3\n");
		OverridesLayer ov = overrides(Map.of(), Map.of("easy", Map.of("minecraft:fall", 0.5, "ns:new", 0.9)));
		ConfigState s = ConfigState.compose(props, ov);
		assertEquals(Map.of("minecraft:fall", 0.5, "minecraft:explosion", 0.4, "ns:new", 0.9), s.effective().easy().damageTypeOverrides());
		assertEquals(Map.of("minecraft:fall", 3.0), s.effective().elite().damageTypeOverrides());
		assertEquals(Map.of(), s.effective().hard().damageTypeOverrides());
		FamilyKey fall = ((FamilyKey.Ok) FamilyKey.parse("easy.damage-type.minecraft.fall")).key();
		FamilyKey explosion = ((FamilyKey.Ok) FamilyKey.parse("easy.damage-type.minecraft.explosion")).key();
		FamilyKey fresh = ((FamilyKey.Ok) FamilyKey.parse("easy.damage-type.ns.new")).key();
		FamilyKey none = ((FamilyKey.Ok) FamilyKey.parse("hard.damage-type.minecraft.fall")).key();
		assertEquals(Source.OVERRIDE, s.sourceOf(fall));
		assertEquals(0.3, s.lowerFamilyValue(fall).orElseThrow());
		assertEquals(0.5, s.effectiveFamilyValue(fall).orElseThrow());
		assertEquals(Source.PROPERTIES, s.sourceOf(explosion));
		assertEquals(Source.OVERRIDE, s.sourceOf(fresh));
		assertTrue(s.lowerFamilyValue(fresh).isEmpty(), "no lower-layer entry: the lower layer is 'absent'");
		assertEquals(Source.DEFAULT, s.sourceOf(none));
		assertTrue(s.effectiveFamilyValue(none).isEmpty());
	}

	@Test
	void composingNeverMutatesTheLayers() {
		PropertiesLayer props = layer("easy.damage-multiplier=0.5\neasy.damage-type.minecraft.fall=0.3");
		OverridesLayer ov = overrides(Map.of("easy.damage-multiplier", 0.7), Map.of("easy", Map.of("minecraft:fall", 0.5)));
		ConfigState.compose(props, ov);
		assertEquals(0.5, props.values().get("easy.damage-multiplier"));
		assertEquals(Map.of("minecraft:fall", 0.3), props.family().get("easy"));
		assertEquals(Map.of("minecraft:fall", 0.5), ov.family().get("easy"));
		assertThrows(UnsupportedOperationException.class, () -> props.values().put("x", 1.0));
		assertThrows(UnsupportedOperationException.class, () -> ov.scalars().put("x", 1.0));
	}
}
