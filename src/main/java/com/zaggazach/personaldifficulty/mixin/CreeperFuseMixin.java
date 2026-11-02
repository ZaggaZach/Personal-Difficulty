package com.zaggazach.personaldifficulty.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.zaggazach.personaldifficulty.PdHooks;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 1.4.0 (Phase 6, plan 6.6): a creeper hunting an Elite player explodes after {@code elite.creeper.fuse-ticks} (never below 15)
 * instead of vanilla's 30, when {@code elite.creeper.short-fuse} is on. Read where {@code tick} compares the swell with the fuse,
 * from the current target; the stored fuse is never changed.
 */
@Mixin(Creeper.class)
abstract class CreeperFuseMixin {
	@ModifyExpressionValue(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/monster/Creeper;maxSwell:I", opcode = Opcodes.GETFIELD))
	private int personaldifficulty$eliteFuse(int vanilla) {
		return PdHooks.creeperFuse((Mob) (Object) this, vanilla);
	}
}
