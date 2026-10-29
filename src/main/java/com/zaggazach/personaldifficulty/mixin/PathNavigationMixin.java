package com.zaggazach.personaldifficulty.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.spongepowered.asm.mixin.injection.ModifyArg;
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

	/**
	 * Phase 5 (plan 6.2): vanilla sizes the path search's node budget as 16 nodes per block of FOLLOW_RANGE, computed once from
	 * the attribute, so a search stretched to 3x the distance for an Elite target still had the 1x budget. On open ground that
	 * is enough; around an obstacle the search ran out and returned a partial route that ended at the obstacle (a measured
	 * zombie stalled at the edge of a chasm 59 blocks from its Elite target). While the target is an Elite player, the budget
	 * grows with the square of the stretch factor (see {@link PdHooks#visitedNodesMultiplier}). Every other search is untouched.
	 */
	@ModifyArg(method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/pathfinder/PathFinder;findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;"),
		index = 5)
	private float personaldifficulty$eliteTargetNodeBudget(float vanilla) {
		return PdHooks.visitedNodesMultiplier(this.mob, (float) this.mob.getAttributeValue(Attributes.FOLLOW_RANGE), vanilla);
	}
}
