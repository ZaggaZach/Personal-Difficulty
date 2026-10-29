package com.zaggazach.personaldifficulty;

import static org.junit.jupiter.api.Assertions.*;

import com.zaggazach.personaldifficulty.logic.AlertBoard;
import com.zaggazach.personaldifficulty.logic.AlertBoard.Known;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Phase 5: every bound of the alert board (radius edge, hop limit, memory expiry, caps, staleness, eligibility, once-only search). */
class AlertBoardTest {
	private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
	private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
	private static final Set<UUID> BOTH = Set.of(A, B);
	private static final long TTL = 1200;

	private static Known learn(AlertBoard b, int mob, double x, long now, int hops) {
		return b.learn(mob, x, 64, 0, now, 64, hops, TTL, BOTH, null, 0);
	}

	@Test
	void theMobThatSawThePlayerKnowsTheRememberedPointEvenWithZeroHops() {
		AlertBoard b = new AlertBoard();
		b.sighted(A, 10, 64, 0, 1, 0, 64, 0, 100);
		Known k = b.learn(1, 500, 64, 0, 120, 64, 0, TTL, BOTH, null, 0);
		assertNotNull(k, "it saw the player, so it knows wherever it has walked to");
		assertEquals(0, k.hops());
		assertEquals(10, k.x());
		assertNull(learn(b, 2, 5, 120, 0), "with zero hops nobody else learns");
	}

	@Test
	void theRadiusEdgeIsInclusive() {
		AlertBoard b = new AlertBoard();
		b.sighted(A, 0, 64, 0, 1, 0, 64, 0, 100);
		assertNotNull(learn(b, 2, 64, 120, 2), "exactly 64 blocks from the node");
		assertNull(learn(b, 3, 128.0001, 120, 1), "beyond the radius of every node (the hop-1 node at 64 cannot pass it on with 1 hop)");
	}

	@Test
	void aSightingIsPassedOnAtMostHopsTimes() {
		AlertBoard b = new AlertBoard();
		b.sighted(A, 0, 64, 0, 1, 0, 64, 0, 100);
		Known one = learn(b, 2, 60, 110, 2);
		Known two = learn(b, 3, 120, 111, 2);
		Known three = learn(b, 4, 180, 112, 2);
		assertEquals(1, one.hops());
		assertEquals(2, two.hops());
		assertNull(three, "a third hop is refused with hops = 2");
		assertEquals(0, two.x(), "every hop learns the same remembered point, not a newer one");
	}

	@Test
	void sightingsExpireAfterTheMemory() {
		AlertBoard b = new AlertBoard();
		b.sighted(A, 0, 64, 0, 1, 0, 64, 0, 100);
		assertNotNull(learn(b, 2, 10, 100 + TTL, 2), "still within the memory");
		assertNull(learn(b, 3, 10, 101 + TTL, 2), "one tick past it");
		assertEquals(0, b.size(), "and the entry is gone");
	}

	@Test
	void aNewerSightingReplacesThePointAndKeepsTheChain_anOlderOneNeverMovesItBack() {
		AlertBoard b = new AlertBoard();
		b.sighted(A, 0, 64, 0, 1, 0, 64, 0, 100);
		learn(b, 2, 40, 101, 2);
		b.sighted(A, 30, 64, 0, 5, 300, 64, 0, 200);
		Known k = learn(b, 2, 40, 201, 2);
		assertEquals(30, k.x(), "the newer point");
		assertEquals(1, k.hops(), "mob 2 still knows through the old chain");
		b.sighted(A, 99, 64, 0, 6, 0, 64, 0, 150);
		assertEquals(30, learn(b, 2, 40, 202, 2).x(), "an older report never moves the point back");
	}

	@Test
	void onlyEligiblePlayersAreLearned_andTheFreshestWins() {
		AlertBoard b = new AlertBoard();
		b.sighted(A, 1, 64, 0, 1, 0, 64, 0, 100);
		b.sighted(B, 2, 64, 0, 1, 0, 64, 0, 150);
		assertEquals(B, learn(b, 2, 10, 160, 2).player(), "the freshest sighting");
		assertEquals(A, b.learn(3, 10, 64, 0, 160, 64, 2, TTL, Set.of(A), null, 0).player(), "B is not eligible (left Elite, died, left the level)");
		assertNull(b.learn(4, 10, 64, 0, 160, 64, 2, TTL, Set.of(), null, 0));
	}

	@Test
	void aSearchedSightingIsNotSearchedAgain_untilThereIsANewerOne() {
		AlertBoard b = new AlertBoard();
		b.sighted(A, 1, 64, 0, 1, 0, 64, 0, 100);
		assertNull(b.learn(2, 10, 64, 0, 110, 64, 2, TTL, BOTH, A, 100), "already searched");
		b.sighted(A, 5, 64, 0, 1, 0, 64, 0, 130);
		Known k = b.learn(2, 10, 64, 0, 140, 64, 2, TTL, BOTH, A, 100);
		assertNotNull(k, "a newer sighting is searched");
		assertEquals(5, k.x());
	}

	@Test
	void theBoardAndEachSightingAreBounded() {
		AlertBoard b = new AlertBoard();
		for (int i = 0; i < AlertBoard.MAX_SIGHTINGS + 20; i++) {
			b.sighted(new UUID(0, i + 100), 0, 64, 0, 1, 0, 64, 0, i);
		}
		assertEquals(AlertBoard.MAX_SIGHTINGS, b.size());
		b.sighted(A, 0, 64, 0, 1, 0, 64, 0, 1000);
		for (int mob = 2; mob < AlertBoard.MAX_NODES + 50; mob++) {
			learn(b, mob, 1, 1001, 4);
		}
		assertEquals(AlertBoard.MAX_NODES, b.nodeCount(A));
		assertNotNull(b.learn(1, 900, 64, 0, 1002, 64, 0, TTL, BOTH, null, 0), "the direct sighting survives the node cap");
	}
}
