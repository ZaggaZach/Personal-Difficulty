package com.zaggazach.personaldifficulty;

import com.zaggazach.personaldifficulty.config.PdConfig;
import com.zaggazach.personaldifficulty.logic.SkeletonAim;
import com.zaggazach.personaldifficulty.mixin.AbstractArrowAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 1.4.0 (Phase 6, plan 6.4): the Elite skeleton shot. Called with vanilla's own direction and launch speed for a skeleton-family
 * shot; returns null (vanilla shot) unless the target is an Elite player and the Elite skeleton settings are on.
 *
 * <ul>
 * <li>speed: vanilla's launch speed times {@code arrow-speed-multiplier};</li>
 * <li>damage: the arrow's base damage times {@code arrow-damage-multiplier} (1.0 leaves it untouched); vanilla still multiplies
 * by the speed at impact, so a faster arrow can hit harder on its own (measured, see the README);</li>
 * <li>aim: {@link SkeletonAim} with the player's last reported movement, blended with vanilla's direction by
 * {@code prediction-strength}. Aiming at the same point vanilla aims at (a third of the way up the target).</li>
 * </ul>
 */
public final class PdSkeletonShot {
	private PdSkeletonShot() {
	}

	/** {dx, dy, dz, velocity} for the Elite shot, or null for vanilla. */
	public static double @Nullable [] adjust(AbstractSkeleton skeleton, @Nullable LivingEntity target, Projectile projectile, double dx, double dy,
		double dz, float velocity) {
		PdConfig.EliteSkeleton sk = PdHooks.eliteSkeletonFor(target);
		if (sk == null || !(target instanceof ServerPlayer player)) {
			return null;
		}
		double speed = velocity * sk.arrowSpeedMultiplier();
		if (projectile instanceof AbstractArrow arrow && sk.arrowDamageMultiplier() != 1.0) {
			arrow.setBaseDamage(((AbstractArrowAccess) arrow).personaldifficulty$baseDamage() * sk.arrowDamageMultiplier());
		}
		Vec3 movement = player.getKnownMovement();
		double[] dir = SkeletonAim.direction(projectile.getX(), projectile.getY(), projectile.getZ(), target.getX(), target.getY(0.3333333333333333),
			target.getZ(), movement.x, movement.z, speed, sk.predictionStrength(), new double[] {dx, dy, dz});
		return new double[] {dir[0], dir[1], dir[2], speed};
	}
}
