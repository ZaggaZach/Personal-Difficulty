package com.zaggazach.personaldifficulty.config;

import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.config.SettingDef.Timing;
import com.zaggazach.personaldifficulty.config.SettingDef.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The single table of every configurable setting. The properties loader, the overrides layer, the strict validator and the
 * {@code admin get/set/unset/config} commands (and later the GUI) all read it, so a setting exists in exactly one place.
 *
 * <p>Order matters: it is the order in which the legacy loader evaluated the keys, which fixes the order of the warnings it
 * produced (a differential test compares them). Bounds are the existing {@link PdConfig} constants, so changing a clamp later is
 * a one-line change here. A test fails if a {@link PdConfig} field has no entry.
 */
public final class SettingRegistry {
	private static final List<SettingEntry> ENTRIES = build();
	private static final List<SettingDef> SCALARS = ENTRIES.stream().filter(e -> e instanceof SettingDef).map(e -> (SettingDef) e).toList();
	private static final List<FamilyDef> FAMILIES = ENTRIES.stream().filter(e -> e instanceof FamilyDef).map(e -> (FamilyDef) e).toList();
	private static final Map<String, SettingDef> BY_KEY = index();

	private SettingRegistry() {
	}

	/** Every entry, sorted by evaluation order. */
	public static List<SettingEntry> entries() {
		return ENTRIES;
	}

	public static List<SettingDef> scalars() {
		return SCALARS;
	}

	public static List<FamilyDef> families() {
		return FAMILIES;
	}

	public static Optional<SettingDef> find(String key) {
		return Optional.ofNullable(BY_KEY.get(key));
	}

	public static Optional<FamilyDef> family(String profile) {
		return FAMILIES.stream().filter(f -> f.profile().equals(profile)).findFirst();
	}

	private static Map<String, SettingDef> index() {
		Map<String, SettingDef> out = new LinkedHashMap<>();
		SCALARS.forEach(d -> out.put(d.key(), d));
		return Map.copyOf(out);
	}

	private static SettingDef num(int order, String key, double min, double max, double def, double step, String display, Timing timing, String path, Function<PdConfig, Object> read) {
		return new SettingDef(key, Type.DOUBLE, min, max, def, step, display, timing, order, path, read);
	}

	private static SettingDef whole(int order, String key, int min, int max, int def, String display, Timing timing, String path, Function<PdConfig, Object> read) {
		return new SettingDef(key, Type.INT, min, max, def, 1, display, timing, order, path, read);
	}

	private static SettingDef flag(int order, String key, boolean def, String display, Timing timing, String path, Function<PdConfig, Object> read) {
		return new SettingDef(key, Type.BOOL, 0, 1, def, 0, display, timing, order, path, read);
	}

