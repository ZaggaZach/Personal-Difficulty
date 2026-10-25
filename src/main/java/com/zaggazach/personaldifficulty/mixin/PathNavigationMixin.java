package com.zaggazach.personaldifficulty.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.zaggazach.personaldifficulty.PdHooks;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla path searches are limited to the mob's FOLLOW_RANGE attribute. Keeping an Elite target out to an extended
 * distance would be worthless if the mob could not path there (it would just stand and "hunt"), so while the mob's current
 * target is an Elite player the search length gets the same factor as the pursuit distance. The attribute itself, and every
 * path computed for any other target, is untouched.
 */
@Mixin(PathNavigation.class)
abstract class PathNavigationMixin {
	@Shadow
	@Final
	protected Mob mob;

	@ModifyReturnValue(method = "getMaxPathLength", at = @At("RETURN"))
	private float personaldifficulty$eliteTargetPathLength(float vanilla) {
		return PdHooks.maxPathLength(this.mob, vanilla);
	}
}
