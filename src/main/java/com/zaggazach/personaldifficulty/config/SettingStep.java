package com.zaggazach.personaldifficulty.config;

import java.math.BigDecimal;

/**
 * What a menu's +/- (or on/off) button asks for: the next value of a setting, as text for {@link StrictValue#parse}, or a refusal
 * when the value is already at the bound in that direction. Pure arithmetic, no I/O: the change itself always goes through
 * {@code PdRuntime.setValue} (strict validation, write, then publish), exactly like {@code admin set}.
 *
 * <p>Decimal settings are stepped in exact decimal arithmetic, so 0.6 + 0.05 is 0.65 (not 0.6499999...), and a step down followed
 * by a step up returns the original value whenever neither step was clamped at a bound. A step that would cross a bound lands on the
 * bound; a step from the bound itself is refused, so "clicking past a bound" never changes anything.
 */
public final class SettingStep {
	/** A shift-click moves this many ordinary steps. */
	public static final int BIG_STEP = 10;

	private SettingStep() {
	}

	public sealed interface Outcome permits Next, AtBound {
	}

	/** @param text the value to pass to {@code setValue}, in the plain form a person would type */
	public record Next(String text) implements Outcome {
	}

	public record AtBound(String message) implements Outcome {
	}

	/**
	 * @param current the setting's effective value (Double, Integer or Boolean)
	 * @param direction +1 or -1; ignored for on/off settings, which simply toggle
	 * @param big true for a shift-click (ten steps)
	 */
	public static Outcome step(SettingDef d, Object current, int direction, boolean big) {
		if (d.type() == SettingDef.Type.BOOL) {
			return new Next(Boolean.TRUE.equals(current) ? "false" : "true");
		}
		if (direction != 1 && direction != -1) {
			throw new IllegalArgumentException("direction " + direction);
		}
		int times = big ? BIG_STEP : 1;
		if (d.type() == SettingDef.Type.INT) {
			long cur = ((Number) current).longValue();
			long min = (long) d.min();
			long max = (long) d.max();
			if (direction > 0 && cur >= max) {
				return atMax(d, String.valueOf(max));
			}
			if (direction < 0 && cur <= min) {
				return atMin(d, String.valueOf(min));
			}
			long next = Math.max(min, Math.min(max, cur + (long) direction * times * Math.max(1L, Math.round(d.step()))));
			return new Next(String.valueOf(next));
		}
		BigDecimal cur = decimal(((Number) current).doubleValue());
		BigDecimal min = decimal(d.min());
		BigDecimal max = decimal(d.max());
		if (direction > 0 && cur.compareTo(max) >= 0) {
			return atMax(d, plain(max));
		}
		if (direction < 0 && cur.compareTo(min) <= 0) {
			return atMin(d, plain(min));
		}
		BigDecimal delta = decimal(d.step()).multiply(BigDecimal.valueOf((long) direction * times));
		BigDecimal next = cur.add(delta);
		if (next.compareTo(max) > 0) {
			next = max;
		}
		if (next.compareTo(min) < 0) {
			next = min;
		}
		return new Next(plain(next));
	}

	/** The size of one step as shown on a button ("0.05", "1"), times ten for a shift-click. */
	public static String stepText(SettingDef d, boolean big) {
		if (d.type() == SettingDef.Type.INT) {
			return String.valueOf(Math.max(1L, Math.round(d.step())) * (big ? BIG_STEP : 1));
		}
		return plain(decimal(d.step()).multiply(BigDecimal.valueOf(big ? BIG_STEP : 1)));
	}

	/** Double.toString gives the shortest text that reads back as the same double, so 0.6 stays 0.6 rather than 0.59999... */
	private static BigDecimal decimal(double v) {
		return new BigDecimal(Double.toString(v));
	}

	private static String plain(BigDecimal v) {
		BigDecimal s = v.stripTrailingZeros();
		if (s.scale() < 0) {
			s = s.setScale(0);
		}
		return s.toPlainString();
	}

	private static AtBound atMax(SettingDef d, String max) {
		return new AtBound(d.key() + " is already at its maximum (" + max + "); nothing changed.");
	}

	private static AtBound atMin(SettingDef d, String min) {
		return new AtBound(d.key() + " is already at its minimum (" + min + "); nothing changed.");
	}
}
