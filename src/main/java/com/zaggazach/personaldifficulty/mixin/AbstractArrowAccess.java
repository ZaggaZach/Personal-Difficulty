package com.zaggazach.personaldifficulty.mixin;

import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the Elite skeleton shot scale an arrow's base damage (vanilla has a setter but no getter). */
@Mixin(AbstractArrow.class)
public interface AbstractArrowAccess {
	@Accessor("baseDamage")
	double personaldifficulty$baseDamage();
}
