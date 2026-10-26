package com.zaggazach.personaldifficulty.legacy;

import com.zaggazach.personaldifficulty.config.PdConfig;
import com.zaggazach.personaldifficulty.config.PdConfigLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/*
 * FROZEN ORACLE. This is the properties loader exactly as committed in 60bce2a6afb795dd56f06c4b6ce28e367a2e55fa
 * (src/main/java/.../config/PdConfigLoader.java), changed only mechanically: renamed, its bounds and defaults inlined as literals,
 * and defaultFileContents() delegating to the resource reader. It exists so the registry-driven loader can be compared with the
 * previous behaviour (config, warnings, readFailed). Do NOT edit its logic to make a comparison pass; a mismatch is a finding to
 * investigate. Its integrity is pinned by FrozenLoaderIntegrityTest.
 */
/**
 * Reads and validates {@code personaldifficulty.properties}.
 *
 * <p>Invalid, missing, NaN or infinite values never throw: they fall back to the default and add a
 * warning. Out-of-range values are clamped. A file that cannot be read at all yields the defaults
 * plus an error; callers decide whether to keep a previously loaded config instead.
 */
public final class LegacyConfigLoader {
	public static final String FILE_NAME = "personaldifficulty.properties";

	private static final String OVERRIDE_INFIX = ".damage-type.";

	// Bounds frozen as literals from 60bce2a so later changes to PdConfig's constants can never move this oracle.
	private static final double L_MAX_MULTIPLIER = 10.0;
	private static final double L_MAX_UNCERTAINTY = 60.0;
	private static final double L_MIN_REGEN_MULTIPLIER = 0.1;
	private static final double L_MAX_AI_RANGE_MULTIPLIER = 2.0;
	private static final double L_MAX_MEMORY_MULTIPLIER = 4.0;
	private static final int L_MIN_SCAN_INTERVAL_TICKS = 5;
	private static final int L_MAX_SCAN_INTERVAL_TICKS = 40;
	private static final int L_MAX_REACQUIRE_SECONDS = 30;
	private static final int L_MAX_EFFECT_SECONDS = 60;

	/** The built-in defaults of 60bce2a, as literals. */
	public static PdConfig defaults() {
		return new PdConfig(
			new PdConfig.Profile(0.60, 0.50, Map.of()),
			new PdConfig.Profile(1.25, 1.25, Map.of()),
			new PdConfig.Profile(2.00, 2.00, Map.of()),
			14.0,
			1.0,
			0.60,
			0.50,
			1.0,
			1.0,
			true,
			false,
			false,
			new PdConfig.RangedMobs(true, true, true, true, true, true),
			new PdConfig.EliteAi(1.25, 1.25, true, 2.0, true),
			new PdConfig.EliteHunt(true, 10, 8),
			new PdConfig.EliteEffects(true, 30, new PdConfig.MobEffectSetting(0.35, 8), new PdConfig.MobEffectSetting(0.35, 5), new PdConfig.MobEffectSetting(0.50, 6),
				new PdConfig.MobEffectSetting(0.25, 4), new PdConfig.MobEffectSetting(0.30, 3))
		);
	}


	public record Result(PdConfig config, List<String> warnings, boolean readFailed) {
	}

	private LegacyConfigLoader() {
	}

	/** Loads the file, writing a commented default file first if none exists. */
	public static Result loadOrCreate(Path file) {
		if (!Files.exists(file)) {
			try {
				Files.createDirectories(file.toAbsolutePath().getParent());
				Files.writeString(file, defaultFileContents(), StandardCharsets.UTF_8);
			} catch (IOException e) {
				return new Result(defaults(), List.of("Could not create default config " + file + ": " + e.getMessage()), true);
			}
		}
		return load(file);
	}

	public static Result load(Path file) {
		Properties props = new Properties();
		try (InputStream in = Files.newInputStream(file); Reader r = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
			props.load(r);
		} catch (IOException | IllegalArgumentException e) {
			// IllegalArgumentException: malformed \\uXXXX escape
			return new Result(defaults(), List.of("Could not read config " + file + ": " + e.getMessage()), true);
		}
		return fromProperties(props);
	}

