package com.zaggazach.personaldifficulty.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.zaggazach.personaldifficulty.PdHooks;
import com.zaggazach.personaldifficulty.logic.RangedAccuracy;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Target-specific aim for {@code AbstractSkeleton}: widens the "uncertainty" passed to {@code Projectile.shoot}
 * for this one shot, and only when {@code target} is a player on Easy. Nothing on the mob (attributes,
 * goals, projectile speed/damage) is changed, so a target switch is honoured on the very next shot.
 */
@Mixin(AbstractSkeleton.class)
abstract class AbstractSkeletonAimMixin {
	@ModifyExpressionValue(
		method = "performRangedAttack",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/skeleton/AbstractSkeleton;rangedAttackUncertainty(Lnet/minecraft/world/level/Level;)F")
	)
	private float personaldifficulty$aim(float vanilla, @Local(argsOnly = true) LivingEntity target) {
		return PdHooks.rangedUncertainty((AbstractSkeleton) (Object) this, target, RangedAccuracy.Family.SKELETON, vanilla);
	}
}
