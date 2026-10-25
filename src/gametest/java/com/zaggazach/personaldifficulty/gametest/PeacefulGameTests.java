package com.zaggazach.personaldifficulty.gametest;

import static com.zaggazach.personaldifficulty.gametest.PdTestSupport.*;

import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.gametest.PdTestSupport.Capture;
import com.zaggazach.personaldifficulty.gametest.PdTestSupport.Outcome;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton;
import net.minecraft.world.entity.monster.spider.CaveSpider;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

/**
 * Peaceful, part 1: damage, PvP, effects, hunger, transitions and lifecycle with real players, real
 * projectiles, real explosions and real tamed animals. Mob AI behaviour is in {@link PeacefulAiGameTests}.
 */
public class PeacefulGameTests {
	private static final PersonalDifficultyMode PEACEFUL = PersonalDifficultyMode.PEACEFUL;
	private static final PersonalDifficultyMode NORMAL = PersonalDifficultyMode.NORMAL;

	/** Captures everything the mod must never change, so a test can prove it stayed the same. */
	private record Guard(net.minecraft.world.Difficulty levelDifficulty, net.minecraft.world.Difficulty worldDataDifficulty, boolean locked, boolean naturalRegen) {
		static Guard take(ServerLevel level) {
			return new Guard(level.getDifficulty(), level.getServer().getWorldData().getDifficulty(), level.getServer().getWorldData().isDifficultyLocked(),
				level.getGameRules().get(GameRules.NATURAL_HEALTH_REGENERATION));
		}

		void verify(ServerLevel level) {
			check(this.equals(take(level)), "the world's difficulty and game rules must never be changed by the mod: before " + this + " after " + take(level));
		}
	}

	/** Moves a player far away so it cannot stand in a projectile's path (entities block arrows). */
	private static void park(ServerPlayer... players) {
		for (ServerPlayer p : players) {
			p.snapTo(p.getX(), p.getY() + 200.0, p.getZ() + 200.0);
		}
	}

	private static void freshHit(ServerPlayer p) {
		p.setHealth(20.0F);
		p.setAbsorptionAmount(0.0F);
		p.damageCooldownTime = 0;
	}

	// ------------------------------------------------------------------ damage

