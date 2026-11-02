package com.zaggazach.personaldifficulty;

import static org.junit.jupiter.api.Assertions.*;

import com.zaggazach.personaldifficulty.logic.SkeletonAim;
import org.junit.jupiter.api.Test;

class SkeletonAimTest {
	/** Flies an arrow with vanilla's per-tick physics from the origin along {@code dir} and returns where it is closest to the moving target. */
	private static double miss(double[] dir, double speed, double tx, double ty, double tz, double vx, double vz) {
		double len = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
		double ax = dir[0] / len * speed;
		double ay = dir[1] / len * speed;
		double az = dir[2] / len * speed;
		double x = 0;
		double y = 0;
		double z = 0;
		double best = Double.MAX_VALUE;
		for (int t = 0; t < 200; t++) {
			// sample along the tick's segment, against the target's position at that moment
			for (int s = 0; s <= 100; s++) {
				double f = s / 100.0;
				double time = t + f;
				double dx = x + ax * f - (tx + vx * time);
				double dy = y + ay * f - ty;
				double dz = z + az * f - (tz + vz * time);
				best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
			}
			x += ax;
			y += ay;
			z += az;
			ax *= SkeletonAim.DRAG;
			ay = ay * SkeletonAim.DRAG - SkeletonAim.GRAVITY;
			az *= SkeletonAim.DRAG;
		}
		return best;
	}

	private static double[] vanilla(double tx, double ty, double tz) {
		return new double[] {tx, ty + Math.sqrt(tx * tx + tz * tz) * 0.2, tz};
	}

	@Test
	void aStationaryTargetIsHitAtEveryDistanceAndSpeed() {
		for (double speed : new double[] {1.6, 2.4, 3.2, 4.8}) {
			for (double d : new double[] {4, 8, 15, 24, 32}) {
				for (double h : new double[] {-3, 0, 3}) {
					double[] dir = SkeletonAim.direction(0, 0, 0, d, h, 0, 0, 0, speed, 1.0, vanilla(d, h, 0));
					assertTrue(miss(dir, speed, d, h, 0, 0, 0) < 0.15, "speed " + speed + " distance " + d + " height " + h);
				}
			}
		}
	}

	@Test
	void vanillasArcAllowanceDoesNotFitAFasterArrow_theSolverDoes() {
		double d = 24;
		double vanillaMiss = miss(vanilla(d, 0, 0), 2.4, d, 0, 0, 0, 0);
		double solvedMiss = miss(SkeletonAim.direction(0, 0, 0, d, 0, 0, 0, 0, 2.4, 1.0, vanilla(d, 0, 0)), 2.4, d, 0, 0, 0, 0);
		assertTrue(vanillaMiss > 1.0, "vanilla's 0.2 allowance overshoots at 1.5x speed: " + vanillaMiss);
		assertTrue(solvedMiss < 0.15, "solved: " + solvedMiss);
	}

	@Test
	void aTargetMovingSidewaysAtSprintSpeedIsLed() {
		double vz = 0.28; // about sprinting, blocks per tick
		for (double d : new double[] {10, 20, 24}) {
			double[] led = SkeletonAim.direction(0, 0, 0, d, 0, 0, 0, vz, 2.4, 1.0, vanilla(d, 0, 0));
			double[] still = SkeletonAim.direction(0, 0, 0, d, 0, 0, 0, 0, 2.4, 1.0, vanilla(d, 0, 0));
			assertTrue(miss(led, 2.4, d, 0, 0, 0, vz) < 0.3, "led shot meets the moving target at " + d + ": " + miss(led, 2.4, d, 0, 0, 0, vz));
			assertTrue(miss(still, 2.4, d, 0, 0, 0, vz) > 0.8, "an unled shot misses it at " + d + ": " + miss(still, 2.4, d, 0, 0, 0, vz));
			assertTrue(led[2] > 0, "aimed ahead, on the side the target is moving to");
		}
	}

	@Test
	void strengthZeroIsVanilla_halfIsBetween() {
		double[] v = vanilla(20, 0, 0);
		assertArrayEquals(v, SkeletonAim.direction(0, 0, 0, 20, 0, 0, 0, 0.3, 2.4, 0.0, v));
		double[] full = SkeletonAim.direction(0, 0, 0, 20, 0, 0, 0, 0.3, 2.4, 1.0, v);
		double[] half = SkeletonAim.direction(0, 0, 0, 20, 0, 0, 0, 0.3, 2.4, 0.5, v);
		assertTrue(half[2] > 0 && half[2] < full[2], "half-strength leads less than full");
	}

	@Test
	void outOfReachPointBlankAndGarbageFallBackToVanilla() {
		double[] v = vanilla(500, 0, 0);
		assertArrayEquals(v, SkeletonAim.direction(0, 0, 0, 500, 0, 0, 0, 0, 1.6, 1.0, v), "beyond what any arc reaches");
		double[] close = vanilla(0.2, 0, 0.1);
		assertArrayEquals(close, SkeletonAim.direction(0, 0, 0, 0.2, 0, 0.1, 0, 0, 2.4, 1.0, close));
		assertArrayEquals(v, SkeletonAim.direction(0, 0, 0, Double.NaN, 0, 0, 0, 0, 2.4, 1.0, v));
		assertArrayEquals(v, SkeletonAim.direction(0, 0, 0, 20, 0, 0, Double.POSITIVE_INFINITY, 0, 2.4, 1.0, v));
	}
}
