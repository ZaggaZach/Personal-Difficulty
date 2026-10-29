package com.zaggazach.personaldifficulty;

import com.zaggazach.personaldifficulty.config.PdConfig;
import com.zaggazach.personaldifficulty.logic.AlertBoard;
import com.zaggazach.personaldifficulty.logic.EliteSurvival;
import com.zaggazach.personaldifficulty.logic.HuntingMob;
import com.zaggazach.personaldifficulty.logic.PursuitStall;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import com.zaggazach.personaldifficulty.mixin.PathNavigationAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import org.jspecify.annotations.Nullable;

/**
 * Elite target priority (1.3.0): hostile mobs prefer an eligible Elite player over any other player.
 *
 * <p>Vanilla only re-runs target selection when a mob has no target (and hurt-by retaliation can swap it for whoever last hit
 * it), so a mob that is busy with a Normal player would never notice an Elite one. Two cooperating pieces fix that:
 * <ol>
 * <li>{@link #huntTick}: every {@code scan-interval-ticks} (staggered by entity id) each participating mob re-evaluates; if it
 *     is not already hunting an Elite player it takes the nearest eligible one.</li>
 * <li>The nearest-player goal (see {@code NearestAttackableTargetGoalMixin}) prefers Elite candidates when it does run, and
 *     refuses to start while the mob already holds a valid Elite target, so the goal cannot override the priority.</li>
 * </ol>
 * <b>Stability:</b> a mob that already hunts a valid Elite player is never re-targeted, so several Elite players cannot make
 * a mob oscillate; it switches only when its Elite target is gone (dead, Peaceful, out of range, disconnected) and then picks
 * the nearest remaining candidate (first in player-list order on exact ties).
 * <b>No omniscience:</b> candidates must pass the mob's own {@link TargetingConditions} (line of sight, invisibility, head
 * gear, {@code canAttack}) within a finite radius, with one narrow exception: for a few seconds after a mob last SAW its Elite
 * target it may re-acquire that same player without line of sight, but only if its pathfinder finds a complete route to them
 * within range (it walks around obstacles, never through them).
 */
public final class PdHunt {
	private static final TargetingConditions SIGHT = TargetingConditions.forCombat();
	private static final TargetingConditions BLIND = TargetingConditions.forCombat().ignoreLineOfSight();
	private static final WeakHashMap<ServerLevel, Cached> CACHE = new WeakHashMap<>();
	/** One alert board per level (Phase 5); it holds only UUIDs, positions and ticks, and dies with the level. */
	private static final WeakHashMap<ServerLevel, AlertBoard> BOARDS = new WeakHashMap<>();

	/** Valid for one game tick, one player count and one generation of mode selections (a switch invalidates it at once). */
	private record Cached(long tick, int players, int generation, List<ServerPlayer> elites) {
	}

	private PdHunt() {
	}

	/** The Elite players of a level, rebuilt at most once per game tick (and only when some mob asks). */
	static List<ServerPlayer> elites(ServerLevel level, PdRuntime rt) {
		long now = level.getGameTime();
		int generation = rt.settings().changeCount();
		Cached cached = CACHE.get(level);
		if (cached != null && cached.tick == now && cached.generation == generation && cached.players == level.players().size()) {
			return cached.elites;
		}
		List<ServerPlayer> found = List.of();
		for (ServerPlayer player : level.players()) {
			if (player.isAlive() && !player.isSpectator() && rt.modeOf(player.getUUID()) == PersonalDifficultyMode.ELITE) {
				if (found.isEmpty()) {
					found = new ArrayList<>(2);
				}
				found.add(player);
			}
		}
		CACHE.put(level, new Cached(now, level.players().size(), generation, found));
		return found;
	}

	/** Hostile mobs with player-hunting goals take part; neutral or provoked-only families never do. */
	static boolean participates(Mob mob) {
		if (!(mob instanceof Monster) || mob instanceof Enderman || mob instanceof ZombifiedPiglin) {
			return false;
		}
		return ((HuntingMob) mob).personaldifficulty$huntsPlayers();
	}

	static boolean isLiveEliteTarget(PdRuntime rt, @Nullable LivingEntity target) {
		return target instanceof ServerPlayer p && p.isAlive() && rt.modeOf(p.getUUID()) == PersonalDifficultyMode.ELITE;
	}

