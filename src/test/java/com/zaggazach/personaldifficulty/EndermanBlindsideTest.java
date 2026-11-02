package com.zaggazach.personaldifficulty;

import static org.junit.jupiter.api.Assertions.*;

import com.zaggazach.personaldifficulty.logic.EndermanBlindside;
import org.junit.jupiter.api.Test;

class EndermanBlindsideTest {
	@Test
	void everyCandidateIsBehindThePlayerForEveryLookDirection() {
		for (float yaw = -180; yaw <= 180; yaw += 15) {
			for (double jitter : new double[] {-1, 0, 1}) {
				double[][] c = EndermanBlindside.candidates(100, -50, yaw, 3, jitter);
				assertEquals(5, c.length);
				for (double[] p : c) {
					assertTrue(EndermanBlindside.isBehind(100, -50, yaw, p[0], p[1]), "yaw " + yaw + " jitter " + jitter + " spot " + p[0] + "," + p[1]);
				}
			}
		}
	}

	@Test
	void minecraftsYawConvention_lookingAlongPlusZPutsTheSpotAtMinusZ() {
		double[][] c = EndermanBlindside.candidates(0, 0, 0f, 3, 0);
		assertEquals(0, c[0][0], 1e-9);
		assertEquals(-3, c[0][1], 1e-9);
		double[][] west = EndermanBlindside.candidates(0, 0, 90f, 3, 0); // yaw 90 looks along -x
		assertEquals(3, west[0][0], 1e-9);
		assertEquals(0, west[0][1], 1e-9);
	}

	@Test
	void distanceAndJitterAreBounded() {
		for (double jitter : new double[] {-5, -1, 0, 1, 5}) {
			double[][] c = EndermanBlindside.candidates(0, 0, 0f, 3, jitter);
			double d = Math.hypot(c[0][0], c[0][1]);
			assertTrue(d >= 2 - 1e-9 && d <= 4 + 1e-9, "jitter is clamped to one block: " + d);
			for (double[] p : c) {
				assertTrue(Math.hypot(p[0], p[1]) >= 1 - 1e-9, "never on top of the player");
			}
		}
	}
}
