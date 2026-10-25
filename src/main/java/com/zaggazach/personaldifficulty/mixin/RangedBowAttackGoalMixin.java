package com.zaggazach.personaldifficulty.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.zaggazach.personaldifficulty.PdHooks;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import net.minecraft.world.entity.monster.Monster;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla gives skeletons a 40-tick bow cadence and a 20-tick one on Hard ({@code reassessWeaponGoal}). Against an Elite
 * player a skeleton uses the Hard cadence on any world difficulty. The interval is read when a shot is fired, from the
 * current target, so it follows the target and cannot go stale. Aim, charge time, range and strafing are untouched.
 */
@Mixin(RangedBowAttackGoal.class)
abstract class RangedBowAttackGoalMixin {
	@Shadow
	@Final
	private Monster mob;

	@ModifyExpressionValue(
		method = "tick",
		at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/ai/goal/RangedBowAttackGoal;attackIntervalMin:I", opcode = Opcodes.GETFIELD)
	)
	private int personaldifficulty$eliteTargetsGetHardCadence(int interval) {
		return PdHooks.bowInterval(this.mob, interval);
	}
}
