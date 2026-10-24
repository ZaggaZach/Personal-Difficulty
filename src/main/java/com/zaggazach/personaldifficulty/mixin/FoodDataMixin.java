package com.zaggazach.personaldifficulty.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.zaggazach.personaldifficulty.PdHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The exhaustion cost of natural regeneration is added inside {@code FoodData.tick} directly (it
 * bypasses {@code Player.causeFoodExhaustion}). It is scaled the same way so "slower hunger"
 * covers healing too. This changes only the hunger cost; regeneration speed is not altered.
 */
@Mixin(FoodData.class)
abstract class FoodDataMixin {
	@WrapOperation(
		method = "tick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/food/FoodData;addExhaustion(F)V")
	)
	private void personaldifficulty$scaleRegenExhaustion(FoodData self, float amount, Operation<Void> original, @Local(argsOnly = true) ServerPlayer player) {
		original.call(self, PdHooks.exhaustion(player, amount));
	}
}
