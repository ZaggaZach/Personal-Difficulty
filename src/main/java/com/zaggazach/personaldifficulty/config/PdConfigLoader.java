package com.zaggazach.personaldifficulty.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Reads and validates {@code personaldifficulty.properties}.
 *
 * <p>Invalid, missing, NaN or infinite values never throw: they fall back to the default and add a
 * warning. Out-of-range values are clamped. A file that cannot be read at all yields the defaults
 * plus an error; callers decide whether to keep a previously loaded config instead.
 */
public final class PdConfigLoader {
	public static final String FILE_NAME = "personaldifficulty.properties";

	public record Result(PdConfig config, List<String> warnings, boolean readFailed) {
	}

	private PdConfigLoader() {
	}

	/** Loads the file, writing a commented default file first if none exists. */
	public static Result loadOrCreate(Path file) {
		return toResult(loadOrCreateLayer(file));
	}

	/** Same as {@link #loadOrCreate} but returns the layer (used by the layered configuration). */
	public static LayerResult loadOrCreateLayer(Path file) {
		if (!Files.exists(file)) {
			try {
				Files.createDirectories(file.toAbsolutePath().getParent());
				Files.writeString(file, defaultFileContents(), StandardCharsets.UTF_8);
			} catch (IOException e) {
				return new LayerResult(null, List.of("Could not create default config " + file + ": " + e.getMessage()), true);
			}
		}
		return loadLayer(file);
	}

	/** Outcome of reading the file as a layer: {@code layer} is null exactly when {@code readFailed}. */
	public record LayerResult(@Nullable PropertiesLayer layer, List<String> warnings, boolean readFailed) {
	}

	public static Result load(Path file) {
		LayerResult r = loadLayer(file);
		return toResult(r);
	}

	public static Result parse(String text) {
		return toResult(parseLayer(text));
	}

	public static Result fromProperties(Properties p) {
		PropertiesLayer layer = layerOf(p);
		return new Result(ConfigAssembler.assemble(layer.values(), layer.family()), layer.warnings(), false);
	}

	private static Result toResult(LayerResult r) {
		if (r.readFailed()) {
			return new Result(PdConfig.defaults(), r.warnings(), true);
		}
		PropertiesLayer layer = r.layer();
		return new Result(ConfigAssembler.assemble(layer.values(), layer.family()), layer.warnings(), false);
	}

	/** Reads the file as a layer (for the layered configuration); a read failure carries no layer. */
	public static LayerResult loadLayer(Path file) {
		Properties props = new Properties();
		try (InputStream in = Files.newInputStream(file); Reader r = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
			props.load(r);
		} catch (IOException | IllegalArgumentException e) {
			// IllegalArgumentException: malformed \\uXXXX escape
			return new LayerResult(null, List.of("Could not read config " + file + ": " + e.getMessage()), true);
		}
		PropertiesLayer layer = layerOf(props);
		return new LayerResult(layer, layer.warnings(), false);
	}

	public static LayerResult parseLayer(String text) {
		Properties props = new Properties();
		try {
			props.load(new StringReader(text));
		} catch (IOException | IllegalArgumentException e) {
			return new LayerResult(null, List.of("Malformed config: " + e.getMessage()), true);
		}
		PropertiesLayer layer = layerOf(props);
		return new LayerResult(layer, layer.warnings(), false);
	}

	/**
	 * Applies the lenient rules to every registry entry, in the registry's (legacy) evaluation order so that warnings come out in
	 * the same order as they always did.
	 */
	public static PropertiesLayer layerOf(Properties p) {
		List<String> warnings = new ArrayList<>();
		Map<String, Object> values = new LinkedHashMap<>();
		Set<String> explicit = new HashSet<>();
		Map<String, Map<String, Double>> family = new LinkedHashMap<>();
		for (SettingEntry entry : SettingRegistry.entries()) {
			if (entry instanceof SettingDef d) {
				switch (d.type()) {
					case DOUBLE -> {
						Lenient.Num n = Lenient.num(p, d.key(), (Double) d.defaultValue(), d.min(), d.max(), warnings);
						values.put(d.key(), n.value());
						if (n.explicit()) {
							explicit.add(d.key());
						}
					}
					case INT -> {
						Lenient.Num n = Lenient.whole(p, d.key(), (Integer) d.defaultValue(), (int) d.min(), (int) d.max(), warnings);
						values.put(d.key(), (int) n.value());
						if (n.explicit()) {
							explicit.add(d.key());
						}
					}
					case BOOL -> {
						Lenient.Flag f = Lenient.bool(p, d.key(), (Boolean) d.defaultValue(), warnings);
						values.put(d.key(), f.value());
						if (f.explicit()) {
							explicit.add(d.key());
						}
					}
				}
			} else if (entry instanceof FamilyDef f) {
				family.put(f.profile(), overrides(p, f.prefix(), warnings));
			}
		}
		return new PropertiesLayer(values, explicit, family, warnings);
	}

	/**
	 * Per-damage-type overrides: {@code easy.damage-type.minecraft.fall=0.3}. The first dot after the
	 * prefix separates namespace from path (vanilla damage type paths contain no dots).
	 */
	private static Map<String, Double> overrides(Properties p, String prefix, List<String> warnings) {
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
			Lenient.Num n = Lenient.num(p, key, Double.NaN, 0, PdConfig.MAX_MULTIPLIER, warnings);
			if (!Double.isNaN(n.value())) {
				out.put(id, n.value());
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