	/** Called by the nearest-player goal: true if it must not start because the mob already holds an Elite target. */
	public static boolean holdsEliteTarget(Mob mob) {
		PdRuntime rt = PdRuntime.get();
		return rt != null && rt.config().eliteHunt().priorityEnabled() && participates(mob) && isLiveEliteTarget(rt, mob.getTarget());
	}

	public static boolean priorityEnabled() {
		PdRuntime rt = PdRuntime.get();
		return rt != null && rt.config().eliteHunt().priorityEnabled();
	}

	/** Mob.serverAiStep hook. Cheap exit first: throttle, then family, then "is anyone Elite at all". */
	public static void huntTick(Mob mob) {
		PdRuntime rt = PdRuntime.get();
		if (rt == null || !(mob.level() instanceof ServerLevel level) || !mob.isAlive()) {
			return;
		}
		PdConfig.EliteHunt cfg = rt.config().eliteHunt();
		if (!cfg.priorityEnabled() || (mob.tickCount + mob.getId()) % cfg.scanIntervalTicks() != 0 || !participates(mob)) {
			return;
		}
		LivingEntity current = mob.getTarget();
		HuntingMob memory = (HuntingMob) mob;
		// A target that this class assigned has no vanilla goal watching over it, so the rules such a goal would enforce are
		// enforced here: gone when dead, out of pursuit range, or unseen for longer than the (Elite-extended) memory.
		if (current != null && current.getUUID().equals(memory.personaldifficulty$ownedTarget())) {
			if (!stillValid(rt, mob, level, current, memory, cfg)) {
				mob.setTarget(null);
				memory.personaldifficulty$setOwnedTarget(null);
				current = null;
			}
		} else if (isLiveEliteTarget(rt, current)) {
			// An Elite target that someone else set (a vanilla goal that has since stopped, another mod) has no goal enforcing
			// the keep-rules either; adopt it so that it can never be held forever.
			memory.personaldifficulty$setOwnedTarget(current.getUUID());
			memory.personaldifficulty$setUnseenTicks(0);
		} else if (memory.personaldifficulty$ownedTarget() != null) {
			memory.personaldifficulty$setOwnedTarget(null);
		}
		if (current != null && (!current.isAlive() || current.isRemoved())) {
			mob.setTarget(null);
			current = null;
		}
		List<ServerPlayer> elites = elites(level, rt);
		if (elites.isEmpty()) {
			return;
		}
		if (isLiveEliteTarget(rt, current)) {
			ServerPlayer hunted = (ServerPlayer) current;
			if (mob.getSensing().hasLineOfSight(hunted)) {
				saw(rt, level, mob, memory, hunted);
			}
			watchStall(mob, memory, hunted, level.getGameTime());
			return; // stable: never swap one Elite target for another
		}
		// A daytime spider is neutral; forcing a target on it would make it drop the target again every few ticks.
		if (mob instanceof Spider && mob.getLightLevelDependentMagicValue() >= 0.5F) {
			return;
		}
		elites = withoutGivenUp(mob, elites, level.getGameTime());
		if (elites.isEmpty()) {
			return;
		}
		double range = EliteSurvival.cappedRange(mob.getAttributeValue(Attributes.FOLLOW_RANGE), rt.config().eliteAi().acquisitionRangeMultiplier(), rt.config().eliteAi());
		ServerPlayer best = nearestVisible(level, mob, elites, range, SIGHT);
		if (best == null) {
			best = rememberedAndReachable(level, mob, memory, elites, range, cfg);
		}
		if (best != null && best != current) {
			mob.setTarget(best);
			memory.personaldifficulty$setOwnedTarget(best.getUUID());
			memory.personaldifficulty$setUnseenTicks(0);
			memory.personaldifficulty$setStall(0, 0);
		}
	}

	// ------------------------------------------------------------------ giving up on an unreachable Elite player (PursuitStall)

