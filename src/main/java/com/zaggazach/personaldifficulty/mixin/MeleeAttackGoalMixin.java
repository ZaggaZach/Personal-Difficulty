package com.zaggazach.personaldifficulty.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.zaggazach.personaldifficulty.PdHooks;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@code MeleeAttackGoal.canUse} throttles itself to one evaluation per 20 ticks, so a melee mob that has just acquired (or
 * re-acquired) a target can idle for up to a second before it starts to move. For an Elite target the throttle is 5 ticks.
 * Everything after the throttle (path creation, attack range, cooldown) is vanilla: this shortens a pause, it does not add
 * attacks. Used by zombies, spiders, vindicators and every other mob built on {@code MeleeAttackGoal}.
 */
@Mixin(MeleeAttackGoal.class)
abstract class MeleeAttackGoalMixin {
	@Shadow
	@Final
	protected PathfinderMob mob;

	@ModifyExpressionValue(method = "canUse", at = @At(value = "CONSTANT", args = "longValue=20"))
	private long personaldifficulty$eliteTargetsAreEngagedSooner(long vanilla) {
		return PdHooks.meleeRecheckTicks(this.mob, vanilla);
	}
}
