package com.zaggazach.personaldifficulty.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.zaggazach.personaldifficulty.PdSkeletonShot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 1.4.0 (Phase 6, plan 6.4): a skeleton-family shot at an Elite player gets the Elite skeleton launch speed, damage scale and
 * predictive aim ({@link PdSkeletonShot}). Every other shot is passed through unchanged. The aim spread ("uncertainty") is not
 * touched here; the existing aim hook has already set it by the time this call is made.
 */
@Mixin(AbstractSkeleton.class)
abstract class AbstractSkeletonShotMixin {
	@WrapOperation(
		method = "performRangedAttack",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/Projectile;spawnProjectileUsingShoot(Lnet/minecraft/world/entity/projectile/Projectile;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;DDDFF)Lnet/minecraft/world/entity/projectile/Projectile;")
	)
	private Projectile personaldifficulty$eliteShot(Projectile projectile, ServerLevel level, ItemStack stack, double dx, double dy, double dz,
		float velocity, float uncertainty, Operation<Projectile> original, @Local(argsOnly = true) LivingEntity target) {
		double[] shot = PdSkeletonShot.adjust((AbstractSkeleton) (Object) this, target, projectile, dx, dy, dz, velocity);
		if (shot == null) {
			return original.call(projectile, level, stack, dx, dy, dz, velocity, uncertainty);
		}
		return original.call(projectile, level, stack, shot[0], shot[1], shot[2], (float) shot[3], uncertainty);
	}
}
