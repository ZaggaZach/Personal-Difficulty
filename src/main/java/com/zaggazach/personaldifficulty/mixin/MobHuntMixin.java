package com.zaggazach.personaldifficulty.mixin;

import com.zaggazach.personaldifficulty.PdHunt;
import com.zaggazach.personaldifficulty.PdLastKnownGoal;
import com.zaggazach.personaldifficulty.logic.HuntingMob;
import com.zaggazach.personaldifficulty.logic.PlayerTargetingGoal;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Entry point of Elite target priority: one cheap, throttled call per mob AI step, plus the per-mob scratch state. */
@Mixin(Mob.class)
abstract class MobHuntMixin implements HuntingMob {
	@Shadow
	@Final
	protected GoalSelector targetSelector;

	@Shadow
	@Final
	protected GoalSelector goalSelector;

	/** 0 = not computed, 1 = hunts players, 2 = does not. */
	@Unique
	private byte personaldifficulty$huntsPlayers;
	@Unique
	private @Nullable UUID personaldifficulty$lastElite;
	@Unique
	private long personaldifficulty$lastEliteSeen;
	@Unique
	private int personaldifficulty$unseenTicks;
	@Unique
	private @Nullable UUID personaldifficulty$ownedTarget;
	@Unique
	private @Nullable UUID personaldifficulty$searchedPlayer;
	@Unique
	private long personaldifficulty$searchedTick;
	@Unique
	private long personaldifficulty$stallSince;
	@Unique
	private double personaldifficulty$stallBest;
	@Unique
	private @Nullable UUID personaldifficulty$gaveUpOn;
	@Unique
	private long personaldifficulty$gaveUpUntil;
	@Unique
	private long personaldifficulty$routeCheckAfter;

	/**
	 * Every hostile pathfinding mob gets the last-known-position goal (Phase 5), right after its own goals were registered (vanilla
	 * registers goals at the end of this constructor, on the server only). Whether it ever runs is decided live, in its canUse: the
	 * setting, the mob's family and whether there is anything to search.
	 */
	@Inject(method = "<init>", at = @At("TAIL"))
	private void personaldifficulty$addLastKnownGoal(EntityType<? extends Mob> type, Level level, CallbackInfo ci) {
		if (level instanceof ServerLevel && (Object) this instanceof PathfinderMob mob && (Object) this instanceof Monster) {
			this.goalSelector.addGoal(PdLastKnownGoal.priorityFor(mob), new PdLastKnownGoal(mob));
		}
	}

	@Inject(method = "serverAiStep", at = @At("HEAD"))
	private void personaldifficulty$preferEliteTargets(CallbackInfo ci) {
		PdHunt.huntTick((Mob) (Object) this);
	}

	@Override
	public boolean personaldifficulty$huntsPlayers() {
		if (this.personaldifficulty$huntsPlayers == 0) {
			byte result = 2;
			for (WrappedGoal wrapped : this.targetSelector.getAvailableGoals()) {
				if (wrapped.getGoal() instanceof PlayerTargetingGoal goal && goal.personaldifficulty$targetsPlayers()) {
					result = 1;
					break;
				}
			}
			this.personaldifficulty$huntsPlayers = result;
		}
		return this.personaldifficulty$huntsPlayers == 1;
	}

	@Override
	public @Nullable UUID personaldifficulty$lastElite() {
		return this.personaldifficulty$lastElite;
	}

	@Override
	public long personaldifficulty$lastEliteSeen() {
		return this.personaldifficulty$lastEliteSeen;
	}

	@Override
	public @Nullable UUID personaldifficulty$ownedTarget() {
		return this.personaldifficulty$ownedTarget;
	}

	@Override
	public void personaldifficulty$setOwnedTarget(@Nullable UUID target) {
		this.personaldifficulty$ownedTarget = target;
	}

	@Override
	public int personaldifficulty$unseenTicks() {
		return this.personaldifficulty$unseenTicks;
	}

	@Override
	public void personaldifficulty$setUnseenTicks(int ticks) {
		this.personaldifficulty$unseenTicks = ticks;
	}

	@Override
	public @Nullable UUID personaldifficulty$searchedPlayer() {
		return this.personaldifficulty$searchedPlayer;
	}

	@Override
	public long personaldifficulty$searchedTick() {
		return this.personaldifficulty$searchedTick;
	}

	@Override
	public void personaldifficulty$markSearched(UUID player, long sightingTick) {
		this.personaldifficulty$searchedPlayer = player;
		this.personaldifficulty$searchedTick = sightingTick;
	}

	@Override
	public void personaldifficulty$rememberElite(UUID player, long gameTime) {
		this.personaldifficulty$lastElite = player;
		this.personaldifficulty$lastEliteSeen = gameTime;
	}

	@Override
	public long personaldifficulty$stallSince() {
		return this.personaldifficulty$stallSince;
	}

	@Override
	public double personaldifficulty$stallBest() {
		return this.personaldifficulty$stallBest;
	}

	@Override
	public void personaldifficulty$setStall(long since, double best) {
		this.personaldifficulty$stallSince = since;
		this.personaldifficulty$stallBest = best;
	}

	@Override
	public @Nullable UUID personaldifficulty$gaveUpOn() {
		return this.personaldifficulty$gaveUpOn;
	}

	@Override
	public long personaldifficulty$gaveUpUntil() {
		return this.personaldifficulty$gaveUpUntil;
	}

	@Override
	public void personaldifficulty$giveUp(@Nullable UUID player, long until) {
		this.personaldifficulty$gaveUpOn = player;
		this.personaldifficulty$gaveUpUntil = until;
	}

	@Override
	public long personaldifficulty$routeCheckAfter() {
		return this.personaldifficulty$routeCheckAfter;
	}

	@Override
	public void personaldifficulty$setRouteCheckAfter(long tick) {
		this.personaldifficulty$routeCheckAfter = tick;
	}
}
