package com.zaggazach.personaldifficulty;

import com.zaggazach.personaldifficulty.config.PdConfig;
import com.zaggazach.personaldifficulty.legacy.LegacyConfigLoader;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The approved 1.4.0 default changes (plan open item C2, decided; switched on after the Phase 5 performance gate passed), applied to
 * what the frozen 1.3.0 loader produces so that the comparisons against it keep testing everything else unchanged.
 *
 * <p>Only two defaults differ: {@code elite.ai.acquisition-range-multiplier} 1.25 to 3.0 and {@code elite.ai.target-memory-multiplier}
 * 2.0 to 5.0. A 1.3.0 result is translated only where the 1.3.0 loader fell back to its default for one of those keys (the key is
 * absent or blank, or it warned "using default"): there the value becomes the new default and the warning names it. A value the
 * file states explicitly is never touched. ({@code elite.alert.*} is unknown to the 1.3.0 loader; its default reaches both results
 * through the same {@code PdConfig.EliteAlert.DEFAULTS}.)
 *
 * <p>The raised maximums (plan decision 6: 3.0 for the two range multipliers, 5.0 for memory) are applied the same way: where the
 * 1.3.0 loader clamped one of those keys to its old maximum, the value is kept up to the new maximum (no warning) or clamped to the
 * new one (the warning names the new range); a clamp to the minimum only changes the range named in the warning.
 */
final class ApprovedDefaults {
	/** Key to {1.3.0 default, 1.4.0 default}. */
	static final Map<String, double[]> CHANGED = Map.of(
		"elite.ai.acquisition-range-multiplier", new double[] {1.25, 3.0},
		"elite.ai.target-memory-multiplier", new double[] {2.0, 5.0});

	/** Key to {1.3.0 maximum, 1.4.0 maximum} (plan decision 6). */
	static final Map<String, double[]> RAISED = Map.of(
		"elite.ai.acquisition-range-multiplier", new double[] {2.0, 3.0},
		"elite.ai.follow-range-multiplier", new double[] {2.0, 3.0},
		"elite.ai.target-memory-multiplier", new double[] {4.0, 5.0});

	private ApprovedDefaults() {
	}

	static LegacyConfigLoader.Result parse(String text) {
		Properties p = new Properties();
		try {
			p.load(new StringReader(text));
		} catch (IOException | RuntimeException e) {
			p = new Properties(); // unreadable text: the loader used its defaults
		}
		return adapt(p, LegacyConfigLoader.parse(text));
	}

	static LegacyConfigLoader.Result fromProperties(Properties p) {
		return adapt(p, LegacyConfigLoader.fromProperties(p));
	}

	static LegacyConfigLoader.Result load(java.nio.file.Path path) {
		return adapt(readable(path), LegacyConfigLoader.load(path));
	}

	static LegacyConfigLoader.Result loadOrCreate(java.nio.file.Path path) {
		LegacyConfigLoader.Result r = LegacyConfigLoader.loadOrCreate(path);
		return adapt(readable(path), r);
	}

	/** The file's properties, or none if it cannot be read as properties (the loader then used its defaults). */
	private static Properties readable(java.nio.file.Path path) {
		Properties p = new Properties();
		try (var in = java.nio.file.Files.newBufferedReader(path, java.nio.charset.StandardCharsets.UTF_8)) {
			p.load(in);
		} catch (IOException | RuntimeException e) {
			return new Properties();
		}
		return p;
	}

	static LegacyConfigLoader.Result adapt(Properties p, LegacyConfigLoader.Result legacy) {
		PdConfig.EliteAi ai = legacy.config().eliteAi();
		double acquisition = ai.acquisitionRangeMultiplier();
		double follow = ai.followRangeMultiplier();
		double memory = ai.targetMemoryMultiplier();
		List<String> warnings = new ArrayList<>(legacy.warnings());
		// raised maximums: a value the 1.3.0 loader clamped to its old maximum is kept up to the new one
		for (var e : RAISED.entrySet()) {
			String key = e.getKey();
			double oldMax = e.getValue()[0];
			double newMax = e.getValue()[1];
			String oldRange = "is outside [1.0, " + oldMax + "]";
			for (int i = 0; i < warnings.size(); i++) {
				String w = warnings.get(i);
				if (!w.startsWith(key + "=") || !w.contains(oldRange)) {
					continue;
				}
				if (!w.endsWith("; clamped to " + oldMax)) {
					warnings.set(i, w.replace(oldRange, "is outside [1.0, " + newMax + "]")); // clamped to the minimum: only the range text
					continue;
				}
				double v = Double.parseDouble(p.getProperty(key).trim());
				double kept = Math.min(v, newMax);
				if (v <= newMax) {
					warnings.remove(i--);
				} else {
					warnings.set(i, w.replace(oldRange, "is outside [1.0, " + newMax + "]").replace("; clamped to " + oldMax, "; clamped to " + newMax));
				}
				switch (key) {
					case "elite.ai.acquisition-range-multiplier" -> acquisition = kept;
					case "elite.ai.follow-range-multiplier" -> follow = kept;
					default -> memory = kept;
				}
			}
		}
		for (var e : CHANGED.entrySet()) {
			String key = e.getKey();
			double oldDefault = e.getValue()[0];
			double newDefault = e.getValue()[1];
			String raw = p.getProperty(key);
			boolean warnedDefault = false;
			for (int i = 0; i < warnings.size(); i++) {
				String w = warnings.get(i);
				if (w.startsWith(key + "=") && w.endsWith("; using default " + oldDefault)) {
					warnings.set(i, w.substring(0, w.length() - String.valueOf(oldDefault).length()) + newDefault);
					warnedDefault = true;
				}
			}
			if (raw == null || raw.isBlank() || warnedDefault) {
				if (key.equals("elite.ai.acquisition-range-multiplier")) {
					acquisition = newDefault;
				} else {
					memory = newDefault;
				}
			}
		}
		PdConfig.EliteAi adapted = new PdConfig.EliteAi(acquisition, follow, ai.improvedPursuit(), memory,
			ai.advancedCombat(), ai.absoluteRangeCap());
		return new LegacyConfigLoader.Result(withEliteAi(legacy.config(), adapted), List.copyOf(warnings), legacy.readFailed());
	}

	/** A copy of {@code c} with another {@code eliteAi} component (generic over the record, so it never lags behind PdConfig). */
	private static PdConfig withEliteAi(PdConfig c, PdConfig.EliteAi ai) {
		try {
			RecordComponent[] parts = PdConfig.class.getRecordComponents();
			Object[] values = new Object[parts.length];
			Class<?>[] types = new Class<?>[parts.length];
			for (int i = 0; i < parts.length; i++) {
				types[i] = parts[i].getType();
				values[i] = parts[i].getName().equals("eliteAi") ? ai : parts[i].getAccessor().invoke(c);
			}
			return PdConfig.class.getDeclaredConstructor(types).newInstance(values);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}
}
