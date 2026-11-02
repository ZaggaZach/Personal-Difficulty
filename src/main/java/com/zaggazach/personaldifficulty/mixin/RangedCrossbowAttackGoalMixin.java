package com.zaggazach.personaldifficulty.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.zaggazach.personaldifficulty.PdHooks;
import net.minecraft.world.entity.ai.goal.RangedCrossbowAttackGoal;
import net.minecraft.world.entity.monster.Monster;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 1.4.0 (Phase 6, plan 6.6): after charging its crossbow, a pillager hunting an Elite player waits
 * {@code elite.pillager.attack-delay-ticks} before firing instead of vanilla's 20 to 39. The first write of the wait in
 * {@code tick} is the one made when charging completes; the countdown itself is vanilla's.
 */
@Mixin(RangedCrossbowAttackGoal.class)
abstract class RangedCrossbowAttackGoalMixin {
	@Shadow
	@Final
	private Monster mob;

	@WrapOperation(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/ai/goal/RangedCrossbowAttackGoal;attackDelay:I", opcode = Opcodes.PUTFIELD, ordinal = 0))
	private void personaldifficulty$eliteDelay(RangedCrossbowAttackGoal<?> goal, int delay, Operation<Void> original) {
		original.call(goal, PdHooks.crossbowDelay(this.mob, delay));
	}
}
