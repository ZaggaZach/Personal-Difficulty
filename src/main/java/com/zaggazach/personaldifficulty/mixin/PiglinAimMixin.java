package com.zaggazach.personaldifficulty.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.zaggazach.personaldifficulty.PdHooks;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.piglin.Piglin;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Piglins are brain-driven: their crossbow shot gets no target from {@code getTarget()} (vanilla aims
 * them along their view vector). The brain's target is passed to {@code performRangedAttack}, so it is
 * exposed, for the duration of that one call only, to {@link CrossbowAimMixin}.
 */
@Mixin(Piglin.class)
abstract class PiglinAimMixin {
	@WrapMethod(method = "performRangedAttack")
	private void personaldifficulty$exposeTarget(LivingEntity target, float power, Operation<Void> original) {
		PdHooks.withAimTarget(target, () -> original.call(target, power));
	}
}