	/**
	 * Called on the mob's scan tick while it hunts an Elite player. After {@link PursuitStall#GIVE_UP_TICKS} of partial routes
	 * without progress it gives up the Elite extensions for that player for {@link PursuitStall#RETRY_TICKS}; if the player is
	 * beyond its vanilla follow range it also lets go of them (inside that range it keeps hunting as a vanilla mob would).
	 */
	private static void watchStall(Mob mob, HuntingMob memory, ServerPlayer hunted, long now) {
		if (gaveUpOn(mob, hunted)) {
			return;
		}
		Path path = mob.getNavigation().getPath();
		PursuitStall.Route route = path == null ? PursuitStall.Route.NONE : path.canReach() ? PursuitStall.Route.COMPLETE : PursuitStall.Route.PARTIAL;
		PursuitStall.State s = PursuitStall.next(new PursuitStall.State(memory.personaldifficulty$stallSince(), memory.personaldifficulty$stallBest()),
			route, mob.distanceTo(hunted), now);
		if (!PursuitStall.givesUp(s, now)) {
			memory.personaldifficulty$setStall(s.since(), s.best());
			return;
		}
		memory.personaldifficulty$setStall(0, 0);
		memory.personaldifficulty$giveUp(hunted.getUUID(), now + PursuitStall.RETRY_TICKS);
		double follow = mob.getAttributeValue(Attributes.FOLLOW_RANGE);
		if (mob.distanceToSqr(hunted) > follow * follow) {
			mob.setTarget(null);
			memory.personaldifficulty$setOwnedTarget(null);
			mob.getNavigation().stop();
		}
	}

	/** True while this mob has given up the Elite extensions for {@code target} (see {@link PursuitStall}). */
	public static boolean gaveUpOn(Mob mob, @Nullable Entity target) {
		if (!(target instanceof ServerPlayer)) {
			return false;
		}
		HuntingMob memory = (HuntingMob) mob;
		UUID id = memory.personaldifficulty$gaveUpOn();
		return id != null && id.equals(target.getUUID()) && PursuitStall.stillGivenUp(memory.personaldifficulty$gaveUpUntil(), mob.level().getGameTime());
	}

	/** {@code elites} without the player this mob has currently given up on (the same list if there is none). */
	static List<ServerPlayer> withoutGivenUp(Mob mob, List<ServerPlayer> elites, long now) {
		HuntingMob memory = (HuntingMob) mob;
		UUID id = memory.personaldifficulty$gaveUpOn();
		if (id == null || !PursuitStall.stillGivenUp(memory.personaldifficulty$gaveUpUntil(), now)) {
			return elites;
		}
		List<ServerPlayer> out = new java.util.ArrayList<>(elites.size());
		for (ServerPlayer p : elites) {
			if (!p.getUUID().equals(id)) {
				out.add(p);
			}
		}
		return out;
	}

	/** The mob has line of sight to an Elite player right now: remember it, and (alert network on) record the sighting. */
	private static void saw(PdRuntime rt, ServerLevel level, Mob mob, HuntingMob memory, ServerPlayer player) {
		memory.personaldifficulty$rememberElite(player.getUUID(), level.getGameTime());
		if (rt.config().eliteAlert().enabled()) {
			board(level).sighted(player.getUUID(), player.getX(), player.getY(), player.getZ(), mob.getId(), mob.getX(), mob.getY(), mob.getZ(), level.getGameTime());
		}
	}

	static AlertBoard board(ServerLevel level) {
		return BOARDS.computeIfAbsent(level, l -> new AlertBoard());
	}

