package com.zaggazach.personaldifficulty.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.zaggazach.personaldifficulty.PdHooks;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import org.objectweb.asm.Opcodes;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Pursuit persistence, target-specific. {@code TargetGoal.canContinueToUse} (shared by every target goal, including
 * {@code HurtByTargetGoal} and {@code NearestAttackableTargetGoal}) abandons a target that is farther than the follow
 * distance or has been out of sight for {@code unseenMemoryTicks}. Both values are looked up from the target held at that
 * moment, so a different target, or a player leaving Elite, takes effect on the very next evaluation; nothing is stored.
 * The follow-range ATTRIBUTE is not touched, and {@code getFollowDistance()} still serves acquisition, alerting nearby
 * allies, etc. exactly as before.
 */
@Mixin(TargetGoal.class)
abstract class TargetGoalMixin {
	@Shadow
	@Final
	protected Mob mob;

	@ModifyExpressionValue(
		method = "canContinueToUse",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/goal/target/TargetGoal;getFollowDistance()D")
	)
	private double personaldifficulty$pursuitDistance(double within, @Local LivingEntity target) {
		return PdHooks.pursuitDistance(this.mob, target, within);
	}

	@ModifyExpressionValue(
		method = "canContinueToUse",
		at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/ai/goal/target/TargetGoal;unseenMemoryTicks:I", opcode = Opcodes.GETFIELD)
	)
	private int personaldifficulty$targetMemory(int ticks, @Local LivingEntity target) {
		return PdHooks.targetMemoryTicks(target, ticks);
	}
}
