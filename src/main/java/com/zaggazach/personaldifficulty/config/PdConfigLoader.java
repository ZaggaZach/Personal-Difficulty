package com.zaggazach.personaldifficulty.config;

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

/**
 * Reads and validates {@code personaldifficulty.properties}.
 *
 * <p>Invalid, missing, NaN or infinite values never throw: they fall back to the default and add a
 * warning. Out-of-range values are clamped. A file that cannot be read at all yields the defaults
 * plus an error; callers decide whether to keep a previously loaded config instead.
 */
public final class PdConfigLoader {
	public static final String FILE_NAME = "personaldifficulty.properties";

	private static final String OVERRIDE_INFIX = ".damage-type.";

	public record Result(PdConfig config, List<String> warnings, boolean readFailed) {
	}

	private PdConfigLoader() {
	}

	/** Loads the file, writing a commented default file first if none exists. */
	public static Result loadOrCreate(Path file) {
		if (!Files.exists(file)) {
			try {
				Files.createDirectories(file.toAbsolutePath().getParent());
				Files.writeString(file, defaultFileContents(), StandardCharsets.UTF_8);
			} catch (IOException e) {
				return new Result(PdConfig.defaults(), List.of("Could not create default config " + file + ": " + e.getMessage()), true);
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
			return new Result(PdConfig.defaults(), List.of("Could not read config " + file + ": " + e.getMessage()), true);
		}
		return fromProperties(props);
	}

	public static Result parse(String text) {
		Properties props = new Properties();
		try {
			props.load(new StringReader(text));
		} catch (IOException | IllegalArgumentException e) {
			return new Result(PdConfig.defaults(), List.of("Malformed config: " + e.getMessage()), true);
		}
		return fromProperties(props);
	}

	public static Result fromProperties(Properties p) {
		PdConfig d = PdConfig.defaults();
		List<String> warnings = new ArrayList<>();
		double max = PdConfig.MAX_MULTIPLIER;

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
			num(p, "easy.ranged-min-uncertainty", d.easyRangedMinUncertainty(), 0, PdConfig.MAX_UNCERTAINTY, warnings),
			num(p, "elite.ranged-max-uncertainty", d.eliteRangedMaxUncertainty(), 0, PdConfig.MAX_UNCERTAINTY, warnings),
			num(p, "elite.armor-effectiveness", d.eliteArmorEffectiveness(), 0, 1, warnings),
			num(p, "pvp.damage-multiplier", d.pvpDamageMultiplier(), 0, max, warnings),
			num(p, "self.damage-multiplier", d.selfDamageMultiplier(), 0, max, warnings),
			bool(p, "environmental.apply-profile", d.environmentalApplyProfile(), warnings),
			bool(p, "void.apply-profile", d.voidApplyProfile(), warnings),
			bool(p, "admin.apply-profile", d.adminApplyProfile(), warnings),
			mobs
		);
		return new Result(config, List.copyOf(warnings), false);
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
			double v = num(p, key, Double.NaN, 0, PdConfig.MAX_MULTIPLIER, warnings);
			if (!Double.isNaN(v)) {
				out.put(id, v);
			}
		}
		return out;
	}

	public static String defaultFileContents() {
		try (InputStream in = PdConfigLoader.class.getResourceAsStream("/personaldifficulty.default.properties")) {
			if (in != null) {
				return new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
		} catch (IOException ignored) {
			// fall through to the empty-but-valid fallback
		}
		return "# Personal Difficulty: defaults are used for every missing key.\n";
	}
}