	/**
	 * Phase 5: the remembered point this idle mob should search, or null. The mob must take part in hunting and have no target;
	 * the player must still be an eligible Elite player in this level; the point must lie within the absolute range cap of the
	 * mob. A point that is out of reach is marked as searched so it is not offered again.
	 */
	public static AlertBoard.@Nullable Known lastKnown(Mob mob) {
		PdRuntime rt = PdRuntime.get();
		if (rt == null || !(mob.level() instanceof ServerLevel level) || !mob.isAlive() || mob.getTarget() != null) {
			return null;
		}
		PdConfig.EliteAlert cfg = rt.config().eliteAlert();
		if (!cfg.enabled() || !participates(mob) || (mob instanceof Spider && mob.getLightLevelDependentMagicValue() >= 0.5F)) {
			return null;
		}
		AlertBoard board = BOARDS.get(level);
		if (board == null || board.size() == 0) {
			return null;
		}
		List<ServerPlayer> elites = elites(level, rt);
		if (elites.isEmpty()) {
			return null;
		}
		Set<UUID> eligible = new HashSet<>();
		for (ServerPlayer p : withoutGivenUp(mob, elites, level.getGameTime())) {
			eligible.add(p.getUUID());
		}
		HuntingMob memory = (HuntingMob) mob;
		AlertBoard.Known k = board.learn(mob.getId(), mob.getX(), mob.getY(), mob.getZ(), level.getGameTime(), cfg.radius(), cfg.hops(),
			cfg.memorySeconds() * 20L, eligible, memory.personaldifficulty$searchedPlayer(), memory.personaldifficulty$searchedTick());
		if (k == null) {
			return null;
		}
		double cap = rt.config().eliteAi().absoluteRangeCap();
		if (mob.distanceToSqr(k.x(), k.y(), k.z()) > cap * cap) {
			memory.personaldifficulty$markSearched(k.player(), k.tick());
			return null;
		}
		return k;
	}

	/** True while {@code player} is still an Elite player this mob may hunt in its level (alive, online there, not a spectator). */
	public static boolean stillEligible(Mob mob, UUID player) {
		PdRuntime rt = PdRuntime.get();
		if (rt == null || !(mob.level() instanceof ServerLevel level)) {
			return false;
		}
		for (ServerPlayer p : elites(level, rt)) {
			if (p.getUUID().equals(player)) {
				return true;
			}
		}
		return false;
	}

	/** Test and diagnostics access: the board of a level, or null if no sighting was ever recorded there. */
	public static @Nullable AlertBoard boardOf(ServerLevel level) {
		return BOARDS.get(level);
	}

	/** Vanilla's rules for keeping a target (alive, within range, not out of sight too long), with the Elite extensions. */
	private static boolean stillValid(PdRuntime rt, Mob mob, ServerLevel level, LivingEntity target, HuntingMob memory, PdConfig.EliteHunt cfg) {
		if (!target.isAlive() || target.isRemoved()) {
			return false;
		}
		boolean elite = isLiveEliteTarget(rt, target) && !gaveUpOn(mob, target);
		if (mob.getSensing().hasLineOfSight(target)) {
			memory.personaldifficulty$setUnseenTicks(0);
			if (elite) {
				saw(rt, level, mob, memory, (ServerPlayer) target);
			}
		} else {
			memory.personaldifficulty$setUnseenTicks(memory.personaldifficulty$unseenTicks() + cfg.scanIntervalTicks());
		}
		PdConfig.EliteAi ai = rt.config().eliteAi();
		double follow = mob.getAttributeValue(Attributes.FOLLOW_RANGE);
		double range = elite ? EliteSurvival.cappedRange(follow, EliteSurvival.pursuitFactor(ai), ai) : follow;
		int memoryTicks = elite && ai.improvedPursuit() ? EliteSurvival.memoryTicks(60, ai.targetMemoryMultiplier()) : 60;
		return mob.distanceToSqr(target) <= range * range && memory.personaldifficulty$unseenTicks() <= memoryTicks;
	}

	/**
	 * Nearest Elite player (first wins ties) that passes {@code conditions} within {@code range}. Shared by the periodic scan
	 * and by the nearest-player goal.
	 */
	static @Nullable ServerPlayer nearestVisible(ServerLevel level, Mob mob, List<ServerPlayer> elites, double range, TargetingConditions conditions) {
		conditions.range(range);
		double limit = range * range;
		ServerPlayer best = null;
		double bestDistance = Double.MAX_VALUE;
		for (ServerPlayer candidate : elites) {
			double distance = mob.distanceToSqr(candidate.getX(), candidate.getEyeY(), candidate.getZ());
			if (distance <= limit && distance < bestDistance && conditions.test(level, mob, candidate)) {
				best = candidate;
				bestDistance = distance;
			}
		}
		return best;
	}

	/** After a failed hidden-reacquire route check the mob makes no new one for this long (3 s). */
	static final int ROUTE_CHECK_PAUSE_TICKS = 60;

