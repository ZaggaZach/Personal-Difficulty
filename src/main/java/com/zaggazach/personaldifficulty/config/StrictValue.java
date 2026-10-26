package com.zaggazach.personaldifficulty.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Strict value parsing for {@code admin set} and for the overrides file. Unlike the lenient properties layer (which clamps and
 * falls back), anything that is not exactly a valid, in-range value of the setting's type is refused, with the allowed range in
 * the message. Doubles are finite; whole-number settings take only whole numbers; booleans are exactly {@code true}/{@code false}.
 */
public final class StrictValue {
	private static final Pattern DECIMAL = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");
	private static final Pattern WHOLE = Pattern.compile("[+-]?\\d+");

	private StrictValue() {
	}

	public sealed interface Parsed permits Ok, Error {
	}

	/** @param value Double, Integer or Boolean matching the setting's type */
	public record Ok(Object value) implements Parsed {
	}

	public record Error(String message) implements Parsed {
	}

	/** Parses the text typed after {@code admin set <key>}. */
	public static Parsed parse(SettingDef d, String text) {
		String t = text.trim();
		return switch (d.type()) {
			case BOOL -> {
				String l = t.toLowerCase(Locale.ROOT);
				yield l.equals("true") ? new Ok(Boolean.TRUE) : l.equals("false") ? new Ok(Boolean.FALSE)
					: new Error("'" + text + "' is not a valid value for " + d.key() + ": it must be " + d.rangeText());
			}
			case INT -> {
				if (!WHOLE.matcher(t).matches()) {
					yield new Error("'" + text + "' is not a whole number: " + d.key() + " must be " + d.rangeText());
				}
				long v;
				try {
					v = Long.parseLong(t.startsWith("+") ? t.substring(1) : t);
				} catch (NumberFormatException e) {
					yield new Error("'" + text + "' is out of range: " + d.key() + " must be " + d.rangeText());
				}
				yield v < d.min() || v > d.max()
					? new Error(text + " is outside the allowed range: " + d.key() + " must be " + d.rangeText())
					: new Ok((int) v);
			}
			case DOUBLE -> parseDouble(d.key(), d.rangeText(), t, text, d.min(), d.max());
		};
	}

	/** Parses a per-damage-type multiplier typed after {@code admin set <mode>.damage-type.<id>}. */
	public static Parsed parseMultiplier(String key, String text) {
		return parseDouble(key, "a number from 0.0 to " + PdConfig.MAX_MULTIPLIER, text.trim(), text, 0, PdConfig.MAX_MULTIPLIER);
	}

	private static Parsed parseDouble(String key, String range, String t, String original, double min, double max) {
		if (!DECIMAL.matcher(t).matches()) {
			return new Error("'" + original + "' is not a valid number: " + key + " must be " + range);
		}
		double v;
		try {
			v = Double.parseDouble(t);
		} catch (NumberFormatException e) {
			return new Error("'" + original + "' is not a valid number: " + key + " must be " + range);
		}
		if (!Double.isFinite(v)) {
			return new Error("'" + original + "' is not a finite number: " + key + " must be " + range);
		}
		if (v < min || v > max) {
			return new Error(original + " is outside the allowed range: " + key + " must be " + range);
		}
		return new Ok(v == 0.0 ? 0.0 : v); // -0 is stored as 0
	}

	/** Validates a value read from the overrides file: it must already be a JSON value of exactly the right type. */
	public static Parsed fromJson(SettingDef d, @Nullable JsonElement e) {
		if (e == null || !e.isJsonPrimitive()) {
			return new Error("not a " + typeName(d) + " (" + e + ")");
		}
		JsonPrimitive p = e.getAsJsonPrimitive();
		return switch (d.type()) {
			case BOOL -> p.isBoolean() ? new Ok(p.getAsBoolean()) : new Error("not true or false (" + e + ")");
			case INT -> {
				if (!p.isNumber()) {
					yield new Error("not a whole number (" + e + ")");
				}
				double v = p.getAsDouble();
				if (!Double.isFinite(v) || v != Math.rint(v)) {
					yield new Error("not a whole number (" + e + ")");
				}
				yield v < d.min() || v > d.max() ? new Error("outside the allowed range, " + d.rangeText() + " (" + e + ")") : new Ok((int) v);
			}
			case DOUBLE -> {
				if (!p.isNumber()) {
					yield new Error("not a number (" + e + ")");
				}
				yield checkedDouble(p.getAsDouble(), d.min(), d.max(), d.rangeText(), e);
			}
		};
	}

	/** Validates a per-damage-type multiplier read from the overrides file. */
	public static Parsed multiplierFromJson(@Nullable JsonElement e) {
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
			return new Error("not a number (" + e + ")");
		}
		return checkedDouble(e.getAsDouble(), 0, PdConfig.MAX_MULTIPLIER, "a number from 0.0 to " + PdConfig.MAX_MULTIPLIER, e);
	}

	private static Parsed checkedDouble(double v, double min, double max, String range, JsonElement e) {
		if (!Double.isFinite(v)) {
			return new Error("not a finite number (" + e + ")");
		}
		if (v < min || v > max) {
			return new Error("outside the allowed range, " + range + " (" + e + ")");
		}
		return new Ok(v == 0.0 ? 0.0 : v);
	}

	private static String typeName(SettingDef d) {
		return switch (d.type()) {
			case BOOL -> "true/false value";
			case INT -> "whole number";
			case DOUBLE -> "number";
		};
	}

	/** The JSON form of a validated value; doubles round-trip exactly. */
	public static JsonElement toJson(Object value) {
		if (value instanceof Boolean b) {
			return new JsonPrimitive(b);
		}
		if (value instanceof Integer i) {
			return new JsonPrimitive(i);
		}
		return new JsonPrimitive((Double) value);
	}

	/** Display text: doubles via {@link Double#toString}, whole numbers and booleans plainly. */
	public static String format(Object value) {
		return String.valueOf(value);
	}
}
