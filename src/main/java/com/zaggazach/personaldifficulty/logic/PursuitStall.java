package com.zaggazach.personaldifficulty.logic;

/**
 * Phase 5: when an Elite hunter cannot get to its Elite target (the player is up a pillar, across a ravine, behind a wall), its
 * route searches keep failing and keep costing server time (measured: about 0.05 ms per tick per such mob). After
 * {@link #GIVE_UP_TICKS} of partial routes without getting at least {@link #PROGRESS} blocks closer, the mob gives up the Elite
 * extensions for that player for {@link #RETRY_TICKS}: it falls back to vanilla range, path length and acquisition for them
 * (inside vanilla range it keeps hunting exactly as a vanilla mob would). A complete route, or real progress, resets the watch.
 *
 * <p>Pure: the state is two numbers kept on the mob ({@code since}, 0 = not watching, and the best distance so far).
 */
public final class PursuitStall {
	/** Five seconds of failing routes without progress. */
	public static final int GIVE_UP_TICKS = 100;
	/** Thirty seconds before the Elite extensions apply to that player again. */
	public static final int RETRY_TICKS = 600;
	/** Blocks of improvement that count as progress. */
	public static final double PROGRESS = 1.0;

	private PursuitStall() {
	}

	/** Watch state after one observation. */
	public record State(long since, double best) {
		public static final State NONE = new State(0, 0);

		public boolean watching() {
			return since != 0;
		}
	}

	/** What the mob's navigation shows at the moment of an observation. */
	public enum Route {
		/** A route that reaches the target. */
		COMPLETE,
		/** A route that ends short of the target. */
		PARTIAL,
		/**
		 * No current route (between re-plans, or the end of a partial route was just reached). Says nothing either way: found
		 * live, a stalled zombie had no route at about a third of its scan ticks, and treating that as "complete" reset the watch.
		 */
		NONE
	}

	/**
	 * The next state: not watching while the route is complete; unchanged while there is no route; a new watch, or a restarted one
	 * when the mob got {@link #PROGRESS} closer than its best, while the route is partial; otherwise unchanged.
	 */
	public static State next(State s, Route route, double distance, long now) {
		if (route == Route.COMPLETE || !Double.isFinite(distance)) {
			return State.NONE;
		}
		if (route == Route.NONE) {
			return s;
		}
		if (!s.watching() || distance < s.best() - PROGRESS) {
			return new State(Math.max(now, 1), distance);
		}
		return s;
	}

	/** True once the watch has run {@link #GIVE_UP_TICKS} without progress. */
	public static boolean givesUp(State s, long now) {
		return s.watching() && now - s.since() >= GIVE_UP_TICKS;
	}

	/** True while a give-up recorded until {@code until} still applies. */
	public static boolean stillGivenUp(long until, long now) {
		return now < until;
	}
}