	private static List<SettingEntry> build() {
		List<SettingEntry> e = new ArrayList<>();
		Timing live = Timing.LIVE;
		Timing scan = Timing.NEXT_SCAN;
		double mx = PdConfig.MAX_MULTIPLIER;
		int o = 0;
		// profiles: damage, hunger, then the damage-type family, in the legacy evaluation order
		e.add(num(o += 10, "easy.damage-multiplier", 0, mx, 0.60, 0.05, "Easy: damage taken multiplier", live, "easy.damageMultiplier", c -> c.easy().damageMultiplier()));
		e.add(num(o += 10, "easy.hunger-multiplier", 0, mx, 0.50, 0.05, "Easy: hunger multiplier", live, "easy.hungerMultiplier", c -> c.easy().hungerMultiplier()));
		e.add(new FamilyDef("easy", PersonalDifficultyMode.EASY, "easy.damage-type.", o += 10, "easy.damageTypeOverrides", c -> c.easy().damageTypeOverrides()));
		e.add(num(o += 10, "hard.damage-multiplier", 0, mx, 1.25, 0.05, "Hard: damage taken multiplier", live, "hard.damageMultiplier", c -> c.hard().damageMultiplier()));
		e.add(num(o += 10, "hard.hunger-multiplier", 0, mx, 1.25, 0.05, "Hard: hunger multiplier", live, "hard.hungerMultiplier", c -> c.hard().hungerMultiplier()));
		e.add(new FamilyDef("hard", PersonalDifficultyMode.HARD, "hard.damage-type.", o += 10, "hard.damageTypeOverrides", c -> c.hard().damageTypeOverrides()));
		e.add(num(o += 10, "elite.damage-multiplier", 0, mx, 2.00, 0.05, "Elite: damage taken multiplier", live, "elite.damageMultiplier", c -> c.elite().damageMultiplier()));
		e.add(num(o += 10, "elite.hunger-multiplier", 0, mx, 2.00, 0.05, "Elite: hunger multiplier", live, "elite.hungerMultiplier", c -> c.elite().hungerMultiplier()));
		e.add(new FamilyDef("elite", PersonalDifficultyMode.ELITE, "elite.damage-type.", o += 10, "elite.damageTypeOverrides", c -> c.elite().damageTypeOverrides()));
		// which ranged attackers have their aim adjusted
		e.add(flag(o += 10, "ranged.skeletons", true, "Aim adjusted: skeletons", live, "rangedMobs.skeletons", c -> c.rangedMobs().skeletons()));
		e.add(flag(o += 10, "ranged.illusioners", true, "Aim adjusted: illusioners", live, "rangedMobs.illusioners", c -> c.rangedMobs().illusioners()));
		e.add(flag(o += 10, "ranged.drowned", true, "Aim adjusted: drowned", live, "rangedMobs.drowned", c -> c.rangedMobs().drowned()));
		e.add(flag(o += 10, "ranged.crossbow-mobs", true, "Aim adjusted: crossbow mobs", live, "rangedMobs.crossbowMobs", c -> c.rangedMobs().crossbowMobs()));
		e.add(flag(o += 10, "ranged.piglins", true, "Aim adjusted: piglins", live, "rangedMobs.piglins", c -> c.rangedMobs().piglins()));
		e.add(flag(o += 10, "ranged.witches", true, "Aim adjusted: witches", live, "rangedMobs.witches", c -> c.rangedMobs().witches()));
		// global values
		e.add(num(o += 10, "easy.ranged-min-uncertainty", 0, PdConfig.MAX_UNCERTAINTY, 14.0, 0.5, "Easy: minimum ranged aim uncertainty", live, "easyRangedMinUncertainty", PdConfig::easyRangedMinUncertainty));
		e.add(num(o += 10, "elite.ranged-max-uncertainty", 0, PdConfig.MAX_UNCERTAINTY, 1.0, 0.5, "Elite: maximum ranged aim uncertainty", live, "eliteRangedMaxUncertainty", PdConfig::eliteRangedMaxUncertainty));
		e.add(num(o += 10, "elite.armor-effectiveness", 0, 1, 0.60, 0.05, "Elite: armour effectiveness", live, "eliteArmorEffectiveness", PdConfig::eliteArmorEffectiveness));
		e.add(num(o += 10, "elite.natural-regeneration-multiplier", PdConfig.MIN_REGEN_MULTIPLIER, 1, 0.50, 0.05, "Elite: natural regeneration speed", live, "eliteRegenMultiplier", PdConfig::eliteRegenMultiplier));
		e.add(num(o += 10, "pvp.damage-multiplier", 0, mx, 1.0, 0.05, "Player-versus-player damage multiplier", live, "pvpDamageMultiplier", PdConfig::pvpDamageMultiplier));
		e.add(num(o += 10, "self.damage-multiplier", 0, mx, 1.0, 0.05, "Self-inflicted damage multiplier", live, "selfDamageMultiplier", PdConfig::selfDamageMultiplier));
		e.add(flag(o += 10, "environmental.apply-profile", true, "Profile applies to environmental damage", live, "environmentalApplyProfile", PdConfig::environmentalApplyProfile));
		e.add(flag(o += 10, "void.apply-profile", false, "Profile applies to void damage", live, "voidApplyProfile", PdConfig::voidApplyProfile));
		e.add(flag(o += 10, "admin.apply-profile", false, "Profile applies to administrative damage", live, "adminApplyProfile", PdConfig::adminApplyProfile));
		// Elite AI
		e.add(num(o += 10, "elite.ai.acquisition-range-multiplier", 1, PdConfig.MAX_AI_RANGE_MULTIPLIER, 3.0, 0.05, "Elite AI: target acquisition range multiplier", live, "eliteAi.acquisitionRangeMultiplier", c -> c.eliteAi().acquisitionRangeMultiplier()));
		e.add(num(o += 10, "elite.ai.follow-range-multiplier", 1, PdConfig.MAX_AI_RANGE_MULTIPLIER, 1.25, 0.05, "Elite AI: pursuit range multiplier", live, "eliteAi.followRangeMultiplier", c -> c.eliteAi().followRangeMultiplier()));
		e.add(flag(o += 10, "elite.ai.improved-pursuit", true, "Elite AI: improved pursuit", live, "eliteAi.improvedPursuit", c -> c.eliteAi().improvedPursuit()));
		e.add(num(o += 10, "elite.ai.target-memory-multiplier", 1, PdConfig.MAX_MEMORY_MULTIPLIER, 5.0, 0.1, "Elite AI: target memory multiplier", live, "eliteAi.targetMemoryMultiplier", c -> c.eliteAi().targetMemoryMultiplier()));
		e.add(flag(o += 10, "elite.ai.advanced-combat", true, "Elite AI: advanced combat", live, "eliteAi.advancedCombat", c -> c.eliteAi().advancedCombat()));
		e.add(new SettingDef("elite.ai.absolute-range-cap", Type.INT, PdConfig.MIN_RANGE_CAP, PdConfig.MAX_RANGE_CAP, PdConfig.DEFAULT_RANGE_CAP, 8,
			"Elite AI: absolute range cap (blocks)", live, o += 10, "eliteAi.absoluteRangeCap", c -> c.eliteAi().absoluteRangeCap()));
		// Elite hunting (read by the per-mob scan)
		e.add(flag(o += 10, "elite.priority.enabled", true, "Elite hunting: prefer Elite players", scan, "eliteHunt.priorityEnabled", c -> c.eliteHunt().priorityEnabled()));
		e.add(whole(o += 10, "elite.priority.scan-interval-ticks", PdConfig.MIN_SCAN_INTERVAL_TICKS, PdConfig.MAX_SCAN_INTERVAL_TICKS, 10, "Elite hunting: scan interval (ticks)", scan, "eliteHunt.scanIntervalTicks", c -> c.eliteHunt().scanIntervalTicks()));
		e.add(whole(o += 10, "elite.priority.reacquire-hidden-seconds", 0, PdConfig.MAX_REACQUIRE_SECONDS, 8, "Elite hunting: hidden-target re-acquire (seconds)", scan, "eliteHunt.reacquireHiddenSeconds", c -> c.eliteHunt().reacquireHiddenSeconds()));
		// Elite alert network and last-known-position search (1.4.0)
		e.add(flag(o += 10, "elite.alert.enabled", PdConfig.EliteAlert.DEFAULTS.enabled(), "Elite hunting: alert network and last-known search", scan, "eliteAlert.enabled", c -> c.eliteAlert().enabled()));
		e.add(whole(o += 10, "elite.alert.radius", PdConfig.MIN_ALERT_RADIUS, PdConfig.MAX_ALERT_RADIUS, PdConfig.EliteAlert.DEFAULTS.radius(), "Elite hunting: alert radius (blocks)", scan, "eliteAlert.radius", c -> c.eliteAlert().radius()));
		e.add(whole(o += 10, "elite.alert.hops", 0, PdConfig.MAX_ALERT_HOPS, PdConfig.EliteAlert.DEFAULTS.hops(), "Elite hunting: alert hops", scan, "eliteAlert.hops", c -> c.eliteAlert().hops()));
		e.add(whole(o += 10, "elite.alert.memory-seconds", PdConfig.MIN_ALERT_MEMORY_SECONDS, PdConfig.MAX_ALERT_MEMORY_SECONDS, PdConfig.EliteAlert.DEFAULTS.memorySeconds(), "Elite hunting: alert memory (seconds)", scan, "eliteAlert.memorySeconds", c -> c.eliteAlert().memorySeconds()));
		// Elite mob effects
		e.add(flag(o += 10, "elite.effects.enabled", true, "Elite effects: enabled", live, "eliteEffects.enabled", c -> c.eliteEffects().enabled()));
		e.add(whole(o += 10, "elite.effects.max-duration-seconds", 1, PdConfig.MAX_EFFECT_SECONDS, 30, "Elite effects: maximum duration (seconds)", live, "eliteEffects.maxDurationSeconds", c -> c.eliteEffects().maxDurationSeconds()));
		String[][] effects = {
			{"zombie-infection", "zombie", "Zombie infection", "0.35", "8"},
			{"spider-venom", "spider", "Spider venom", "0.35", "5"},
			{"cave-spider-venom", "caveSpider", "Cave spider venom", "0.50", "6"},
			{"skeleton-shot", "skeleton", "Skeleton shot", "0.25", "4"},
			{"drowned-chill", "drowned", "Drowned chill", "0.30", "3"}
		};
		for (String[] f : effects) {
			String key = "elite.effects." + f[0];
			String field = f[1];
			e.add(num(o += 10, key + "-chance", 0, 1, Double.parseDouble(f[3]), 0.05, "Elite effects: " + f[2] + " chance", live, "eliteEffects." + field + ".chance", c -> effect(c, field).chance()));
			e.add(whole(o += 10, key + "-seconds", 1, PdConfig.MAX_EFFECT_SECONDS, Integer.parseInt(f[4]), "Elite effects: " + f[2] + " duration (seconds)", live, "eliteEffects." + field + ".seconds", c -> effect(c, field).seconds()));
		}
		return List.copyOf(e);
	}

	private static PdConfig.MobEffectSetting effect(PdConfig c, String field) {
		PdConfig.EliteEffects fx = c.eliteEffects();
		return switch (field) {
			case "zombie" -> fx.zombie();
			case "spider" -> fx.spider();
			case "caveSpider" -> fx.caveSpider();
			case "skeleton" -> fx.skeleton();
			case "drowned" -> fx.drowned();
			default -> throw new IllegalArgumentException(field);
		};
	}
}
