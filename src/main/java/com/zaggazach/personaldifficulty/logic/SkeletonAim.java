package com.zaggazach.personaldifficulty.logic;

/**
 * Phase 6 (plan 6.4): where an Elite skeleton aims. Pure arithmetic, no Minecraft types, so it is unit-tested directly.
 *
 * <p>Vanilla aims at the target's current position with a flat arc allowance ({@code dy + horizontalDistance * 0.2}) tuned for its
 * launch speed of 1.6. With a different launch speed that allowance no longer fits, and it never accounts for a moving target.
 * This solver uses vanilla's own arrow physics (each tick: move by the velocity, multiply the velocity by {@link #DRAG}, subtract
 * {@link #GRAVITY} from its vertical part) to find the low-arc launch direction that brings the arrow to the target's height at the
 * target's horizontal distance, and, with the target's velocity, to where the target will be when the arrow gets there (iterated:
 * flight time, predicted position, new flight time). The result is blended with vanilla's direction by {@code strength}.
 *
 * <p>Never promised: a hit. Vertical target motion is ignored (jumps are short and would make the aim jitter); a target that
 * changes direction after the arrow is released still dodges; the shot's random spread is applied afterwards, by vanilla.
 */
public final class SkeletonAim {
	/** Arrow velocity kept per tick in air (vanilla {@code AbstractArrow}). */
	public static final double DRAG = 0.99;
	/** Arrow gravity per tick (vanilla {@code AbstractArrow}). */
	public static final double GRAVITY = 0.05;
	/** Vanilla skeleton launch speed. */
	public static final double VANILLA_SPEED = 1.6;
	/** Predicted-position iterations (plan: 3 to 5). */
	static final int ITERATIONS = 4;
	/** A shot whose flight would take longer than this many ticks is treated as out of reach. */
	static final int MAX_FLIGHT_TICKS = 200;

	private SkeletonAim() {
	}

	/** One simulated flight: the height reached at the horizontal distance and the (fractional) tick it got there. */
	record Flight(double height, double ticks, boolean reached) {
	}

	/**
	 * Simulates an arrow launched at {@code pitch} radians (positive is up) with {@code speed}, until it has covered
	 * {@code distance} horizontally; returns its height relative to the launch point and the time taken (interpolated within
	 * the last tick), or {@code reached == false} if it falls short within {@link #MAX_FLIGHT_TICKS}.
	 */
	static Flight fly(double pitch, double speed, double distance) {
		double vh = speed * Math.cos(pitch);
		double vy = speed * Math.sin(pitch);
		double x = 0;
		double y = 0;
		for (int t = 1; t <= MAX_FLIGHT_TICKS; t++) {
			double nx = x + vh;
			double ny = y + vy;
			if (nx >= distance) {
				double f = vh <= 0 ? 0 : (distance - x) / vh;
				return new Flight(y + (ny - y) * f, t - 1 + f, true);
			}
			x = nx;
			y = ny;
			vh *= DRAG;
			vy = vy * DRAG - GRAVITY;
			if (vh < 1e-6) {
				break;
			}
		}
		return new Flight(y, MAX_FLIGHT_TICKS, false);
	}

	/**
	 * The low-arc pitch that reaches height {@code dy} at horizontal distance {@code distance} with launch {@code speed}, or NaN
	 * if no pitch up to 45 degrees reaches it (out of range). Bisection: within that interval the height at the distance rises
	 * with the pitch.
	 */
	static double pitchFor(double distance, double dy, double speed) {
		double lo = -Math.PI / 2 + 0.01;
		double hi = Math.PI / 4;
		Flight top = fly(hi, speed, distance);
		if (!top.reached() || top.height() < dy) {
			return Double.NaN;
		}
		for (int i = 0; i < 40; i++) {
			double mid = (lo + hi) / 2;
			Flight f = fly(mid, speed, distance);
			if (f.reached() && f.height() >= dy) {
				hi = mid;
			} else {
				lo = mid;
			}
		}
		return hi;
	}

	/**
	 * The launch direction (not normalised; vanilla's {@code shoot} normalises it) for an arrow starting at {@code (sx, sy, sz)}
	 * aimed at a point {@code (tx, ty, tz)} on a target moving {@code (vx, vz)} blocks per tick horizontally.
	 *
	 * @param vanilla vanilla's own direction for this shot ({@code dx, dy + horizontal * 0.2, dz}); returned unchanged when
	 *     {@code strength} is 0 or no solution exists
	 * @param strength 0 = vanilla, 1 = the solved direction, in between = blended
	 */
	public static double[] direction(double sx, double sy, double sz, double tx, double ty, double tz, double vx, double vz, double speed,
		double strength, double[] vanilla) {
		if (!(strength > 0) || !(speed > 0) || !finite(sx, sy, sz, tx, ty, tz, vx, vz)) {
			return vanilla;
		}
		double px = tx;
		double pz = tz;
		double pitch = Double.NaN;
		for (int i = 0; i < ITERATIONS; i++) {
			double hx = px - sx;
			double hz = pz - sz;
			double distance = Math.sqrt(hx * hx + hz * hz);
			if (distance < 0.5) {
				return vanilla; // point blank: vanilla's aim is as good as any
			}
			pitch = pitchFor(distance, ty - sy, speed);
			if (Double.isNaN(pitch)) {
				return vanilla;
			}
			double ticks = fly(pitch, speed, distance).ticks();
			px = tx + vx * ticks;
			pz = tz + vz * ticks;
		}
		double hx = px - sx;
		double hz = pz - sz;
		double h = Math.sqrt(hx * hx + hz * hz);
		double[] solved = {hx / h * Math.cos(pitch), Math.sin(pitch), hz / h * Math.cos(pitch)};
		if (strength >= 1) {
			return solved;
		}
		double[] v = normalised(vanilla);
		if (v == null) {
			return solved;
		}
		return new double[] {
			v[0] * (1 - strength) + solved[0] * strength,
			v[1] * (1 - strength) + solved[1] * strength,
			v[2] * (1 - strength) + solved[2] * strength
		};
	}

	private static double @org.jspecify.annotations.Nullable [] normalised(double[] d) {
		double len = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
		return len > 0 && Double.isFinite(len) ? new double[] {d[0] / len, d[1] / len, d[2] / len} : null;
	}

	private static boolean finite(double... values) {
		for (double v : values) {
			if (!Double.isFinite(v)) {
				return false;
			}
		}
		return true;
	}
}