	@GameTest
	public void peaceful_everyRegisteredDamageTypeIsBlocked_exceptVoidAndKill(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		Guard guard = Guard.take(level);
		ServerPlayer peaceful = player(h, "pd_pa_peace", PEACEFUL);
		ServerPlayer control = player(h, "pd_pa_control", NORMAL);
		var registry = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE);
		List<String> blocked = new ArrayList<>();
		List<String> bypassing = new ArrayList<>();
		List<String> harmlessToVanilla = new ArrayList<>();
		int total = 0;
		for (Holder.Reference<DamageType> holder : registry.listElements().toList()) {
			total++;
			String id = holder.key().identifier().toString();
			DamageSource src = new DamageSource(holder);
			float controlLoss = lost(control, src, 1.0F);
			float peacefulLoss = lost(peaceful, src, 1.0F);
			if (src.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
				check(controlLoss > 0 && peacefulLoss > 0, id + " (void / /kill style) must still hurt a Peaceful player: control " + controlLoss + ", peaceful " + peacefulLoss);
				bypassing.add(id);
			} else if (controlLoss <= 0) {
				check(peacefulLoss == 0, id + " cannot hurt even a Normal player here, so Peaceful must not be hurt either");
				harmlessToVanilla.add(id);
			} else {
				check(peacefulLoss == 0, id + " must be fully blocked for Peaceful (a Normal player lost " + controlLoss + ")");
				blocked.add(id);
			}
		}
		System.out.println("PEACEFUL-DAMAGE-TYPES total=" + total + " blocked=" + blocked.size() + " bypassing=" + bypassing + " harmless-to-vanilla-here=" + harmlessToVanilla);
		check(total >= 40, "sanity: the vanilla registry should hold dozens of damage types, saw " + total);
		check(bypassing.size() == 2 && bypassing.containsAll(List.of("minecraft:out_of_world", "minecraft:generic_kill")), "exactly the void and /kill types bypass: " + bypassing);
		for (String must : List.of("minecraft:fall", "minecraft:in_fire", "minecraft:on_fire", "minecraft:lava", "minecraft:drown", "minecraft:in_wall", "minecraft:cactus", "minecraft:sweet_berry_bush",
			"minecraft:freeze", "minecraft:hot_floor", "minecraft:starve", "minecraft:mob_attack", "minecraft:arrow", "minecraft:explosion", "minecraft:magic", "minecraft:wither", "minecraft:lightning_bolt")) {
			check(blocked.contains(must), must + " is in the blocked set: " + blocked);
		}
		guard.verify(level);
		h.succeed();
	}

	@GameTest
	public void peaceful_realMobAttacksAndProjectilesAndExplosions(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		Guard guard = Guard.take(level);
		ServerPlayer peaceful = player(h, "pd_pb_peace", PEACEFUL);
		ServerPlayer control = player(h, "pd_pb_control", NORMAL);

		// melee with a real zombie
		Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 2, 1, 2);
		for (int i = 0; i < 5; i++) {
			zombie.snapTo(peaceful.getX() - 1, peaceful.getY(), peaceful.getZ());
			freshHit(peaceful);
			check(!zombie.doHurtTarget(level, peaceful) || peaceful.getHealth() == 20.0F, "a zombie's real melee attack deals nothing to Peaceful");
			near(20.0F, peaceful.getHealth(), "melee");
		}
		freshHit(control);
		zombie.doHurtTarget(level, control);
		check(control.getHealth() < 20.0F, "control: the same real melee attack hurts a Normal player");
		zombie.discard(); // it would otherwise stand in the arrows' path

		// ranged: a real arrow flies, collides and is stopped
		AbstractSkeleton skeleton = h.spawnWithNoFreeWill(EntityTypes.SKELETON, 2, 1, 2);
		skeleton.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
		int controlHits = 0;
		for (int i = 0; i < 40; i++) {
			for (ServerPlayer victim : List.of(peaceful, control)) {
				park(peaceful, control);
				Vec3 spot = h.absoluteVec(new Vec3(6.5, 1.0, 2.5));
				victim.snapTo(spot.x, spot.y, spot.z); // alone in the arrow's path
				freshHit(victim);
				skeleton.performRangedAttack(victim, 1.0F);
				List<AbstractArrow> arrows = level.getEntitiesOfClass(AbstractArrow.class, skeleton.getBoundingBox().inflate(3.0));
				check(arrows.size() == 1, "one arrow in flight");
				AbstractArrow arrow = arrows.get(0);
				for (int t = 0; t < 12 && !arrow.isRemoved(); t++) {
					arrow.tick();
				}
				arrow.discard();
				if (victim == peaceful) {
					near(20.0F, victim.getHealth(), "arrow " + i + " must never hurt Peaceful");
				} else if (victim.getHealth() < 20.0F) {
					controlHits++;
				}
			}
		}
		check(controlHits > 10, "control: real arrows do hit and hurt a Normal player (" + controlHits + "/40)");

		// explosions: TNT with no owner, 6.5 blocks from both players at once
		Vec3 blast = h.absoluteVec(new Vec3(7.5, 1.0, 1.5));
		peaceful.snapTo(blast.x, blast.y, blast.z);
		control.snapTo(blast.x, blast.y, blast.z);
		freshHit(peaceful);
		freshHit(control);
		detonate(h, 1.5, 1.5, null);
		near(20.0F, peaceful.getHealth(), "explosion next to Peaceful");
		check(control.getHealth() < 20.0F, "control: the same explosion hurts the Normal player standing in the same spot (" + control.getHealth() + ")");
		guard.verify(level);
		h.succeed();
	}

	/** Spawns real TNT at (x, 1, z) of the arena, optionally owned, and runs it to detonation. */
	private static void detonate(GameTestHelper h, double x, double z, ServerPlayer owner) {
		Vec3 at = h.absoluteVec(new Vec3(x, 1.0, z));
		PrimedTnt tnt = new PrimedTnt(h.getLevel(), at.x, at.y, at.z, owner);
		tnt.setFuse(2);
		h.getLevel().addFreshEntity(tnt);
		for (int i = 0; i < 4 && !tnt.isRemoved(); i++) {
			tnt.tick();
		}
		check(tnt.isRemoved(), "the TNT really exploded");
	}

	// ------------------------------------------------------------------ pvp

	@GameTest
	public void peaceful_pvpMatrix_meleeIsBlockedInBothDirections_andOrdinaryPvpIsUntouched(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		Guard guard = Guard.take(level);
		floor(h);
		Map<PersonalDifficultyMode, ServerPlayer> players = new EnumMap<>(PersonalDifficultyMode.class);
		for (PersonalDifficultyMode m : PersonalDifficultyMode.values()) {
			players.put(m, player(h, "pd_pvp_" + m.commandName(), m));
		}
		int checked = 0;
		StringBuilder grid = new StringBuilder("PVP-MATRIX (melee damage dealt; rows attacker, cols victim)");
		for (PersonalDifficultyMode a : PersonalDifficultyMode.values()) {
			grid.append(String.format("%n  %-9s", a));
			for (PersonalDifficultyMode v : PersonalDifficultyMode.values()) {
				ServerPlayer attacker = players.get(a);
				ServerPlayer victim = players.get(v);
				if (a == v) {
					grid.append("   (self) ");
					continue;
				}
				tickPlayer(attacker, 8); // charge the attack so a real hit does real damage
				victim.snapTo(attacker.getX() + 1.0, attacker.getY(), attacker.getZ());
				freshHit(victim);
				attacker.attack(victim);
				float loss = 20.0F - victim.getHealth();
				boolean expectBlocked = a == PEACEFUL || v == PEACEFUL;
				if (expectBlocked) {
					check(loss == 0.0F, a + " attacks " + v + ": Peaceful protection applies in both directions but " + loss + " damage was dealt");
				} else {
					check(loss > 0.0F, a + " attacks " + v + ": ordinary PvP must be untouched but no damage was dealt");
				}
				grid.append(String.format(" %6.2f   ", loss));
				checked++;
			}
		}
		System.out.println(grid);
		check(checked == 20, "all 20 ordered pairs were exercised");
		guard.verify(level);
		h.succeed();
	}

	@GameTest
	public void peaceful_pvpProjectilesTntAndTamedAnimalsAreAttributedToTheirOwner(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		Guard guard = Guard.take(level);
		floor(h);
		Map<PersonalDifficultyMode, ServerPlayer> players = new EnumMap<>(PersonalDifficultyMode.class);
		for (PersonalDifficultyMode m : List.of(PEACEFUL, NORMAL, PersonalDifficultyMode.ELITE)) {
			players.put(m, player(h, "pd_pvpx_" + m.commandName(), m));
		}
		// 1) arrows owned by a player: the immediate source is an arrow, the owner decides
		for (var a : players.entrySet()) {
			for (var v : players.entrySet()) {
				if (a.getKey() == v.getKey()) {
					continue;
				}
				ServerPlayer attacker = a.getValue();
				ServerPlayer victim = v.getValue();
				park(players.values().toArray(new ServerPlayer[0]));
				Vec3 from = h.absoluteVec(new Vec3(1.5, 1.0, 3.5));
				attacker.snapTo(from.x, from.y, from.z);
				victim.snapTo(from.x + 3.0, from.y, from.z); // alone in the arrow's path
				freshHit(victim);
				AbstractArrow arrow = ProjectileUtil.getMobArrow(attacker, new ItemStack(Items.ARROW), 1.0F, null);
				arrow.setPos(attacker.getX(), attacker.getEyeY() - 0.5, attacker.getZ());
				arrow.shoot(1.0, 0.0, 0.0, 1.6F, 0.0F);
				level.addFreshEntity(arrow);
				for (int t = 0; t < 12 && !arrow.isRemoved(); t++) {
					arrow.tick();
				}
				arrow.discard();
				float loss = 20.0F - victim.getHealth();
				if (a.getKey() == PEACEFUL || v.getKey() == PEACEFUL) {
					check(loss == 0.0F, "arrow " + a.getKey() + " -> " + v.getKey() + " must be blocked, lost " + loss);
				} else {
					check(loss > 0.0F, "arrow " + a.getKey() + " -> " + v.getKey() + " is ordinary PvP and must still hurt");
				}
			}
		}
		// 2) TNT owned by a player (explosion attributed to its owner), victim 6.5 blocks away
		ServerPlayer peaceful = players.get(PEACEFUL);
		ServerPlayer normal = players.get(NORMAL);
		park(players.values().toArray(new ServerPlayer[0]));
		Vec3 blast = h.absoluteVec(new Vec3(7.5, 1.0, 1.5));
		normal.snapTo(blast.x, blast.y, blast.z);
		freshHit(normal);
		detonate(h, 1.5, 1.5, peaceful);
		near(20.0F, normal.getHealth(), "TNT lit by a Peaceful player cannot hurt another player (attribution through the TNT owner)");
		freshHit(normal);
		detonate(h, 1.5, 1.5, players.get(PersonalDifficultyMode.ELITE));
		check(normal.getHealth() < 20.0F, "control: TNT lit by a non-Peaceful player hurts the same victim (" + normal.getHealth() + ")");
		freshHit(normal);
		detonate(h, 1.5, 1.5, null);
		check(normal.getHealth() < 20.0F, "unowned TNT is ordinary: it hurts a non-Peaceful player (attribution is impossible, so nothing is blocked)");
		// 3) a wolf tamed by a Peaceful player cannot hurt other players; a wolf of a Normal player can
		Wolf wolf = h.spawnWithNoFreeWill(EntityTypes.WOLF, 2, 1, 2);
		wolf.tame(peaceful);
		normal.snapTo(wolf.getX() + 1, wolf.getY(), wolf.getZ());
		freshHit(normal);
		wolf.doHurtTarget(level, normal);
		near(20.0F, normal.getHealth(), "a wolf tamed by a Peaceful player cannot hurt another player");
		Wolf other = h.spawnWithNoFreeWill(EntityTypes.WOLF, 3, 1, 2);
		other.tame(players.get(PersonalDifficultyMode.ELITE));
		other.snapTo(normal.getX() - 1, normal.getY(), normal.getZ());
		freshHit(normal);
		other.doHurtTarget(level, normal);
		check(normal.getHealth() < 20.0F, "control: a wolf tamed by a non-Peaceful player hurts");
		// 4) ...and an unowned wolf-like attacker still cannot hurt a Peaceful victim
		freshHit(peaceful);
		other.snapTo(peaceful.getX() - 1, peaceful.getY(), peaceful.getZ());
		other.doHurtTarget(level, peaceful);
		near(20.0F, peaceful.getHealth(), "nothing hurts a Peaceful victim");
		guard.verify(level);
		h.succeed();
	}

	@GameTest
	public void peaceful_otherAttributedDamageSources_followTheResponsiblePlayer(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		ServerPlayer peaceful = player(h, "pd_pi_peace", PEACEFUL);
		ServerPlayer normal = player(h, "pd_pi_normal", NORMAL);
		ServerPlayer victim = player(h, "pd_pi_victim", NORMAL);
		AbstractArrow byPeaceful = ProjectileUtil.getMobArrow(peaceful, new ItemStack(Items.ARROW), 1.0F, null);
		AbstractArrow byNormal = ProjectileUtil.getMobArrow(normal, new ItemStack(Items.ARROW), 1.0F, null);
		AbstractArrow unowned = new net.minecraft.world.entity.projectile.arrow.Arrow(EntityTypes.ARROW, level);
		record Pair(String name, DamageSource fromPeaceful, DamageSource fromNormal) {
		}
		var s = level.damageSources();
		for (Pair c : List.of(
			new Pair("arrow", s.arrow(byPeaceful, peaceful), s.arrow(byNormal, normal)),
			new Pair("indirect magic (thrown potion / harming)", s.indirectMagic(byPeaceful, peaceful), s.indirectMagic(byNormal, normal)),
			new Pair("thrown", s.thrown(byPeaceful, peaceful), s.thrown(byNormal, normal)),
			new Pair("player attack", s.playerAttack(peaceful), s.playerAttack(normal)),
			new Pair("explosion caused by", s.explosion(byPeaceful, peaceful), s.explosion(byNormal, normal)),
			new Pair("mob projectile", s.mobProjectile(byPeaceful, peaceful), s.mobProjectile(byNormal, normal)))) {
			near(0.0F, lost(victim, c.fromPeaceful(), 4.0F), c.name() + ": blocked when the responsible player is Peaceful");
			check(lost(victim, c.fromNormal(), 4.0F) > 0.0F, c.name() + ": control - still hurts when the responsible player is not Peaceful");
		}
		// when attribution is impossible nothing can be blocked (documented limitation), but a Peaceful VICTIM is always safe
		near(4.0F, lost(victim, s.arrow(unowned, null), 4.0F), "an unowned arrow (dispenser-style) cannot be attributed, so it is ordinary damage to a non-Peaceful victim");
		near(0.0F, lost(peaceful, s.arrow(unowned, null), 4.0F), "...but never to a Peaceful victim");
		h.succeed();
	}

	// ------------------------------------------------------------------ effects

	private static MobEffectInstance harmful(Holder<MobEffect> e) {
		return new MobEffectInstance(e, 600, 0);
	}

	@GameTest
	public void peaceful_harmfulEffectsAreBlockedBothWays_beneficialAreNot(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		ServerPlayer peaceful = player(h, "pd_pe_peace", PEACEFUL);
		ServerPlayer normal = player(h, "pd_pe_normal", NORMAL);
		ServerPlayer bystander = player(h, "pd_pe_bystander", NORMAL);
		List<Holder<MobEffect>> bad = List.of(MobEffects.POISON, MobEffects.WITHER, MobEffects.SLOWNESS, MobEffects.WEAKNESS, MobEffects.BLINDNESS, MobEffects.NAUSEA,
			MobEffects.HUNGER, MobEffects.MINING_FATIGUE, MobEffects.DARKNESS, MobEffects.LEVITATION, MobEffects.INSTANT_DAMAGE);
		for (Holder<MobEffect> e : bad) {
			check(!peaceful.addEffect(harmful(e)), "harmful effect refused for a Peaceful victim: " + e.getRegisteredName());
			check(!peaceful.hasEffect(e), "not applied: " + e.getRegisteredName());
			check(normal.addEffect(harmful(e)), "control: accepted by a Normal player: " + e.getRegisteredName());
			normal.removeEffect(e);
		}
		for (Holder<MobEffect> e : List.of(MobEffects.SPEED, MobEffects.REGENERATION, MobEffects.STRENGTH, MobEffects.ABSORPTION, MobEffects.RESISTANCE, MobEffects.NIGHT_VISION)) {
			check(peaceful.addEffect(new MobEffectInstance(e, 600, 0)), "beneficial effect still accepted by Peaceful: " + e.getRegisteredName());
		}

		// attribution: a Peaceful player's own arrow / cloud / direct source cannot inflict harmful effects on another player
		AbstractArrow arrow = ProjectileUtil.getMobArrow(peaceful, new ItemStack(Items.ARROW), 1.0F, null);
		AreaEffectCloud cloud = new AreaEffectCloud(level, peaceful.getX(), peaceful.getY(), peaceful.getZ());
		cloud.setOwner(peaceful);
		check(!bystander.addEffect(harmful(MobEffects.POISON), peaceful), "direct source = Peaceful player");
		check(!bystander.addEffect(harmful(MobEffects.POISON), arrow), "source = arrow owned by a Peaceful player");
		check(!bystander.addEffect(harmful(MobEffects.POISON), cloud), "source = cloud owned by a Peaceful player");
		check(!bystander.hasEffect(MobEffects.POISON), "bystander is unaffected");
		// ...but ordinary PvP effects and unattributed ones are untouched (nothing is blocked beyond Peaceful)
		check(bystander.addEffect(harmful(MobEffects.POISON), normal), "source = a Normal player: ordinary PvP effect works");
		bystander.removeEffect(MobEffects.POISON);
		check(bystander.addEffect(harmful(MobEffects.SLOWNESS), null), "unattributed effect works on a non-Peaceful player");
		// a Peaceful player may still affect mobs
		Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 2, 1, 2);
		check(zombie.addEffect(harmful(MobEffects.SLOWNESS), peaceful), "a Peaceful player can still apply effects to mobs");
		h.succeed();
	}

	@GameTest
	public void peaceful_mobAttacksCannotInflictTheirHarmfulEffects(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		ServerPlayer peaceful = player(h, "pd_pf_peace", PEACEFUL);
		ServerPlayer control = player(h, "pd_pf_control", NORMAL);
		WitherSkeleton wither = h.spawnWithNoFreeWill(EntityTypes.WITHER_SKELETON, 2, 1, 2);
		CaveSpider spider = h.spawnWithNoFreeWill(EntityTypes.CAVE_SPIDER, 3, 1, 2);
		Husk husk = h.spawnWithNoFreeWill(EntityTypes.HUSK, 4, 1, 2);
		record Case(net.minecraft.world.entity.Mob mob, Holder<MobEffect> effect) {
		}
		for (Case c : List.of(new Case(wither, MobEffects.WITHER), new Case(spider, MobEffects.POISON), new Case(husk, MobEffects.HUNGER))) {
			for (ServerPlayer victim : List.of(peaceful, control)) {
				victim.removeAllEffects();
				freshHit(victim);
				c.mob().snapTo(victim.getX() - 1, victim.getY(), victim.getZ());
				c.mob().doHurtTarget(level, victim);
			}
			check(!peaceful.hasEffect(c.effect()), c.mob().getType().getDescriptionId() + " cannot inflict " + c.effect().getRegisteredName() + " on Peaceful");
			near(20.0F, peaceful.getHealth(), "no damage from " + c.mob().getType().getDescriptionId());
			check(control.hasEffect(c.effect()), "control: the same real attack inflicts " + c.effect().getRegisteredName() + " on a Normal player");
		}
		h.succeed();
	}

	@GameTest
	public void peaceful_enteringClearsHarmfulEffectsKeepsBeneficial_andLeavingResumesNormalDamage(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerLevel level = h.getLevel();
		Guard guard = Guard.take(level);
		floor(h);
		ServerPlayer p = player(h, "pd_pg_switch", NORMAL);
		Capture cap = new Capture();
		CommandSourceStack asPlayer = as(p, PermissionSet.NO_PERMISSIONS, cap);
		for (Holder<MobEffect> e : List.of(MobEffects.POISON, MobEffects.WITHER, MobEffects.SLOWNESS, MobEffects.HUNGER, MobEffects.BLINDNESS)) {
			p.addEffect(harmful(e));
		}
		p.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 6000, 1));
		p.addEffect(new MobEffectInstance(MobEffects.SPEED, 6000, 0));
		p.igniteForSeconds(30);
		p.setHealth(20.0F);
		tickPlayer(p, 45);
		check(p.getHealth() < 20.0F, "premise: poison/wither/fire really damage a Normal player (" + p.getHealth() + ")");

		Outcome enter = run(server, "personaldifficulty peaceful", asPlayer, cap);
		check(enter.result() == 1, "entered: " + enter);
		for (Holder<MobEffect> e : List.of(MobEffects.POISON, MobEffects.WITHER, MobEffects.SLOWNESS, MobEffects.HUNGER, MobEffects.BLINDNESS)) {
			check(!p.hasEffect(e), "harmful effect removed on entering Peaceful: " + e.getRegisteredName());
		}
		check(p.hasEffect(MobEffects.STRENGTH) && p.hasEffect(MobEffects.SPEED), "beneficial effects are not removed");
		check(!p.isOnFire(), "fire extinguished");
		p.setHealth(20.0F);
		tickPlayer(p, 100);
		near(20.0F, p.getHealth(), "no ordinary damage continues after entering Peaceful (100 real ticks)");

		run(server, "personaldifficulty normal", asPlayer, cap);
		check(p.addEffect(harmful(MobEffects.POISON)), "leaving Peaceful: harmful effects can be applied again immediately");
		tickPlayer(p, 45);
		check(p.getHealth() < 20.0F, "leaving Peaceful: poison damages again (" + p.getHealth() + ")");
		guard.verify(level);
		h.succeed();
	}

	// ------------------------------------------------------------------ hazards over real ticks

	@FunctionalInterface
	private interface Hazard {
		void setUp(GameTestHelper h, ServerPlayer p, BlockPos cell);
	}

	/** Runs a real hazard for {@code ticks} ticks on a Normal control and on a Peaceful player; returns control damage. */
	private static float exercise(GameTestHelper h, String name, int ticks, Hazard hazard, boolean mustHurtControl) {
		BlockPos cell = new BlockPos(4, 1, 4);
		float[] taken = new float[2];
		int i = 0;
		for (PersonalDifficultyMode mode : List.of(NORMAL, PEACEFUL)) {
			ServerPlayer p = player(h, "pd_hz_" + name + "_" + mode.commandName(), mode);
			Vec3 at = h.absoluteVec(new Vec3(4.5, 1.0, 4.5));
			p.snapTo(at.x, at.y, at.z);
			p.getFoodData().setFoodLevel(20);
			freshHit(p);
			hazard.setUp(h, p, cell);
			float minHealth = p.getHealth();
			for (int t = 0; t < ticks; t++) {
				if (forceStanding) {
					p.setPose(net.minecraft.world.entity.Pose.STANDING);
				}
				if (hazardMovement[0] != 0) {
					p.move(MoverType.SELF, new Vec3(hazardMovement[0] * ((t & 1) == 0 ? 1 : -1), 0, 0));
				}
				tickPlayer(p);
				minHealth = Math.min(minHealth, p.getHealth()); // natural regeneration would otherwise hide the damage
			}
			taken[i++] = 20.0F - minHealth;
			p.removeAllEffects();
			p.clearFire();
			p.setNoGravity(false);
		}
		for (int y = 1; y <= 3; y++) {
			for (int[] d : new int[][] {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
				h.setBlock(cell.getX() + d[0], y, cell.getZ() + d[1], Blocks.AIR);
			}
		}
		h.setBlock(cell.getX(), 0, cell.getZ(), Blocks.STONE);
		hazardMovement[0] = 0;
		forceStanding = false;
		System.out.printf("HAZARD %-14s normal lost %5.2f | peaceful lost %5.2f (%d ticks)%n", name, taken[0], taken[1], ticks);
		if (mustHurtControl) {
			check(taken[0] > 0.0F, name + ": premise - the hazard must really hurt a Normal player in this harness (lost " + taken[0] + ")");
		}
		check(taken[1] == 0.0F, name + ": Peaceful player lost " + taken[1]);
		return taken[0];
	}

	/** horizontal wobble per tick for hazards that need movement (berry bush) */
	private static final double[] hazardMovement = {0};

	/** vanilla auto-crawls a player whose head cell fills up; force the standing pose each tick to reach the real in_wall code path */
	private static boolean forceStanding;

	@GameTest(maxTicks = 400)
	public void peaceful_realEnvironmentalHazardsOverRealTicks(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		Guard guard = Guard.take(level);
		floor(h);
		exercise(h, "lava", 25, (hh, p, c) -> hh.setBlock(c, Blocks.LAVA), true);
		exercise(h, "burning", 45, (hh, p, c) -> p.igniteForSeconds(8), true);
		exercise(h, "cactus", 25, (hh, p, c) -> hh.setBlock(c, Blocks.CACTUS), true);
		exercise(h, "magma", 45, (hh, p, c) -> {
			hh.setBlock(c.getX(), 0, c.getZ(), Blocks.MAGMA_BLOCK);
			p.setShiftKeyDown(false);
		}, true);
		exercise(h, "berry-bush", 30, (hh, p, c) -> {
			hh.setBlock(c, Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE, 3));
			hazardMovement[0] = 0.04;
		}, true);
		exercise(h, "suffocation", 25, (hh, p, c) -> {
			// vanilla auto-crawls (and pushes out) a player whose head cell fills up, so wall in the head cell on all sides and
			// force the standing pose each tick: this models the first tick after a block lands on a standing player
			forceStanding = true;
			for (int[] d : new int[][] {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
				hh.setBlock(c.getX() + d[0], 2, c.getZ() + d[1], Blocks.STONE);
			}
		}, true);
		exercise(h, "drowning", 6, (hh, p, c) -> {
			hh.setBlock(c, Blocks.WATER);
			hh.setBlock(c.getX(), 2, c.getZ(), Blocks.WATER);
			hh.setBlock(c.getX(), 3, c.getZ(), Blocks.WATER);
			p.setAirSupply(-19);
		}, true);
		exercise(h, "powder-snow", 60, (hh, p, c) -> {
			hh.setBlock(c, Blocks.POWDER_SNOW);
			p.setTicksFrozen(p.getTicksRequiredToFreeze() + 20);
		}, true);
		exercise(h, "falling", 60, (hh, p, c) -> {
			Vec3 high = hh.absoluteVec(new Vec3(4.5, 7.0, 4.5));
			p.snapTo(high.x, high.y, high.z);
		}, true);
		guard.verify(level);
		h.succeed();
	}

	// ------------------------------------------------------------------ hunger

	@GameTest(maxTicks = 400)
	public void peaceful_hunger_neverDepletes_starvationCannotDamage_andEverythingResumesWhenLeaving(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerLevel level = h.getLevel();
		Guard guard = Guard.take(level);
		floor(h);
		Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 2, 1, 2);

		// 1) ordinary gameplay costs a Peaceful player no hunger, and does not touch food or saturation
		ServerPlayer p = player(h, "pd_ph_hunger", PEACEFUL);
		p.getFoodData().setFoodLevel(11);
		p.getFoodData().setSaturation(1.5F);
		setExhaustion(p, 0.0F);
		for (int i = 0; i < 300; i++) {
			p.setSprinting(true);
			p.jumpFromGround();
			zombie.damageCooldownTime = 0;
			zombie.setHealth(zombie.getMaxHealth());
			p.attack(zombie);
			p.causeFoodExhaustion(1.0F);
			p.getFoodData().tick(p);
		}
		check(p.getFoodData().getFoodLevel() == 11, "food unchanged after heavy activity: " + p.getFoodData().getFoodLevel());
		near(1.5F, p.getFoodData().getSaturationLevel(), "saturation unchanged (not refilled, not drained)");
		near(0.0F, exhaustion(p), "no exhaustion accrues at all");

		// 2) exhaustion banked BEFORE entering Peaceful can never be converted into food loss
		ServerPlayer banked = player(h, "pd_ph_banked", NORMAL);
		banked.getFoodData().setFoodLevel(11);
		banked.getFoodData().setSaturation(0.0F);
		setExhaustion(banked, 30.0F);
		setMode(banked, PEACEFUL);
		for (int i = 0; i < 20; i++) {
			banked.getFoodData().tick(banked);
		}
		check(banked.getFoodData().getFoodLevel() == 11, "banked exhaustion cannot drain food while Peaceful: " + banked.getFoodData().getFoodLevel());
		near(4.0F, exhaustion(banked), "banked exhaustion is capped at vanilla's conversion threshold, not discarded");
		// control: the same banked exhaustion does drain a Normal player
		ServerPlayer ctl = player(h, "pd_ph_bankedctl", NORMAL);
		ctl.getFoodData().setFoodLevel(11);
		ctl.getFoodData().setSaturation(0.0F);
		setExhaustion(ctl, 30.0F);
		for (int i = 0; i < 20; i++) {
			ctl.getFoodData().tick(ctl);
		}
		check(ctl.getFoodData().getFoodLevel() < 11, "control: banked exhaustion drains a Normal player (" + ctl.getFoodData().getFoodLevel() + ")");
		setMode(banked, NORMAL);
		banked.causeFoodExhaustion(1.0F);
		near(5.0F, exhaustion(banked), "leaving Peaceful: exhaustion accrues normally again (4.0 kept + 1.0)");

		// 3) a starving player entering Peaceful stops taking starvation damage; food/saturation untouched; leaving resumes it
		ServerPlayer starving = player(h, "pd_ph_starving", NORMAL);
		starving.getFoodData().setFoodLevel(0);
		starving.getFoodData().setSaturation(0.0F);
		starving.setHealth(20.0F);
		tickPlayer(starving, 170);
		float beforeSwitch = starving.getHealth();
		check(beforeSwitch < 20.0F, "premise: a Normal player at food 0 takes starvation damage (" + beforeSwitch + ")");
		Capture cap = new Capture();
		run(server, "personaldifficulty peaceful", as(starving, PermissionSet.NO_PERMISSIONS, cap), cap);
		starving.setHealth(beforeSwitch);
		tickPlayer(starving, 240);
		near(beforeSwitch, starving.getHealth(), "no starvation damage while Peaceful (240 ticks at food 0)");
		check(starving.getFoodData().getFoodLevel() == 0, "food level not refilled");
		near(0.0F, starving.getFoodData().getSaturationLevel(), "saturation not refilled");
		run(server, "personaldifficulty normal", as(starving, PermissionSet.NO_PERMISSIONS, cap), cap);
		tickPlayer(starving, 170);
		check(starving.getHealth() < beforeSwitch, "leaving Peaceful: starvation resumes (" + starving.getHealth() + ")");

		// 4) natural regeneration is vanilla for Peaceful (not 'free healing'), it just costs no hunger
		ServerPlayer regenP = player(h, "pd_ph_regenp", PEACEFUL);
		ServerPlayer regenN = player(h, "pd_ph_regenn", NORMAL);
		for (ServerPlayer r : List.of(regenP, regenN)) {
			r.getFoodData().setFoodLevel(20);
			r.getFoodData().setSaturation(5.0F);
			r.setHealth(10.0F);
			setExhaustion(r, 0.0F);
			for (int i = 0; i < 40; i++) {
				r.getFoodData().tick(r);
			}
		}
		check(regenP.getHealth() > 10.0F && regenN.getHealth() > 10.0F, "regeneration really happens for both");
		check(regenP.getHealth() >= regenN.getHealth(), "Peaceful never regenerates slower than vanilla");
		near(0.0F, exhaustion(regenP), "...at no hunger cost for Peaceful");
		check(exhaustion(regenN) > 0.0F, "...while it costs a Normal player exhaustion");
		near(5.0F, regenP.getFoodData().getSaturationLevel(), "so Peaceful's saturation is never spent");
		check(regenN.getFoodData().getSaturationLevel() < 5.0F, "(a Normal player's saturation is spent by healing)");
		// The regeneration RULES are untouched: after exactly one 10-tick cycle both have healed the same amount.
		ServerPlayer oneP = player(h, "pd_ph_onep", PEACEFUL);
		ServerPlayer oneN = player(h, "pd_ph_onen", NORMAL);
		for (ServerPlayer r : List.of(oneP, oneN)) {
			r.getFoodData().setFoodLevel(20);
			r.getFoodData().setSaturation(5.0F);
			r.setHealth(10.0F);
			setExhaustion(r, 0.0F);
			for (int i = 0; i < 10; i++) {
				r.getFoodData().tick(r);
			}
		}
		near(oneN.getHealth(), oneP.getHealth(), "one regeneration cycle heals exactly the vanilla amount");
		System.out.println("PEACEFUL-REGEN 40 ticks from 10 HP, food 20, saturation 5: normal " + regenN.getHealth() + " (saturation " + regenN.getFoodData().getSaturationLevel() + ") peaceful " + regenP.getHealth() + " (saturation " + regenP.getFoodData().getSaturationLevel() + ")");
		guard.verify(level);
		h.succeed();
	}

	// ------------------------------------------------------------------ transitions & lifecycle

	@GameTest
	public void peaceful_transitions_everyPairTakesEffectImmediately_andNothingGlobalOrPermanentChanges(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerLevel level = h.getLevel();
		Guard guard = Guard.take(level);
		ServerPlayer p = player(h, "pd_pt_trans", NORMAL);
		ServerPlayer bystander = player(h, "pd_pt_bystander", PersonalDifficultyMode.HARD);
		Capture cap = new Capture();
		CommandSourceStack asPlayer = as(p, PermissionSet.NO_PERMISSIONS, cap);
		double maxHealth = p.getAttributeValue(Attributes.MAX_HEALTH);
		double speed = p.getAttributeValue(Attributes.MOVEMENT_SPEED);
		double attack = p.getAttributeValue(Attributes.ATTACK_DAMAGE);
		double armor = p.getAttributeValue(Attributes.ARMOR);
		boolean mayFly = p.getAbilities().mayfly, instabuild = p.getAbilities().instabuild, invulnerable = p.getAbilities().invulnerable;
		Map<PersonalDifficultyMode, Float> expected = Map.of(NORMAL, 4.0F, PersonalDifficultyMode.EASY, 2.4F, PersonalDifficultyMode.HARD, 5.0F, PersonalDifficultyMode.ELITE, 8.0F, PEACEFUL, 0.0F);
		int transitions = 0;
		for (PersonalDifficultyMode from : PersonalDifficultyMode.values()) {
			for (PersonalDifficultyMode to : PersonalDifficultyMode.values()) {
				setMode(p, from);
				if (from != to) {
					Outcome o = run(server, "personaldifficulty " + to.commandName(), asPlayer, cap);
					check(o.result() == 1 && o.error() == null, from + " -> " + to + ": " + o);
				}
				check(rt().modeOf(p.getUUID()) == (from == to ? from : to), "mode after " + from + " -> " + to);
				near(expected.get(to), lost(p, level.damageSources().fall(), 4.0F), from + " -> " + to + " takes effect on the very next hit");
				check(p.getAttributeValue(Attributes.MAX_HEALTH) == maxHealth && p.getAttributeValue(Attributes.MOVEMENT_SPEED) == speed
					&& p.getAttributeValue(Attributes.ATTACK_DAMAGE) == attack && p.getAttributeValue(Attributes.ARMOR) == armor, "no attribute is ever modified (" + from + " -> " + to + ")");
				check(p.getAbilities().mayfly == mayFly && p.getAbilities().instabuild == instabuild && p.getAbilities().invulnerable == invulnerable, "abilities untouched: Peaceful is not creative mode");
				near(5.0F, lost(bystander, level.damageSources().fall(), 4.0F), "a bystander on HARD is never affected by anyone's switch");
				transitions++;
			}
		}
		check(transitions == 25, "all 25 transitions (incl. each mode to itself) exercised");
		guard.verify(level);
		h.succeed();
	}

	@GameTest
	public void peaceful_killAndVoidStillWork_noAdministrativeImmunity(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerLevel level = h.getLevel();
		ServerPlayer a = player(h, "pd_pk_kill", PEACEFUL);
		ServerPlayer b = player(h, "pd_pk_void", PEACEFUL);
		ServerPlayer c = player(h, "pd_pk_damagecmd", PEACEFUL);
		Capture cap = new Capture();
		ServerPlayer op = player(h, "pd_pk_op", NORMAL);
		// the real /kill command (selector argument needs level 2)
		Outcome kill = run(server, "kill pd_pk_kill", as(op, LevelBasedPermissionSet.GAMEMASTER, cap), cap);
		check(kill.error() == null && a.isDeadOrDying(), "/kill kills a Peaceful player: " + kill);
		// void damage
		b.damageCooldownTime = 0;
		b.hurtServer(level, level.damageSources().fellOutOfWorld(), 4.0F);
		near(16.0F, b.getHealth(), "void damage still hurts Peaceful");
		// /damage with an ordinary type is blocked (it is just damage), with generic_kill it is not
		freshHit(c);
		run(server, "damage pd_pk_damagecmd 5 minecraft:generic", as(op, LevelBasedPermissionSet.GAMEMASTER, cap), cap);
		near(20.0F, c.getHealth(), "/damage with an ordinary damage type is blocked like any other damage");
		freshHit(c);
		run(server, "damage pd_pk_damagecmd 5 minecraft:generic_kill", as(op, LevelBasedPermissionSet.GAMEMASTER, cap), cap);
		near(15.0F, c.getHealth(), "/damage with a bypassing type works");
		h.succeed();
	}

	@GameTest
	public void peaceful_deathRespawnAndReconnectPreserveTheSelection(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerLevel level = h.getLevel();
		Guard guard = Guard.take(level);
		ServerPlayer p = player(h, "pd_pl_life", PEACEFUL);
		UUID id = p.getUUID();
		freshHit(p);
		near(0.0F, lost(p, level.damageSources().fall(), 4.0F), "protected before");
		p.kill(level);
		check(p.isDeadOrDying(), "a Peaceful player can still be killed administratively");
		ServerPlayer respawned = server.getPlayerList().respawn(p, false, Entity.RemovalReason.KILLED);
		check(respawned.getUUID().equals(id) && rt().modeOf(id) == PEACEFUL, "mode survives death + respawn");
		respawned.setGameMode(GameType.SURVIVAL);
		respawned.getAbilities().invulnerable = false;
		freshHit(respawned);
		near(0.0F, lost(respawned, level.damageSources().fall(), 4.0F), "the respawned player is still protected");

		// reconnect: the entity is discarded, a new one joins with the same UUID
		server.getPlayerList().remove(respawned);
		ServerPlayer rejoined = placeNewPlayer(h, id, "pd_pl_life");
		check(rt().modeOf(id) == PEACEFUL, "mode survives disconnect + reconnect");
		near(0.0F, lost(rejoined, level.damageSources().fall(), 4.0F), "the reconnected player is protected at once");

		// switching away, then another full reconnect cycle, keeps the new selection too
		setMode(rejoined, PersonalDifficultyMode.ELITE);
		server.getPlayerList().remove(rejoined);
		ServerPlayer again = placeNewPlayer(h, id, "pd_pl_life");
		near(8.0F, lost(again, level.damageSources().fall(), 4.0F), "Elite also survives reconnect (x2)");
		guard.verify(level);
		h.succeed();
	}
}
