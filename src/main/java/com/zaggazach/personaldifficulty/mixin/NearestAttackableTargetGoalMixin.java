package com.zaggazach.personaldifficulty.mixin;

import com.zaggazach.personaldifficulty.PdHooks;
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

/**
 * Wider acquisition of Elite players. Runs only when the vanilla search found nobody (so ordinary encounters are bit-for-bit
 * unchanged), only for player-hunting goals, and only evaluates Elite players, through the goal's own targeting conditions
 * at the larger radius. The goal already throttles itself to roughly one search per 10 ticks, so this adds one pass over the
 * online players on those occasions only.
 */
@Mixin(NearestAttackableTargetGoal.class)
abstract class NearestAttackableTargetGoalMixin extends TargetGoal {
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

	@Inject(method = "findTarget", at = @At("RETURN"))
	private void personaldifficulty$extendedAcquisitionOfElitePlayers(CallbackInfo ci) {
		if (this.target != null || (this.targetType != Player.class && this.targetType != ServerPlayer.class)) {
			return;
		}
		ServerPlayer elite = PdHooks.extendedAcquisition(this.mob, this.targetConditions, this.getFollowDistance());
		if (elite != null) {
			this.target = elite;
		}
	}
}
