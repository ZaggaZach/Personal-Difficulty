package com.zaggazach.personaldifficulty.config;

import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * The lenient parsing rules of the hand-edited properties layer, exactly as the 1.3.0 loader implemented them: a missing or blank
 * value is the default; an unparseable or non-finite value falls back to the default with a warning; an out-of-range value is
 * clamped with a warning. Message texts are part of the behaviour (a differential test compares them with the frozen loader).
 */
final class Lenient {
	/** @param explicit true if the file supplied a usable value (possibly clamped); false if the default was used */
	record Num(double value, boolean explicit) {
	}

	record Flag(boolean value, boolean explicit) {
	}

	private Lenient() {
	}

	static Num num(Properties p, String key, double def, double min, double max, List<String> warnings) {
		String raw = p.getProperty(key);
		if (raw == null || raw.isBlank()) {
			return new Num(def, false);
		}
		double v;
		try {
			v = Double.parseDouble(raw.trim());
		} catch (NumberFormatException e) {
			warnings.add(key + "=" + raw + " is not a number; using default " + def);
			return new Num(def, false);
		}
		if (!Double.isFinite(v)) {
			warnings.add(key + "=" + raw + " is not finite; using default " + def);
			return new Num(def, false);
		}
		if (v < min || v > max) {
			double clamped = Math.max(min, Math.min(max, v));
			warnings.add(key + "=" + raw + " is outside [" + min + ", " + max + "]; clamped to " + clamped);
			return new Num(clamped, true);
		}
		return new Num(v, true);
	}

	/** Whole-number settings are parsed as numbers and rounded. */
	static Num whole(Properties p, String key, int def, int min, int max, List<String> warnings) {
		Num n = num(p, key, def, min, max, warnings);
		return new Num((int) Math.round(n.value()), n.explicit());
	}

	static Flag bool(Properties p, String key, boolean def, List<String> warnings) {
		String raw = p.getProperty(key);
		if (raw == null || raw.isBlank()) {
			return new Flag(def, false);
		}
		String t = raw.trim().toLowerCase(Locale.ROOT);
		if (t.equals("true")) {
			return new Flag(true, true);
		}
		if (t.equals("false")) {
			return new Flag(false, true);
		}
		warnings.add(key + "=" + raw + " is not true/false; using default " + def);
		return new Flag(def, false);
	}
}