	public static Result parse(String text) {
		Properties props = new Properties();
		try {
			props.load(new StringReader(text));
		} catch (IOException | IllegalArgumentException e) {
			return new Result(defaults(), List.of("Malformed config: " + e.getMessage()), true);
		}
		return fromProperties(props);
	}

	public static Result fromProperties(Properties p) {
		PdConfig d = defaults();
		List<String> warnings = new ArrayList<>();
		double max = L_MAX_MULTIPLIER;

		PdConfig.Profile easy = new PdConfig.Profile(
			num(p, "easy.damage-multiplier", d.easy().damageMultiplier(), 0, max, warnings),
			num(p, "easy.hunger-multiplier", d.easy().hungerMultiplier(), 0, max, warnings),
			overrides(p, "easy", warnings)
		);
		PdConfig.Profile hard = new PdConfig.Profile(
			num(p, "hard.damage-multiplier", d.hard().damageMultiplier(), 0, max, warnings),
			num(p, "hard.hunger-multiplier", d.hard().hungerMultiplier(), 0, max, warnings),
			overrides(p, "hard", warnings)
		);
		PdConfig.Profile elite = new PdConfig.Profile(
			num(p, "elite.damage-multiplier", d.elite().damageMultiplier(), 0, max, warnings),
			num(p, "elite.hunger-multiplier", d.elite().hungerMultiplier(), 0, max, warnings),
			overrides(p, "elite", warnings)
		);
		PdConfig.RangedMobs mobs = new PdConfig.RangedMobs(
			bool(p, "ranged.skeletons", d.rangedMobs().skeletons(), warnings),
			bool(p, "ranged.illusioners", d.rangedMobs().illusioners(), warnings),
			bool(p, "ranged.drowned", d.rangedMobs().drowned(), warnings),
			bool(p, "ranged.crossbow-mobs", d.rangedMobs().crossbowMobs(), warnings),
			bool(p, "ranged.piglins", d.rangedMobs().piglins(), warnings),
			bool(p, "ranged.witches", d.rangedMobs().witches(), warnings)
		);
		PdConfig config = new PdConfig(
			easy,
			hard,
			elite,
			num(p, "easy.ranged-min-uncertainty", d.easyRangedMinUncertainty(), 0, L_MAX_UNCERTAINTY, warnings),
			num(p, "elite.ranged-max-uncertainty", d.eliteRangedMaxUncertainty(), 0, L_MAX_UNCERTAINTY, warnings),
			num(p, "elite.armor-effectiveness", d.eliteArmorEffectiveness(), 0, 1, warnings),
			num(p, "elite.natural-regeneration-multiplier", d.eliteRegenMultiplier(), L_MIN_REGEN_MULTIPLIER, 1, warnings),
			num(p, "pvp.damage-multiplier", d.pvpDamageMultiplier(), 0, max, warnings),
			num(p, "self.damage-multiplier", d.selfDamageMultiplier(), 0, max, warnings),
			bool(p, "environmental.apply-profile", d.environmentalApplyProfile(), warnings),
			bool(p, "void.apply-profile", d.voidApplyProfile(), warnings),
			bool(p, "admin.apply-profile", d.adminApplyProfile(), warnings),
			mobs,
			new PdConfig.EliteAi(
				num(p, "elite.ai.acquisition-range-multiplier", d.eliteAi().acquisitionRangeMultiplier(), 1, L_MAX_AI_RANGE_MULTIPLIER, warnings),
				num(p, "elite.ai.follow-range-multiplier", d.eliteAi().followRangeMultiplier(), 1, L_MAX_AI_RANGE_MULTIPLIER, warnings),
				bool(p, "elite.ai.improved-pursuit", d.eliteAi().improvedPursuit(), warnings),
				num(p, "elite.ai.target-memory-multiplier", d.eliteAi().targetMemoryMultiplier(), 1, L_MAX_MEMORY_MULTIPLIER, warnings),
				bool(p, "elite.ai.advanced-combat", d.eliteAi().advancedCombat(), warnings)
			),
			new PdConfig.EliteHunt(
				bool(p, "elite.priority.enabled", d.eliteHunt().priorityEnabled(), warnings),
				whole(p, "elite.priority.scan-interval-ticks", d.eliteHunt().scanIntervalTicks(), L_MIN_SCAN_INTERVAL_TICKS, L_MAX_SCAN_INTERVAL_TICKS, warnings),
				whole(p, "elite.priority.reacquire-hidden-seconds", d.eliteHunt().reacquireHiddenSeconds(), 0, L_MAX_REACQUIRE_SECONDS, warnings)
			),
			new PdConfig.EliteEffects(
				bool(p, "elite.effects.enabled", d.eliteEffects().enabled(), warnings),
				whole(p, "elite.effects.max-duration-seconds", d.eliteEffects().maxDurationSeconds(), 1, L_MAX_EFFECT_SECONDS, warnings),
				effect(p, "elite.effects.zombie-infection", d.eliteEffects().zombie(), warnings),
				effect(p, "elite.effects.spider-venom", d.eliteEffects().spider(), warnings),
				effect(p, "elite.effects.cave-spider-venom", d.eliteEffects().caveSpider(), warnings),
				effect(p, "elite.effects.skeleton-shot", d.eliteEffects().skeleton(), warnings),
				effect(p, "elite.effects.drowned-chill", d.eliteEffects().drowned(), warnings)
			)
		);
		return new Result(config, List.copyOf(warnings), false);
	}

