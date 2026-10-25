package com.zaggazach.personaldifficulty.mixin;

import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.PathFinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Lets {@code PdHunt} ask "is there a complete route?" WITHOUT calling {@code PathNavigation.createPath}: that method stores the
 * request as the navigation's current target and, on the next identical request, returns the mob's ACTIVE path instead of
 * searching (a stale path from some unrelated stroll reported {@code canReach() == true}; found by a flaky sealed-room test).
 */
@Mixin(PathNavigation.class)
public interface PathNavigationAccess {
	@Accessor("pathFinder")
	PathFinder personaldifficulty$pathFinder();

	@Accessor("maxVisitedNodesMultiplier")
	float personaldifficulty$maxVisitedNodesMultiplier();

	@Invoker("getMaxPathLength")
	float personaldifficulty$maxPathLength();
}
