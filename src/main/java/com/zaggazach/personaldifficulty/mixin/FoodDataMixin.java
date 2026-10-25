package com.zaggazach.personaldifficulty.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
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
 * <li>Elite: natural regeneration is slowed by stretching the two regeneration INTERVALS (10 ticks when saturated, 80 ticks
 *     when merely well fed) by 1/multiplier. Vanilla counts a per-player timer up to the interval, heals
 *     {@code min(saturation,6)/6} (or 1.0), then charges the matching exhaustion. Stretching the interval therefore divides
 *     the rate by exactly the multiplier while the amount per heal and the hunger cost per heal stay vanilla (the player is
 *     not healed less per heal, and does not pay more hunger per heal). The conditions that decide WHETHER regeneration
 *     happens (game rule, food level, "is hurt") are untouched, and so is the 80-tick starvation interval that shares the
 *     same timer. Nothing is stored: the interval is computed from the player's current mode on every tick, so leaving
 *     Elite takes effect on the next tick.
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

	/** Saturated regeneration: vanilla {@code tickTimer >= 10}. */
	@ModifyExpressionValue(method = "tick", at = @At(value = "CONSTANT", args = "intValue=10", ordinal = 0))
	private int personaldifficulty$saturatedRegenInterval(int vanilla, @Local(argsOnly = true) ServerPlayer player) {
		return PdHooks.regenInterval(player, vanilla);
	}

	/** Well-fed regeneration: vanilla {@code tickTimer >= 80} (first occurrence; the second is the starvation interval, left alone). */
	@ModifyExpressionValue(method = "tick", at = @At(value = "CONSTANT", args = "intValue=80", ordinal = 0))
	private int personaldifficulty$wellFedRegenInterval(int vanilla, @Local(argsOnly = true) ServerPlayer player) {
		return PdHooks.regenInterval(player, vanilla);
	}

	@WrapOperation(
		method = "tick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/food/FoodData;addExhaustion(F)V")
	)
	private void personaldifficulty$scaleRegenExhaustion(FoodData self, float amount, Operation<Void> original, @Local(argsOnly = true) ServerPlayer player) {
		original.call(self, PdHooks.exhaustion(player, amount));
	}
}
