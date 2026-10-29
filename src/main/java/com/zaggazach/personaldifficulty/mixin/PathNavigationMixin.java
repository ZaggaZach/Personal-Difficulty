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
 *
 * <p>The node budget is deliberately NOT enlarged (Phase 5, plan 6.2, measured): 3x vanilla's budget did not get a zombie round a
 * 75-block chasm, and 9x did not either, while 30 zombies hunting an Elite player they could never reach then cost +7.7 ms per
 * tick (p95 25 ms). Long detours remain a documented limitation.
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
