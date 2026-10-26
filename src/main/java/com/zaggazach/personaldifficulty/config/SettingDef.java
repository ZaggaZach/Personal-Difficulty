package com.zaggazach.personaldifficulty.config;

import java.util.function.Function;

/**
 * One scalar setting: its properties/overrides key, type, bounds, default, GUI step, display text and apply timing.
 *
 * @param javaPath path of the corresponding leaf in {@link PdConfig} (for example {@code elite.damageMultiplier}); a test keeps
 *     the set of paths equal to the set of {@code PdConfig} leaves
 * @param min inclusive lower bound (for INT settings a whole number)
 * @param step increment a GUI button uses; 0 for booleans
 * @param read reads the effective value out of a {@link PdConfig} (Double, Integer or Boolean)
 */
public record SettingDef(
	String key,
	Type type,
	double min,
	double max,
	Object defaultValue,
	double step,
	String display,
	Timing timing,
	int order,
	String javaPath,
	Function<PdConfig, Object> read
) implements SettingEntry {
	public enum Type {
		DOUBLE,
		INT,
		BOOL
	}

	/** When a changed value takes effect on a running server. */
	public enum Timing {
		/** At the next event that reads it (next hit, next tick, next use). */
		LIVE,
		/** Picked up by the next hunting scan of each mob (within the scan interval). */
		NEXT_SCAN,
		/** Needs a restart. */
		RESTART
	}

	/** Human readable allowed range, for error messages and displays. */
	public String rangeText() {
		return switch (type) {
			case BOOL -> "true or false";
			case INT -> "a whole number from " + (long) min + " to " + (long) max;
			case DOUBLE -> "a number from " + min + " to " + max;
		};
	}
}
