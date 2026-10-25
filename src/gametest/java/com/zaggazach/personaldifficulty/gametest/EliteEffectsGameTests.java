package com.zaggazach.personaldifficulty.gametest;

import static com.zaggazach.personaldifficulty.gametest.PdTestSupport.*;

import com.zaggazach.personaldifficulty.PdMobEffects;
import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.gametest.EliteAiGameTests.Arena;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Elite combat effects (1.3.0). {@code direct_*} tests make a real mob perform its real attack ({@code doHurtTarget}) or hurt
 * the player with a real {@code DamageSource}, inside one server tick (the config differs from the default, and it is global);
 * {@code ai_*} tests let live skeletons shoot with the default configuration and judge every single arrow hit.
 */
public class EliteEffectsGameTests {
	private static final PersonalDifficultyMode ELITE = PersonalDifficultyMode.ELITE;

	/** Effect applications per victim (from the diagnostics hook) and damaging hits per victim (from AFTER_DAMAGE). */
	private static final Map<UUID, AtomicInteger> APPLIED = new ConcurrentHashMap<>();
	private static final Map<UUID, AtomicInteger> ARROW_HITS = new ConcurrentHashMap<>();
	private static final Map<UUID, AtomicInteger> MAX_APPLIED_PER_HIT = new ConcurrentHashMap<>();
	private static final Map<UUID, AtomicInteger> LAST_APPLIED = new ConcurrentHashMap<>();

	static {
		PdMobEffects.appliedListener = p -> APPLIED.computeIfAbsent(p.getUUID(), k -> new AtomicInteger()).incrementAndGet();
		// registered after the mod's own AFTER_DAMAGE listener, so for one hit it runs after the mod's decision: the change in
		// the application counter across ONE hit event must be 0 or 1, never 2 (duplicate trigger)
		ServerLivingEntityEvents.AFTER_DAMAGE.register((victim, source, base, taken, blocked) -> {
			if (!(victim instanceof ServerPlayer p) || blocked || taken <= 0) {
				return;
			}
			int now = applied(p);
			int before = LAST_APPLIED.computeIfAbsent(p.getUUID(), k -> new AtomicInteger()).getAndSet(now);
			MAX_APPLIED_PER_HIT.computeIfAbsent(p.getUUID(), k -> new AtomicInteger()).accumulateAndGet(now - before, Math::max);
			if (source.is(DamageTypes.ARROW)) {
				ARROW_HITS.computeIfAbsent(p.getUUID(), k -> new AtomicInteger()).incrementAndGet();
			}
		});
	}

	private static int applied(ServerPlayer p) {
		AtomicInteger n = APPLIED.get(p.getUUID());
		return n == null ? 0 : n.get();
	}

	private static int count(Map<UUID, AtomicInteger> map, ServerPlayer p) {
		AtomicInteger n = map.get(p.getUUID());
		return n == null ? 0 : n.get();
	}

	private static final String ALWAYS = "elite.effects.zombie-infection-chance=1.0\nelite.effects.spider-venom-chance=1.0\n"
		+ "elite.effects.cave-spider-venom-chance=1.0\nelite.effects.skeleton-shot-chance=1.0\nelite.effects.drowned-chill-chance=1.0\n";

	private static <M extends Mob> M mob(GameTestHelper h, EntityType<M> type, ServerPlayer near) {
		M m = h.spawn(type, new Vec3(2.5, 1.0, 2.5));
		m.snapTo(near.getX() - 1.2, near.getY(), near.getZ());
		m.setPersistenceRequired();
		return m;
	}

	/** One real melee attack by {@code attacker} on {@code victim}; invulnerability frames are cleared so every call can land. */
	private static boolean hit(ServerLevel level, Mob attacker, ServerPlayer victim) {
		victim.setHealth(20.0F);
		victim.setAbsorptionAmount(0.0F);
		victim.damageCooldownTime = 0;
		return attacker.doHurtTarget(level, victim);
	}

	private static int amp(ServerPlayer p, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> e) {
		MobEffectInstance i = p.getEffect(e);
		return i == null ? -1 : i.getAmplifier();
	}

	private static int dur(ServerPlayer p, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> e) {
		MobEffectInstance i = p.getEffect(e);
		return i == null ? 0 : i.getDuration();
	}

