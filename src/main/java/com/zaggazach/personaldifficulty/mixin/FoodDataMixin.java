package com.zaggazach.personaldifficulty.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.zaggazach.personaldifficulty.PdHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <ul>
 * <li>The exhaustion cost of natural regeneration is added inside {@code FoodData.tick} directly (it
 *     bypasses {@code Player.causeFoodExhaustion}). It is scaled the same way so hunger profiles cover
 *     healing too. This changes only the hunger cost; regeneration speed is not altered.
 * <li>Peaceful: exhaustion already accrued is capped at the vanilla threshold (4.0) so it can never be
 *     converted into saturation/food loss while Peaceful. Food and saturation are not edited.
 * </ul>
 */
@Mixin(FoodData.class)
abstract class FoodDataMixin {
	@Shadow
	private float exhaustionLevel;

	@Inject(method = "tick", at = @At("HEAD"))
	private void personaldifficulty$peacefulFreezesHunger(ServerPlayer player, CallbackInfo ci) {
		if (this.exhaustionLevel > 4.0F && PdHooks.isPeaceful(player)) {
			this.exhaustionLevel = 4.0F;
		}
	}

	@WrapOperation(
		method = "tick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/food/FoodData;addExhaustion(F)V")
	)
	private void personaldifficulty$scaleRegenExhaustion(FoodData self, float amount, Operation<Void> original, @Local(argsOnly = true) ServerPlayer player) {
		original.call(self, PdHooks.exhaustion(player, amount));
	}
}
