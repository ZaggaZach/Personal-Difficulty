package com.zaggazach.personaldifficulty.config;

import java.util.Map;

/** Builds the immutable {@link PdConfig} from a complete map of setting values and the per-profile damage-type entries. */
public final class ConfigAssembler {
	private ConfigAssembler() {
	}

	/**
	 * @param values every registry key to its value (Double, Integer or Boolean)
	 * @param family profile name to damage type id to multiplier (a missing profile means no entries)
	 */
	public static PdConfig assemble(Map<String, Object> values, Map<String, Map<String, Double>> family) {
		return new PdConfig(
			profile(values, family, "easy"),
			profile(values, family, "hard"),
			profile(values, family, "elite"),
			d(values, "easy.ranged-min-uncertainty"),
			d(values, "elite.ranged-max-uncertainty"),
			d(values, "elite.armor-effectiveness"),
			d(values, "elite.natural-regeneration-multiplier"),
			d(values, "pvp.damage-multiplier"),
			d(values, "self.damage-multiplier"),
			b(values, "environmental.apply-profile"),
			b(values, "void.apply-profile"),
			b(values, "admin.apply-profile"),
			new PdConfig.RangedMobs(
				b(values, "ranged.skeletons"), b(values, "ranged.illusioners"), b(values, "ranged.drowned"),
				b(values, "ranged.crossbow-mobs"), b(values, "ranged.piglins"), b(values, "ranged.witches")),
			new PdConfig.EliteAi(
				d(values, "elite.ai.acquisition-range-multiplier"), d(values, "elite.ai.follow-range-multiplier"),
				b(values, "elite.ai.improved-pursuit"), d(values, "elite.ai.target-memory-multiplier"), b(values, "elite.ai.advanced-combat"),
				i(values, "elite.ai.absolute-range-cap")),
			new PdConfig.EliteHunt(
				b(values, "elite.priority.enabled"), i(values, "elite.priority.scan-interval-ticks"), i(values, "elite.priority.reacquire-hidden-seconds")),
			new PdConfig.EliteEffects(
				b(values, "elite.effects.enabled"), i(values, "elite.effects.max-duration-seconds"),
				effect(values, "elite.effects.zombie-infection"), effect(values, "elite.effects.spider-venom"),
				effect(values, "elite.effects.cave-spider-venom"), effect(values, "elite.effects.skeleton-shot"),
				effect(values, "elite.effects.drowned-chill")),
			new PdConfig.EliteAlert(
				b(values, "elite.alert.enabled"), i(values, "elite.alert.radius"), i(values, "elite.alert.hops"), i(values, "elite.alert.memory-seconds"))
		);
	}

	private static PdConfig.Profile profile(Map<String, Object> v, Map<String, Map<String, Double>> family, String name) {
		return new PdConfig.Profile(d(v, name + ".damage-multiplier"), d(v, name + ".hunger-multiplier"), family.getOrDefault(name, Map.of()));
	}

	private static PdConfig.MobEffectSetting effect(Map<String, Object> v, String prefix) {
		return new PdConfig.MobEffectSetting(d(v, prefix + "-chance"), i(v, prefix + "-seconds"));
	}

	private static double d(Map<String, Object> v, String key) {
		return (Double) required(v, key);
	}

	private static int i(Map<String, Object> v, String key) {
		return (Integer) required(v, key);
	}

	private static boolean b(Map<String, Object> v, String key) {
		return (Boolean) required(v, key);
	}

	private static Object required(Map<String, Object> v, String key) {
		Object o = v.get(key);
		if (o == null) {
			throw new IllegalStateException("no value for setting " + key);
		}
		return o;
	}
}
