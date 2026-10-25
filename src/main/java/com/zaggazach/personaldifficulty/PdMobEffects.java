package com.zaggazach.personaldifficulty;

import com.zaggazach.personaldifficulty.config.PdConfig;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;

/**
 * Elite combat effects (1.3.0): temporary, bounded debuffs for specific hostile families that genuinely damage an Elite player.
 *
 * <p><b>Attribution.</b> Runs from Fabric's {@code AFTER_DAMAGE}, i.e. only after damage was really dealt (the event does not
 * fire for cancelled hits, so Peaceful and invulnerability frames never reach it), with {@code blocked == false} (shield) and only
 * if the victim's health plus absorption really went down (so a hit that Resistance reduced to zero counts for nothing).
 * It fires once per applied hit, so a hit cannot trigger twice. The cause must be the attacker itself:
 * <ul>
 * <li>melee: damage type {@code mob_attack} whose DIRECT entity is the attacker (excludes thorns, retaliation, secondary damage);</li>
 * <li>arrows: damage type {@code arrow} whose direct entity is an arrow OWNED by the attacker.</li>
 * </ul>
 * Fire, poison, explosions, falls, PvP and every other source are ignored. Only Elite victims are affected.
 *
 * <p><b>Bounds.</b> An effect is never stacked: durations are refreshed to {@code max(remaining, base)} capped at
 * {@code max-duration-seconds}, amplifiers never exceed the listed values, and a stronger effect from another source
 * is left alone.
 */
public final class PdMobEffects {
	/** Diagnostics hook, null in production: tests use it to count applications per victim and detect duplicate triggers. */
	public static volatile Consumer<ServerPlayer> appliedListener;

	// Health + absorption of the player whose hit is being processed, noted just before vanilla processes it. Fabric's AFTER_DAMAGE
	// reports a damage figure from the start of the pipeline (before Resistance/Protection), so a hit that Resistance V reduces to
	// zero still reports a positive number; the real test for "damage was dealt" is that health + absorption actually went down.
	// Hits are processed one at a time on the server thread, and a nested hit on another player simply overwrites the note.
	private static ServerPlayer notedPlayer;
	private static float notedTotal;

	private PdMobEffects() {
	}

	static void noteBeforeHit(LivingEntity victim) {
		if (victim instanceof ServerPlayer player) {
			notedPlayer = player;
			notedTotal = player.getHealth() + player.getAbsorptionAmount();
		}
	}

	private static void applied(ServerPlayer player) {
		Consumer<ServerPlayer> listener = appliedListener;
		if (listener != null) {
			listener.accept(player);
		}
	}

	public static void afterDamage(LivingEntity victim, DamageSource source, float baseDamageTaken, float damageTaken, boolean blocked) {
		if (blocked || damageTaken <= 0.0F || !(victim instanceof ServerPlayer player)) {
			return;
		}
		if (notedPlayer != player || player.getHealth() + player.getAbsorptionAmount() >= notedTotal) {
			return; // nothing was actually lost: fully prevented (Resistance, Protection, absorption of 0), or a hit we did not see start
		}
		PdRuntime rt = PdRuntime.get();
		if (rt == null || rt.modeOf(player.getUUID()) != PersonalDifficultyMode.ELITE) {
			return;
		}
		PdConfig.EliteEffects cfg = rt.config().eliteEffects();
		if (!cfg.enabled() || !(source.getEntity() instanceof LivingEntity attacker) || attacker == player) {
			return;
		}
		EntityType<?> type = attacker.getType();
		Entity direct = source.getDirectEntity();
		boolean melee = source.is(DamageTypes.MOB_ATTACK) && direct == attacker;
		boolean arrow = source.is(DamageTypes.ARROW) && direct instanceof AbstractArrow a && a.getOwner() == attacker;
		if (melee) {
			if (type == EntityTypes.ZOMBIE || type == EntityTypes.ZOMBIE_VILLAGER) {
				infect(player, attacker, cfg, cfg.zombie());
			} else if (type == EntityTypes.SPIDER) {
				strike(player, attacker, cfg.spider(), cfg, MobEffects.SLOWNESS, 0);
			} else if (type == EntityTypes.CAVE_SPIDER) {
				// vanilla cave spiders already poison on Normal/Hard worlds; the Elite venom is a deeper slow on top of that
				strike(player, attacker, cfg.caveSpider(), cfg, MobEffects.SLOWNESS, 1);
			} else if (type == EntityTypes.DROWNED) {
				strike(player, attacker, cfg.drowned(), cfg, MobEffects.SLOWNESS, 0);
			}
		} else if (arrow && type == EntityTypes.SKELETON) {
			// strays (vanilla slowness arrows) and bogged (poison arrows) already carry their own effect: no second one
			strike(player, attacker, cfg.skeleton(), cfg, MobEffects.SLOWNESS, 0);
		}
	}

	private static boolean roll(ServerPlayer player, PdConfig.MobEffectSetting setting) {
		return setting.chance() > 0.0 && player.getRandom().nextDouble() < setting.chance();
	}

	private static void strike(ServerPlayer player, LivingEntity attacker, PdConfig.MobEffectSetting setting, PdConfig.EliteEffects cfg,
		Holder<MobEffect> effect, int amplifier) {
		if (roll(player, setting)) {
			applied(player);
			refresh(player, attacker, effect, amplifier, setting.seconds() * 20, cfg.maxDurationSeconds() * 20);
		}
	}

	/**
	 * Zombie infection: Weakness + Hunger. If the player is still ill from an earlier infection (both present) the next one
	 * deepens it once - Weakness II, Hunger II and a short Nausea - and no further.
	 */
	private static void infect(ServerPlayer player, LivingEntity attacker, PdConfig.EliteEffects cfg, PdConfig.MobEffectSetting setting) {
		if (!roll(player, setting)) {
			return;
		}
		applied(player);
		int ticks = setting.seconds() * 20;
		int cap = cfg.maxDurationSeconds() * 20;
		boolean relapse = player.hasEffect(MobEffects.WEAKNESS) && player.hasEffect(MobEffects.HUNGER);
		int amplifier = relapse ? 1 : 0;
		refresh(player, attacker, MobEffects.WEAKNESS, amplifier, ticks, cap);
		refresh(player, attacker, MobEffects.HUNGER, amplifier, ticks, cap);
		if (relapse) {
			refresh(player, attacker, MobEffects.NAUSEA, 0, Math.max(60, ticks / 2), cap);
		}
	}

	private static void refresh(ServerPlayer player, LivingEntity attacker, Holder<MobEffect> effect, int amplifier, int ticks, int capTicks) {
		MobEffectInstance existing = player.getEffect(effect);
		int remaining = 0;
		if (existing != null) {
			if (existing.getAmplifier() > amplifier) {
				return; // something stronger (a potion, say) is already active: never weaken or shorten it
			}
			if (existing.getAmplifier() == amplifier) {
				remaining = existing.getDuration();
			}
		}
		int duration = Math.min(capTicks, Math.max(remaining, ticks));
		player.addEffect(new MobEffectInstance(effect, duration, amplifier), attacker);
	}
}
