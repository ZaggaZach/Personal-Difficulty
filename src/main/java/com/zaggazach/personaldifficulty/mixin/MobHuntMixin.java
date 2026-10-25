package com.zaggazach.personaldifficulty.mixin;

import com.zaggazach.personaldifficulty.PdHunt;
import com.zaggazach.personaldifficulty.logic.HuntingMob;
import com.zaggazach.personaldifficulty.logic.PlayerTargetingGoal;
import java.util.UUID;
import net.minecraft.world.entity.Mob;
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
	public void personaldifficulty$rememberElite(UUID player, long gameTime) {
		this.personaldifficulty$lastElite = player;
		this.personaldifficulty$lastEliteSeen = gameTime;
	}
}