	@GameTest
	public void direct_zombieHit_infectsAnElitePlayer_withWeaknessAndHunger(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		ServerPlayer elite = player(h, "pd_ez_e", ELITE);
		Mob zombie = mob(h, EntityTypes.ZOMBIE, elite);
		withConfig(ALWAYS, () -> {
			int before = applied(elite);
			check(hit(level, zombie, elite), "the zombie's real attack lands");
			check(elite.getHealth() < 20.0F, "and hurts");
			check(applied(elite) - before == 1, "exactly one application for one hit");
			check(amp(elite, MobEffects.WEAKNESS) == 0 && amp(elite, MobEffects.HUNGER) == 0, "Weakness I + Hunger I (the infection)");
			check(dur(elite, MobEffects.WEAKNESS) > 0 && dur(elite, MobEffects.WEAKNESS) <= 8 * 20, "for the configured 8 s (" + dur(elite, MobEffects.WEAKNESS) + " ticks)");
			check(!elite.hasEffect(MobEffects.NAUSEA), "no Nausea on the first infection");
		});
		h.succeed();
	}

	@GameTest
	public void direct_otherDifficultiesAndPeacefulAreNeverAffected(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		List<ServerPlayer> others = new ArrayList<>();
		for (PersonalDifficultyMode m : List.of(PersonalDifficultyMode.NORMAL, PersonalDifficultyMode.EASY, PersonalDifficultyMode.HARD, PersonalDifficultyMode.PEACEFUL)) {
			others.add(player(h, "pd_eo_" + m.commandName(), m));
		}
		withConfig(ALWAYS, () -> {
			for (ServerPlayer p : others) {
				Mob zombie = mob(h, EntityTypes.ZOMBIE, p);
				Mob spider = mob(h, EntityTypes.SPIDER, p);
				hit(level, zombie, p);
				hit(level, spider, p);
				check(applied(p) == 0, p.getName().getString() + ": no Elite effect application");
				check(!p.hasEffect(MobEffects.WEAKNESS) && !p.hasEffect(MobEffects.SLOWNESS), p.getName().getString() + ": no effect at all");
			}
		});
		ServerPlayer peaceful = others.get(3);
		check(peaceful.getHealth() == 20.0F, "Peaceful took no damage either");
		h.succeed();
	}

