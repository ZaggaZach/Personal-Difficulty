package com.zaggazach.personaldifficulty.mixin;

import com.zaggazach.personaldifficulty.PdEnderman;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enderman;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1.4.0 (Phase 6, plan 6.5): Enderman Blindside. Two vanilla teleports may land behind an Elite player instead:
 * <ul>
 * <li>{@code teleportTowards(target)}: closing the distance to a target more than 16 blocks away (only ever called for a target
 * the Enderman is already angry at);</li>
 * <li>{@code teleport()} while {@code hurtServer} handles a projectile (or splash potion) whose owner is an Elite player: the dodge.
 * The chance is rolled once per hit, and only the first teleport attempt of that hit is redirected; if it fails, vanilla's own
 * attempts follow unchanged.</li>
 * </ul>
 * The daylight teleport, staring, anger and every non-Elite case are untouched.
 */
@Mixin(Enderman.class)
abstract class EndermanBlindsideMixin {
	/** The Elite player whose projectile is being handled right now, if this hit won the Blindside roll. */
	@Unique
	private @Nullable ServerPlayer personaldifficulty$blindsideFor;

	@Inject(method = "teleportTowards", at = @At("HEAD"), cancellable = true)
	private void personaldifficulty$approachBehind(Entity target, CallbackInfoReturnable<Boolean> cir) {
		Enderman self = (Enderman) (Object) this;
		ServerPlayer player = PdEnderman.roll(self, target);
		if (player != null && PdEnderman.teleportBehind(self, player)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "hurtServer", at = @At("HEAD"))
	private void personaldifficulty$rememberEliteShooter(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
		this.personaldifficulty$blindsideFor = source.is(DamageTypeTags.IS_PROJECTILE) || source.getDirectEntity() != null && source.getDirectEntity() != source.getEntity()
			? PdEnderman.roll((Enderman) (Object) this, source.getEntity())
			: null;
	}

	@Inject(method = "hurtServer", at = @At("RETURN"))
	private void personaldifficulty$forgetEliteShooter(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
		this.personaldifficulty$blindsideFor = null;
	}

	@Inject(method = "teleport()Z", at = @At("HEAD"), cancellable = true)
	private void personaldifficulty$dodgeBehind(CallbackInfoReturnable<Boolean> cir) {
		ServerPlayer player = this.personaldifficulty$blindsideFor;
		if (player == null) {
			return;
		}
		this.personaldifficulty$blindsideFor = null; // only the first attempt of this hit
		if (PdEnderman.teleportBehind((Enderman) (Object) this, player)) {
			cir.setReturnValue(true);
		}
	}
}
