package com.zaggazach.personaldifficulty.logic;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Per-mob scratch state for Elite target priority, implemented on {@code Mob} by a mixin. It lives and dies with the mob
 * (nothing is kept in a registry, so there is nothing to clean up when a mob dies, despawns or unloads).
 */
public interface HuntingMob {
	/** True if this mob owns at least one goal that picks players as targets (computed once, then cached). */
	boolean personaldifficulty$huntsPlayers();

	@Nullable UUID personaldifficulty$lastElite();

	long personaldifficulty$lastEliteSeen();

	void personaldifficulty$rememberElite(UUID player, long gameTime);

	/** The target this class assigned to the mob (null if the mob's target came from vanilla goals). */
	@Nullable UUID personaldifficulty$ownedTarget();

	void personaldifficulty$setOwnedTarget(@Nullable UUID target);

	/** Ticks an Elite target this class is holding has been out of sight (counted at scan granularity). */
	int personaldifficulty$unseenTicks();

	void personaldifficulty$setUnseenTicks(int ticks);

	/** The player whose alert-board sighting this mob last searched (null if none), and that sighting's game tick. */
	@Nullable UUID personaldifficulty$searchedPlayer();

	long personaldifficulty$searchedTick();

	void personaldifficulty$markSearched(UUID player, long sightingTick);

	/** Pursuit-stall watch (see {@code PursuitStall}): the tick it started (0 = not watching) and the best distance since. */
	long personaldifficulty$stallSince();

	double personaldifficulty$stallBest();

	void personaldifficulty$setStall(long since, double best);

	/** The Elite player this mob gave up the Elite extensions for (null if none), and until which game tick. */
	@Nullable UUID personaldifficulty$gaveUpOn();

	long personaldifficulty$gaveUpUntil();

	void personaldifficulty$giveUp(@Nullable UUID player, long until);
}
