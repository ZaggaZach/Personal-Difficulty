package com.zaggazach.personaldifficulty.logic;

/**
 * Minecraft-free description of one incoming damage event, built by the mixin from a DamageSource.
 *
 * @param typeId damage type id, e.g. {@code minecraft:fall}
 * @param voidDamage {@code minecraft:out_of_world}
 * @param adminDamage {@code /kill} or any other bypasses_invulnerability damage that is not the void
 * @param selfInflicted the responsible entity is the victim themself
 * @param fromOtherPlayer the responsible entity is a different player
 * @param environmental no responsible entity at all
 */
public record DamageContext(
	String typeId,
	boolean voidDamage,
	boolean adminDamage,
	boolean selfInflicted,
	boolean fromOtherPlayer,
	boolean environmental
) {
}
