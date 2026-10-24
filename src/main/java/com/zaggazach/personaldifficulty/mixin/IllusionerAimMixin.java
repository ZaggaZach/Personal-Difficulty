package com.zaggazach.personaldifficulty.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.zaggazach.personaldifficulty.PdHooks;
import com.zaggazach.personaldifficulty.logic.RangedAccuracy;
import net.minecraft.world.entity.monster.illager.Illusioner;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Target-specific aim for {@code Illusioner}: widens the "uncertainty" passed to {@code Projectile.shoot}
 * for this one shot, and only when {@code target} is a player on Easy. Nothing on the mob (attributes,
 * goals, projectile speed/damage) is changed, so a target switch is honoured on the very next shot.
 */
@Mixin(Illusioner.class)
abstract class IllusionerAimMixin {
	@ModifyExpressionValue(
		method = "performRangedAttack",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/illager/Illusioner;rangedAttackUncertainty(Lnet/minecraft/world/level/Level;)F")
	)
	private float personaldifficulty$aim(float vanilla, @Local(argsOnly = true) LivingEntity target) {
		return PdHooks.rangedUncertainty((Illusioner) (Object) this, target, RangedAccuracy.Family.ILLUSIONER, vanilla);
	}
}
