package com.zaggazach.personaldifficulty.mixin;

import com.zaggazach.personaldifficulty.PdHooks;
import com.zaggazach.personaldifficulty.PdHunt;
import com.zaggazach.personaldifficulty.logic.PlayerTargetingGoal;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Wider acquisition of Elite players. Runs only when the vanilla search found nobody (so ordinary encounters are bit-for-bit
 * unchanged), only for player-hunting goals, and only evaluates Elite players, through the goal's own targeting conditions
 * at the larger radius. The goal already throttles itself to roughly one search per 10 ticks, so this adds one pass over the
 * online players on those occasions only.
 */
@Mixin(NearestAttackableTargetGoal.class)
abstract class NearestAttackableTargetGoalMixin extends TargetGoal implements PlayerTargetingGoal {
	// Mixin cannot @Shadow members inherited from a superclass, so this mixin extends the superclass instead and uses
	// `mob` and `getFollowDistance()` directly. The constructor is never run.
	private NearestAttackableTargetGoalMixin() {
		super(null, false);
	}

	@Shadow
	@Final
	protected Class<? extends LivingEntity> targetType;

	@Shadow
	@Final
	protected TargetingConditions targetConditions;

	@Shadow
	protected @Nullable LivingEntity target;

	@Override
	public boolean personaldifficulty$targetsPlayers() {
		return this.targetType == Player.class || this.targetType == ServerPlayer.class;
	}

	/** While the mob holds a valid Elite target this goal must not start and replace it with the nearest ordinary player. */
	@Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
	private void personaldifficulty$keepEliteTarget(CallbackInfoReturnable<Boolean> cir) {
		if (this.personaldifficulty$targetsPlayers() && PdHunt.holdsEliteTarget(this.mob)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "findTarget", at = @At("RETURN"))
	private void personaldifficulty$eliteAcquisition(CallbackInfo ci) {
		if (!this.personaldifficulty$targetsPlayers()) {
			return;
		}
		// 1.3.0 priority: an eligible Elite player beats an ordinary one; 1.2.0 fallback: wider range when nobody was found
		if (PdHunt.priorityEnabled()) {
			LivingEntity chosen = PdHunt.preferElite(this.mob, this.targetConditions, this.getFollowDistance(), this.target);
			if (chosen != null) {
				this.target = chosen;
			}
		} else if (this.target == null) {
			ServerPlayer elite = PdHooks.extendedAcquisition(this.mob, this.targetConditions, this.getFollowDistance());
			if (elite != null) {
				this.target = elite;
			}
		}
	}
}