	private static @Nullable ServerPlayer rememberedAndReachable(
		ServerLevel level, Mob mob, HuntingMob memory, List<ServerPlayer> elites, double range, PdConfig.EliteHunt cfg
	) {
		UUID id = memory.personaldifficulty$lastElite();
		if (id == null || cfg.reacquireHiddenSeconds() <= 0
			|| level.getGameTime() - memory.personaldifficulty$lastEliteSeen() > cfg.reacquireHiddenSeconds() * 20L) {
			return null;
		}
		for (ServerPlayer candidate : elites) {
			if (!candidate.getUUID().equals(id)) {
				continue;
			}
			// BLIND skips only the line-of-sight test; range, invisibility, head gear and canAttack (Peaceful gate) still apply
			if (nearestVisible(level, mob, List.of(candidate), range, BLIND) == null) {
				return null;
			}
			// a failed route check is a full-budget search; repeating it every scan for every mob near an unreachable player was the
			// measured cost (+2.3 ms for about 20 idle zombies at the foot of a pillar), so a failure pauses this mob's checks
			long now = level.getGameTime();
			if (now < memory.personaldifficulty$routeCheckAfter()) {
				return null;
			}
			if (hasCompleteRoute(level, mob, candidate)) {
				return candidate;
			}
			memory.personaldifficulty$setRouteCheckAfter(now + ROUTE_CHECK_PAUSE_TICKS);
			return null;
		}
		return null;
	}

	/**
	 * A fresh, side-effect-free A* search with the mob's own pathfinder: true only if it reaches the target's block (so never
	 * through solid blocks and never into a sealed room). Bounded by the mob's normal maximum path length and node budget.
	 */
	/** Diagnostics: hidden-reacquire route searches made since the server started (read by the performance measurement). */
	private static long routeSearches;

	public static long routeSearches() {
		return routeSearches;
	}

	private static boolean hasCompleteRoute(ServerLevel level, Mob mob, ServerPlayer target) {
		routeSearches++;
		PathNavigationAccess access = (PathNavigationAccess) mob.getNavigation();
		float maxLength = access.personaldifficulty$maxPathLength();
		BlockPos from = mob.blockPosition().above();
		int radius = (int) (maxLength + 16);
		PathNavigationRegion region = new PathNavigationRegion(level, from.offset(-radius, -radius, -radius), from.offset(radius, radius, radius));
		Path route = access.personaldifficulty$pathFinder().findPath(region, mob, Set.of(target.blockPosition()), maxLength, 0, access.personaldifficulty$maxVisitedNodesMultiplier());
		return route != null && route.canReach();
	}

	/**
	 * Result of the nearest-player goal's own search. With priority on, an eligible Elite player within the extended range
	 * beats whatever ordinary player vanilla found; without priority the 1.2.0 behaviour applies (extended range only when
	 * vanilla found nobody).
	 */
	public static @Nullable LivingEntity preferElite(Mob mob, TargetingConditions vanilla, double vanillaRange, @Nullable LivingEntity found) {
		PdRuntime rt = PdRuntime.get();
		if (rt == null || !(mob.level() instanceof ServerLevel level)) {
			return found;
		}
		boolean priority = rt.config().eliteHunt().priorityEnabled() && participates(mob);
		if (found != null && (!priority || isLiveEliteTarget(rt, found))) {
			return found;
		}
		List<ServerPlayer> elites = elites(level, rt);
		if (elites.isEmpty()) {
			return found;
		}
		elites = withoutGivenUp(mob, elites, level.getGameTime());
		if (elites.isEmpty()) {
			return found;
		}
		double extended = EliteSurvival.cappedRange(vanillaRange, rt.config().eliteAi().acquisitionRangeMultiplier(), rt.config().eliteAi());
		if (extended <= vanillaRange && found != null) {
			// range not widened: still allow an Elite player inside the plain range to win over an ordinary one
			extended = vanillaRange;
		}
		ServerPlayer elite = nearestVisible(level, mob, elites, extended, copyFor(vanilla));
		return elite != null ? elite : found;
	}

	/** The goal's own conditions (selector included) as a throw-away copy so the shared instance is never mutated. */
	private static TargetingConditions copyFor(TargetingConditions vanilla) {
		return vanilla.copy();
	}
}