	private static int whole(Properties p, String key, int def, int min, int max, List<String> warnings) {
		return (int) Math.round(num(p, key, def, min, max, warnings));
	}

	private static PdConfig.MobEffectSetting effect(Properties p, String prefix, PdConfig.MobEffectSetting def, List<String> warnings) {
		return new PdConfig.MobEffectSetting(
			num(p, prefix + "-chance", def.chance(), 0, 1, warnings),
			whole(p, prefix + "-seconds", def.seconds(), 1, L_MAX_EFFECT_SECONDS, warnings));
	}

	private static double num(Properties p, String key, double def, double min, double max, List<String> warnings) {
		String raw = p.getProperty(key);
		if (raw == null || raw.isBlank()) {
			return def;
		}
		double v;
		try {
			v = Double.parseDouble(raw.trim());
		} catch (NumberFormatException e) {
			warnings.add(key + "=" + raw + " is not a number; using default " + def);
			return def;
		}
		if (!Double.isFinite(v)) {
			warnings.add(key + "=" + raw + " is not finite; using default " + def);
			return def;
		}
		if (v < min || v > max) {
			double clamped = Math.max(min, Math.min(max, v));
			warnings.add(key + "=" + raw + " is outside [" + min + ", " + max + "]; clamped to " + clamped);
			return clamped;
		}
		return v;
	}

	private static boolean bool(Properties p, String key, boolean def, List<String> warnings) {
		String raw = p.getProperty(key);
		if (raw == null || raw.isBlank()) {
			return def;
		}
		String t = raw.trim().toLowerCase(Locale.ROOT);
		if (t.equals("true")) {
			return true;
		}
		if (t.equals("false")) {
			return false;
		}
		warnings.add(key + "=" + raw + " is not true/false; using default " + def);
		return def;
	}

	/**
	 * Per-damage-type overrides: {@code easy.damage-type.minecraft.fall=0.3}. The first dot after the
	 * prefix separates namespace from path (vanilla damage type paths contain no dots).
	 */
	private static Map<String, Double> overrides(Properties p, String profile, List<String> warnings) {
		String prefix = profile + OVERRIDE_INFIX;
		Map<String, Double> out = new TreeMap<>();
		for (String key : p.stringPropertyNames()) {
			if (!key.startsWith(prefix)) {
				continue;
			}
			String rest = key.substring(prefix.length());
			int dot = rest.indexOf('.');
			if (dot <= 0 || dot == rest.length() - 1) {
				warnings.add(key + ": expected <namespace>.<path>; ignored");
				continue;
			}
			String id = rest.substring(0, dot) + ":" + rest.substring(dot + 1);
			double v = num(p, key, Double.NaN, 0, L_MAX_MULTIPLIER, warnings);
			if (!Double.isNaN(v)) {
				out.put(id, v);
			}
		}
		return out;
	}

	public static String defaultFileContents() {
		return PdConfigLoader.defaultFileContents();
	}
}
