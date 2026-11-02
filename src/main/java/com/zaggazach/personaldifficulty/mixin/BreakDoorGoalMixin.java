package com.zaggazach.personaldifficulty.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.zaggazach.personaldifficulty.PdHooks;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.BreakDoorGoal;
import net.minecraft.world.entity.ai.goal.DoorInteractGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 1.4.0 (Phase 6, plan 6.6): a door-breaking goal's difficulty check passes on any world difficulty while its mob hunts an Elite
 * player and {@code elite.zombie.break-doors} is on. Vanilla allows it only on Hard. Everything else about breaking a door (the
 * mob must be pushing against it on its route, the break time, which doors) is vanilla's.
 */
@Mixin(BreakDoorGoal.class)
abstract class BreakDoorGoalMixin extends DoorInteractGoal {
	private BreakDoorGoalMixin(Mob mob) {
		super(mob);
	}

	@ModifyReturnValue(method = "isValidDifficulty", at = @At("RETURN"))
	private boolean personaldifficulty$eliteTargetAnyDifficulty(boolean vanilla) {
		return PdHooks.breakDoorDifficulty(this.mob, vanilla);
	}
}
