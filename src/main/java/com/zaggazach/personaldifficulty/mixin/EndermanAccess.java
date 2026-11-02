package com.zaggazach.personaldifficulty.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enderman;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets the GameTests call vanilla's private "close the distance" teleport directly (the Blindside measurement). */
@Mixin(Enderman.class)
public interface EndermanAccess {
	@Invoker("teleportTowards")
	boolean personaldifficulty$teleportTowards(Entity target);
}
