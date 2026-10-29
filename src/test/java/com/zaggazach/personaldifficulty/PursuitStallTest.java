package com.zaggazach.personaldifficulty;

import static org.junit.jupiter.api.Assertions.*;

import com.zaggazach.personaldifficulty.logic.PursuitStall;
import com.zaggazach.personaldifficulty.logic.PursuitStall.State;
import org.junit.jupiter.api.Test;

class PursuitStallTest {
	@Test
	void aCompleteRouteNeverStartsOrKeepsAWatch() {
		State s = PursuitStall.next(State.NONE, false, 50, 100);
		assertFalse(s.watching());
		s = PursuitStall.next(new State(10, 40), false, 40, 500);
		assertFalse(s.watching(), "a complete route ends the watch");
	}

	@Test
	void partialRoutesWithoutProgressGiveUpAfterExactlyFiveSeconds() {
		State s = PursuitStall.next(State.NONE, true, 50, 1000);
		assertTrue(s.watching());
		for (long t = 1010; t < 1100; t += 10) {
			s = PursuitStall.next(s, true, 50.5, t); // standing at the foot of the pillar, a little jitter
			assertFalse(PursuitStall.givesUp(s, t), "not yet at " + t);
		}
		s = PursuitStall.next(s, true, 49.5, 1100);
		assertTrue(PursuitStall.givesUp(s, 1100), "100 ticks without getting a whole block closer");
	}

	@Test
	void realProgressRestartsTheWatch() {
		State s = PursuitStall.next(State.NONE, true, 50, 1000);
		s = PursuitStall.next(s, true, 48.9, 1090); // 1.1 blocks closer
		assertEquals(1090, s.since());
		assertFalse(PursuitStall.givesUp(s, 1150));
		assertTrue(PursuitStall.givesUp(PursuitStall.next(s, true, 48.5, 1190), 1190));
	}

	@Test
	void movingAwayIsNotProgress_andGarbageDistanceEndsTheWatch() {
		State s = PursuitStall.next(State.NONE, true, 50, 1000);
		s = PursuitStall.next(s, true, 70, 1050);
		assertEquals(1000, s.since(), "a detour that gets farther away does not restart the watch");
		assertFalse(PursuitStall.next(s, true, Double.NaN, 1060).watching());
	}

	@Test
	void gameTimeZeroStillStartsAWatch_andTheGiveUpExpires() {
		assertTrue(PursuitStall.next(State.NONE, true, 50, 0).watching());
		assertTrue(PursuitStall.stillGivenUp(1600, 1599));
		assertFalse(PursuitStall.stillGivenUp(1600, 1600));
	}
}
