package com.zaggazach.personaldifficulty;

import com.zaggazach.personaldifficulty.config.PdConfig;
import com.zaggazach.personaldifficulty.logic.AlertBoard;
import com.zaggazach.personaldifficulty.logic.HuntingMob;
import java.util.EnumSet;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Witch;
import org.jspecify.annotations.Nullable;

/**
 * Phase 5: an idle hostile mob walks to the point where an Elite player was last SEEN (by itself, or by a mob that told it through
 * the {@link AlertBoard}), then gives up. It never learns where the player is now, and it moves only with its own pathfinder
 * (around obstacles, never through them). If it sees the player on the way, its ordinary targeting goals take over and this goal
 * stops, because it only runs while the mob has no target.
 *
 * <p>Long routes: the pathfinder's search budget is limited, so a far point may first get a partial route. When the mob reaches the
 * end of it, it plans again from where it stands, which moves it on towards the point in hops (bounded by {@link #SEARCH_TICKS}
 * and {@link #MAX_REPLANS}).
 *
 * <p>Cost: {@link #canUse} does nothing but a modulo test except on the mob's own scan tick (the Elite hunting scan interval,
 * staggered by entity id), and then a board lookup that is only distance checks.
 */
public final class PdLastKnownGoal extends Goal {
	/** Give up after this long (30 seconds) even if the point was not reached. */
	static final int SEARCH_TICKS = 600;
	/** Close enough to the remembered point to call the search done. */
	static final double ARRIVED = 2.5;
	/** Plans per search, including the first; a mob that cannot get anywhere stops early. */
	static final int MAX_REPLANS = 8;
	/** Walking speed modifier: the mob's ordinary pace, as vanilla uses for chasing. */
	static final double SPEED = 1.0;
	/**
	 * After a search that did NOT reach the point (no route, out of plans, timed out), the mob ignores further sightings of that
	 * player for this long (10 s). Without it, a mob near a player it cannot reach (on top of a pillar) started a new, failing
	 * search after every fresh sighting by the mobs that can see them - measured: about +1.9 ms per tick for 20 such zombies.
	 */
	static final int RETRY_AFTER_FAILED_TICKS = 200;

	/** Diagnostics: route plans made by this goal since the server started (read by the performance measurement). */
	private static long plansMade;

	public static long plansMade() {
		return plansMade;
	}

	private final PathfinderMob mob;
	private AlertBoard.@Nullable Known searching;
	private long deadline;
	private int plans;
	private int failedPlans;

	public PdLastKnownGoal(PathfinderMob mob) {
		this.mob = mob;
		this.setFlags(EnumSet.of(Goal.Flag.MOVE));
	}

	/**
	 * Above the mob's idle wandering and below everything that matters more (fleeing the sun, attacking: attacks need a target,
	 * and this goal never runs with one). A witch strolls at priority 2, so its search sits just above that.
	 */
	public static int priorityFor(PathfinderMob mob) {
		return mob instanceof Witch ? 1 : 4;
	}

	@Override
	public boolean canUse() {
		PdRuntime rt = PdRuntime.get();
		if (rt == null || mob.getTarget() != null) {
			return false;
		}
		PdConfig cfg = rt.config();
		if (!cfg.eliteAlert().enabled() || (mob.tickCount + mob.getId()) % cfg.eliteHunt().scanIntervalTicks() != 0) {
			return false;
		}
		AlertBoard.Known k = PdHunt.lastKnown(mob);
		if (k == null) {
			return false;
		}
		if (mob.distanceToSqr(k.x(), k.y(), k.z()) <= ARRIVED * ARRIVED) {
			((HuntingMob) mob).personaldifficulty$markSearched(k.player(), k.tick());
			return false;
		}
		this.searching = k;
		return true;
	}

	@Override
	public void start() {
		this.deadline = mob.level().getGameTime() + SEARCH_TICKS;
		this.plans = 0;
		this.failedPlans = 0;
		plan();
	}

	private void plan() {
		AlertBoard.Known k = this.searching;
		if (k == null) {
			return;
		}
		this.plans++;
		plansMade++;
		if (!mob.getNavigation().moveTo(k.x(), k.y(), k.z(), SPEED)) {
			this.failedPlans++;
		}
	}

	@Override
	public boolean canContinueToUse() {
		AlertBoard.Known k = this.searching;
		PdRuntime rt = PdRuntime.get();
		return k != null && rt != null && rt.config().eliteAlert().enabled() && mob.getTarget() == null
			&& mob.level().getGameTime() < this.deadline && this.plans <= MAX_REPLANS && this.failedPlans < 3
			&& mob.distanceToSqr(k.x(), k.y(), k.z()) > ARRIVED * ARRIVED && PdHunt.stillEligible(mob, k.player());
	}

	@Override
	public void tick() {
		// the end of a partial route: plan again from here, towards the same remembered point
		if (mob.getNavigation().isDone()) {
			plan();
		}
	}

	@Override
	public void stop() {
		AlertBoard.Known k = this.searching;
		if (k != null) {
			boolean arrived = mob.distanceToSqr(k.x(), k.y(), k.z()) <= ARRIVED * ARRIVED;
			// a failed search also covers every sighting of that player for the next RETRY_AFTER_FAILED_TICKS
			long covered = arrived ? k.tick() : Math.max(k.tick(), mob.level().getGameTime() + RETRY_AFTER_FAILED_TICKS);
			((HuntingMob) mob).personaldifficulty$markSearched(k.player(), covered);
		}
		this.searching = null;
		mob.getNavigation().stop();
	}

	/** Test and diagnostics access: the point being searched, or null. */
	public AlertBoard.@Nullable Known searching() {
		return searching;
	}
}
