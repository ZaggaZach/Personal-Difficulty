package com.zaggazach.personaldifficulty.mixin;

import com.zaggazach.personaldifficulty.PdHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Scales exhaustion where it is accrued. Every player-driven source (sprinting, jumping, mining,
 * attacking, taking damage, the Hunger effect, swimming...) funnels through
 * {@code Player.causeFoodExhaustion}. Scaling the amount keeps vanilla's float exhaustion
 * remainder exact, so no fractional exhaustion is ever lost and food/saturation are never edited.
 */
@Mixin(Player.class)
abstract class PlayerExhaustionMixin {
	@ModifyVariable(method = "causeFoodExhaustion", at = @At("HEAD"), argsOnly = true)
	private float personaldifficulty$scaleExhaustion(float amount) {
		if ((Object) this instanceof ServerPlayer player) {
			return PdHooks.exhaustion(player, amount);
		}
		return amount;
	}
}