	@GameTest
	public void direct_blockedAndFullyPreventedHitsApplyNothing(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		ServerPlayer shielded = player(h, "pd_eb_s", ELITE);
		ServerPlayer resistant = player(h, "pd_eb_r", ELITE);
		withConfig(ALWAYS, () -> {
			// shield: raised for 8 ticks (vanilla's 5-tick delay), facing +Z, attacker in front
			shielded.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
			shielded.setYRot(0.0F);
			shielded.startUsingItem(InteractionHand.OFF_HAND);
			tickPlayer(shielded, 8);
			check(shielded.isBlocking(), "premise: blocking");
			Mob z1 = mob(h, EntityTypes.ZOMBIE, shielded);
			z1.snapTo(shielded.getX(), shielded.getY(), shielded.getZ() + 1.2);
			int before = applied(shielded);
			shielded.setHealth(20.0F);
			shielded.damageCooldownTime = 0;
			z1.doHurtTarget(level, shielded);
			check(shielded.getHealth() == 20.0F, "the shield blocked the hit completely (health " + shielded.getHealth() + ")");
			check(applied(shielded) == before && !shielded.hasEffect(MobEffects.WEAKNESS), "a blocked hit infects nobody");

			// Resistance V: the damage is reduced to zero
			resistant.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 600, 4));
			Mob z2 = mob(h, EntityTypes.ZOMBIE, resistant);
			hit(level, z2, resistant);
			check(resistant.getHealth() == 20.0F, "premise: fully prevented");
			check(applied(resistant) == 0 && !resistant.hasEffect(MobEffects.WEAKNESS), "a fully prevented hit infects nobody");
			resistant.removeEffect(MobEffects.RESISTANCE);
			hit(level, z2, resistant);
			check(applied(resistant) == 1 && resistant.hasEffect(MobEffects.WEAKNESS), "control: the same zombie, without Resistance, does infect");
		});
		h.succeed();
	}

	@GameTest
	public void direct_unrelatedAndSecondaryDamageNeverTriggersAnAttackerEffect(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		ServerPlayer elite = player(h, "pd_eu_e", ELITE);
		ServerPlayer other = player(h, "pd_eu_o", PersonalDifficultyMode.NORMAL);
		withConfig(ALWAYS, () -> {
			Mob zombie = mob(h, EntityTypes.ZOMBIE, elite);
			Mob husk = mob(h, EntityTypes.HUSK, elite);
			Mob vindicator = mob(h, EntityTypes.VINDICATOR, elite);
			List<DamageSource> unrelated = List.of(
				level.damageSources().fall(), level.damageSources().onFire(), level.damageSources().inFire(), level.damageSources().magic(),
				level.damageSources().explosion(zombie, zombie), level.damageSources().thorns(zombie), level.damageSources().indirectMagic(zombie, zombie),
				level.damageSources().playerAttack(other), level.damageSources().mobAttack(vindicator), level.damageSources().mobAttack(husk),
				level.damageSources().cactus(), level.damageSources().drown());
			for (DamageSource source : unrelated) {
				elite.setHealth(20.0F);
				elite.damageCooldownTime = 0;
				elite.hurtServer(level, source, 2.0F);
				check(applied(elite) == 0, "no application for damage type " + source.getMsgId() + " (" + applied(elite) + ")");
			}
			check(!elite.hasEffect(MobEffects.WEAKNESS) && !elite.hasEffect(MobEffects.HUNGER) && !elite.hasEffect(MobEffects.SLOWNESS), "no mob effect from any of them");
			// fire damage 'caused by' a zombie, and a zombie's thrown arrow-type damage, are not melee and not a skeleton arrow either
			elite.damageCooldownTime = 0;
			elite.hurtServer(level, level.damageSources().mobProjectile(zombie, zombie), 2.0F);
			check(applied(elite) == 0, "a zombie's non-melee damage never infects");
			check(amp(elite, MobEffects.HUNGER) == -1, "premise: no vanilla husk Hunger leaked in from the husk call either");
		});
		h.succeed();
	}

	@GameTest
	public void direct_spiderFamily_drowned_zombieVillager_andHuskAreHandledPerFamily(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		ServerPlayer elite = player(h, "pd_ef_e", ELITE);
		withConfig(ALWAYS, () -> {
			Mob spider = mob(h, EntityTypes.SPIDER, elite);
			hit(level, spider, elite);
			check(amp(elite, MobEffects.SLOWNESS) == 0 && dur(elite, MobEffects.SLOWNESS) <= 5 * 20 && dur(elite, MobEffects.SLOWNESS) > 0, "spider: Slowness I for at most 5 s");
			elite.removeAllEffects();

			Mob cave = mob(h, EntityTypes.CAVE_SPIDER, elite);
			hit(level, cave, elite);
			check(amp(elite, MobEffects.SLOWNESS) == 1 && dur(elite, MobEffects.SLOWNESS) <= 6 * 20, "cave spider: the stronger Slowness II, up to 6 s");
			System.out.println("EFFECTS cave spider on this world difficulty also left vanilla poison: " + elite.hasEffect(MobEffects.POISON));
			elite.removeAllEffects();

			Mob drowned = mob(h, EntityTypes.DROWNED, elite);
			hit(level, drowned, elite);
			check(amp(elite, MobEffects.SLOWNESS) == 0 && dur(elite, MobEffects.SLOWNESS) <= 3 * 20, "drowned (melee): Slowness I for at most 3 s");
			elite.removeAllEffects();

			Mob villager = mob(h, EntityTypes.ZOMBIE_VILLAGER, elite);
			hit(level, villager, elite);
			check(amp(elite, MobEffects.WEAKNESS) == 0 && amp(elite, MobEffects.HUNGER) == 0, "zombie villager: the infection too");
			elite.removeAllEffects();

			Mob husk = mob(h, EntityTypes.HUSK, elite);
			int before = applied(elite);
			hit(level, husk, elite);
			check(applied(elite) == before, "husk: the mod adds nothing (vanilla husks already inflict Hunger)");
			check(!elite.hasEffect(MobEffects.WEAKNESS), "and no Weakness");
		});
		h.succeed();
	}

	@GameTest
	public void direct_probabilityAndDurationFollowTheConfiguration(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		ServerPlayer elite = player(h, "pd_ec_e", ELITE);
		Mob zombie = mob(h, EntityTypes.ZOMBIE, elite);
		int[] landed = {0};
		withConfig("elite.effects.zombie-infection-chance=0.0\n", () -> {
			for (int i = 0; i < 100; i++) {
				hit(level, zombie, elite);
			}
			check(applied(elite) == 0 && !elite.hasEffect(MobEffects.WEAKNESS), "chance 0: never");
		});
		int base = applied(elite);
		withConfig("elite.effects.zombie-infection-chance=0.35\n", () -> {
			for (int i = 0; i < 600; i++) {
				elite.removeAllEffects();
				hit(level, zombie, elite);
				landed[0] += elite.hasEffect(MobEffects.WEAKNESS) ? 1 : 0;
			}
		});
		System.out.println("EFFECTS zombie infection at chance 0.35: " + landed[0] + " of 600 hits (expected 210 +- 36)");
		check(landed[0] >= 165 && landed[0] <= 255, "default chance 0.35 is honoured (" + landed[0] + "/600)");
		check(applied(elite) - base == landed[0], "every landed infection was counted exactly once");
		withConfig("elite.effects.zombie-infection-chance=1.0\nelite.effects.zombie-infection-seconds=3\n", () -> {
			elite.removeAllEffects();
			hit(level, zombie, elite);
			check(dur(elite, MobEffects.WEAKNESS) <= 60 && dur(elite, MobEffects.WEAKNESS) > 40, "duration 3 s is honoured (" + dur(elite, MobEffects.WEAKNESS) + ")");
		});
		h.succeed();
	}

	@GameTest
	public void direct_repeatedInfectionsDeepenOnceAndNeverStackWithoutBound(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		ServerPlayer elite = player(h, "pd_er_e", ELITE);
		Mob zombie = mob(h, EntityTypes.ZOMBIE, elite);
		withConfig("elite.effects.zombie-infection-chance=1.0\nelite.effects.zombie-infection-seconds=60\nelite.effects.max-duration-seconds=20\n", () -> {
			hit(level, zombie, elite);
			check(amp(elite, MobEffects.WEAKNESS) == 0 && !elite.hasEffect(MobEffects.NAUSEA), "first infection: Weakness I, no Nausea");
			check(dur(elite, MobEffects.WEAKNESS) <= 400, "60 s requested but capped at 20 s: " + dur(elite, MobEffects.WEAKNESS));
			hit(level, zombie, elite);
			check(amp(elite, MobEffects.WEAKNESS) == 1 && amp(elite, MobEffects.HUNGER) == 1 && elite.hasEffect(MobEffects.NAUSEA), "second infection while still ill: deepened (II + Nausea)");
			for (int i = 0; i < 40; i++) {
				hit(level, zombie, elite);
			}
			check(amp(elite, MobEffects.WEAKNESS) == 1 && amp(elite, MobEffects.HUNGER) == 1, "forty more hits: never beyond amplifier II");
			check(dur(elite, MobEffects.WEAKNESS) <= 400 && dur(elite, MobEffects.HUNGER) <= 400 && dur(elite, MobEffects.NAUSEA) <= 400, "...and no duration beyond the 20 s cap (" + dur(elite, MobEffects.WEAKNESS) + "/" + dur(elite, MobEffects.HUNGER) + "/" + dur(elite, MobEffects.NAUSEA) + ")");
			tickPlayer(elite, 5);
			check(dur(elite, MobEffects.WEAKNESS) < 400, "effects still run down normally");
		});
		h.succeed();
	}

	@GameTest
	public void direct_aStrongerExistingEffectIsNeverWeakened(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		ServerPlayer elite = player(h, "pd_es_e", ELITE);
		Mob zombie = mob(h, EntityTypes.ZOMBIE, elite);
		withConfig(ALWAYS, () -> {
			elite.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 3000, 2));
			hit(level, zombie, elite);
			check(amp(elite, MobEffects.WEAKNESS) == 2 && dur(elite, MobEffects.WEAKNESS) > 2000, "an existing Weakness III potion is left exactly as it was");
			check(amp(elite, MobEffects.HUNGER) == 0, "while the Hunger half of the infection still applies");
		});
		h.succeed();
	}

	@GameTest
	public void direct_disablingTheFeatureKeepsNormalAttacks(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		ServerPlayer elite = player(h, "pd_ed_e", ELITE);
		Mob zombie = mob(h, EntityTypes.ZOMBIE, elite);
		withConfig("elite.effects.enabled=false\n" + ALWAYS, () -> {
			for (int i = 0; i < 20; i++) {
				check(hit(level, zombie, elite), "the attack lands");
				check(elite.getHealth() < 20.0F, "and hurts (Elite damage unchanged)");
			}
			check(applied(elite) == 0 && !elite.hasEffect(MobEffects.WEAKNESS) && !elite.hasEffect(MobEffects.HUNGER), "no effects with elite.effects.enabled=false");
		});
		h.succeed();
	}

	@GameTest
	public void direct_malformedEffectConfigurationFallsBackSafely(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		ServerPlayer elite = player(h, "pd_em_e", ELITE);
		Mob zombie = mob(h, EntityTypes.ZOMBIE, elite);
		withConfig("elite.effects.zombie-infection-chance=lots\nelite.effects.zombie-infection-seconds=-4\nelite.effects.max-duration-seconds=NaN\nelite.effects.enabled=maybe\n", () -> {
			for (int i = 0; i < 30; i++) {
				hit(level, zombie, elite);
			}
			check(dur(elite, MobEffects.WEAKNESS) <= 30 * 20, "the cap fell back to its 30 s default");
			check(applied(elite) >= 1 && applied(elite) < 30, "chance fell back to 0.35: some, not all, of 30 hits (" + applied(elite) + ")");
		});
		h.succeed();
	}

	// ------------------------------------------------------------------ live archers

	@GameTest(maxTicks = 480)
	public void ai_skeletonArrows_applyAtMostOneEffectPerDamagingHit_andStraysAddNone(GameTestHelper h) {
		Arena a = new Arena(h, 70);
		ServerPlayer skeletonTarget = a.player(h, "pd_ea_k", ELITE, 40, 20);
		ServerPlayer strayTarget = a.player(h, "pd_ea_t", ELITE, 40, LANE_B + 20);
		List<Mob> shooters = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			Mob s = a.spawn(EntityTypes.SKELETON, 28 + (i % 4) * 8, 6 + (i / 4) * 8);
			s.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
			((net.minecraft.world.entity.monster.skeleton.AbstractSkeleton) s).reassessWeaponGoal();
			shooters.add(s);
		}
		for (int i = 0; i < 16; i++) {
			Mob s = a.spawn(EntityTypes.STRAY, 28 + (i % 4) * 8, LANE_B + 6 + (i / 4) * 5);
			s.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
			((net.minecraft.world.entity.monster.skeleton.AbstractSkeleton) s).reassessWeaponGoal();
			shooters.add(s);
		}
		everyTick(h, () -> {
			for (ServerPlayer p : List.of(skeletonTarget, strayTarget)) {
				p.setHealth(20.0F);
				p.getFoodData().setFoodLevel(20);
				tickPlayer(p);
			}
		});
		at(h, 440, () -> {
			int hits = count(ARROW_HITS, skeletonTarget);
			int strayHits = count(ARROW_HITS, strayTarget);
			long alive = shooters.stream().filter(Mob::isAlive).count();
			System.out.println("EFFECTS-ARROWS shooters alive: " + alive + " of " + shooters.size());
			System.out.printf("EFFECTS-ARROWS skeleton arrow hits=%d applications=%d max per hit=%d | stray arrow hits=%d applications=%d%n", hits, applied(skeletonTarget), count(MAX_APPLIED_PER_HIT, skeletonTarget), strayHits, applied(strayTarget));
			check(hits >= 15, "premise: the live skeletons landed many arrows (" + hits + ")");
			check(applied(skeletonTarget) >= 1, "at 25 % per arrow, at least one debuffing shot (" + applied(skeletonTarget) + ")");
			check(applied(skeletonTarget) <= hits, "never more applications than damaging arrow hits");
			check(count(MAX_APPLIED_PER_HIT, skeletonTarget) <= 1, "no single hit ever applied the effect twice (overlapping hooks)");
			check(applied(skeletonTarget) < hits, "and not every arrow debuffs (" + applied(skeletonTarget) + "/" + hits + ")");
			check(strayHits >= 3, "premise: strays landed arrows too (" + strayHits + ")");
			check(applied(strayTarget) == 0, "strays add nothing of their own (their arrows carry vanilla Slowness)");
			a.close();
			h.succeed();
		});
	}

	private static final int LANE_B = 80;
}
