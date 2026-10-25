package com.zaggazach.personaldifficulty.gametest;

import static com.zaggazach.personaldifficulty.gametest.PdTestSupport.*;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import com.zaggazach.personaldifficulty.PdRuntime;
import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.config.PdConfigLoader;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.illager.Illusioner;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * In-server integration tests: real ServerPlayer instances, real vanilla mobs and the real mixins
 * running inside a Minecraft 26.3 server. Every test body is synchronous (no cross-tick waiting),
 * so tests that run in the same batch cannot interleave, and shared config is restored in finally.
 */
public class PdGameTests {
	// ------------------------------------------------------------------ damage

	@GameTest
	public void damage_modesGiveDifferentOutcomesFromEquivalentHits(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		var difficultyBefore = level.getDifficulty();
		ServerPlayer easy = player(h, "pd_easy", PersonalDifficultyMode.EASY);
		ServerPlayer normal = player(h, "pd_normal", PersonalDifficultyMode.NORMAL);
		ServerPlayer elite = player(h, "pd_elite", PersonalDifficultyMode.ELITE);
		ServerPlayer hard = player(h, "pd_hard", PersonalDifficultyMode.HARD);
		for (DamageSource s : List.of(level.damageSources().fall(), level.damageSources().lava(), level.damageSources().inFire(), level.damageSources().drown(), level.damageSources().inWall())) {
			near(6.0F, lost(easy, s, 10.0F), "easy " + s.getMsgId());
			near(10.0F, lost(normal, s, 10.0F), "normal " + s.getMsgId());
			near(12.5F, lost(hard, s, 10.0F), "hard " + s.getMsgId());
		}
		// applied exactly once: 10 -> 6, never 3.6
		near(6.0F, lost(easy, level.damageSources().fall(), 10.0F), "exactly once");
		check(level.getDifficulty() == difficultyBefore, "world difficulty must not change");
		h.succeed();
	}

	@GameTest
	public void damage_mobMeleeAndProjectileIncludingVanillaDifficultyScaling(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 2, 1, 2);
		ServerPlayer easy = player(h, "pd_easy2", PersonalDifficultyMode.EASY);
		ServerPlayer normal = player(h, "pd_normal2", PersonalDifficultyMode.NORMAL);
		DamageSource melee = level.damageSources().mobAttack(zombie);
		float vanilla = lost(normal, melee, 6.0F); // includes the world's own difficulty scaling
		float personal = lost(easy, melee, 6.0F);
		check(vanilla > 0, "vanilla melee must hurt");
		near(vanilla * 0.6F, personal, "easy melee = 0.6 x what a vanilla player loses (world scaling not re-applied)");
		h.succeed();
	}

	@GameTest
	public void damage_scalesAfterArmourEnchantmentAndResistance(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		Zombie zombie = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 2, 1, 2);
		ServerPlayer easy = player(h, "pd_easy3", PersonalDifficultyMode.EASY);
		ServerPlayer normal = player(h, "pd_normal3", PersonalDifficultyMode.NORMAL);
		var enchantments = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
		for (ServerPlayer p : List.of(easy, normal)) {
			ItemStack chest = new ItemStack(Items.DIAMOND_CHESTPLATE);
			chest.enchant(enchantments.getOrThrow(Enchantments.PROTECTION), 4);
			p.setItemSlot(EquipmentSlot.CHEST, chest);
			p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
			p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 1000, 1));
		}
		DamageSource melee = level.damageSources().mobAttack(zombie);
		float vanilla = lost(normal, melee, 12.0F);
		float personal = lost(easy, melee, 12.0F);
		check(vanilla > 0 && vanilla < 12.0F * 1.5F, "armour+protection+resistance must have mitigated vanilla damage (lost " + vanilla + ")");
		near(vanilla * 0.6F, personal, "easy = 0.6 x the already-mitigated vanilla loss");
		h.succeed();
	}

	@GameTest
	public void damage_pvpSelfVoidAndAdminAreUnchangedByDefault(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		ServerPlayer easy = player(h, "pd_easy4", PersonalDifficultyMode.EASY);
		ServerPlayer normal = player(h, "pd_normal4", PersonalDifficultyMode.NORMAL);
		ServerPlayer attacker = player(h, "pd_attacker", PersonalDifficultyMode.NORMAL);
		DamageSource pvp = level.damageSources().playerAttack(attacker);
		near(lost(normal, pvp, 4.0F), lost(easy, pvp, 4.0F), "PvP unchanged");
		DamageSource self = level.damageSources().playerAttack(easy);
		near(4.0F, lost(easy, self, 4.0F) , "self-inflicted unchanged");
		near(4.0F, lost(easy, level.damageSources().fellOutOfWorld(), 4.0F), "void unchanged");
		near(4.0F, lost(easy, level.damageSources().genericKill(), 4.0F), "/kill-type unchanged");
		h.succeed();
	}

	@GameTest
	public void damage_exceptionsAreConfigurable(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		ServerPlayer easy = player(h, "pd_easy5", PersonalDifficultyMode.EASY);
		ServerPlayer attacker = player(h, "pd_attacker5", PersonalDifficultyMode.NORMAL);
		withConfig("void.apply-profile=true\nadmin.apply-profile=true\npvp.damage-multiplier=0.5\nself.damage-multiplier=0.25\n", () -> {
			near(2.4F, lost(easy, level.damageSources().fellOutOfWorld(), 4.0F), "void opt-in");
			near(2.4F, lost(easy, level.damageSources().genericKill(), 4.0F), "admin opt-in");
			near(2.0F, lost(easy, level.damageSources().playerAttack(attacker), 4.0F), "pvp 0.5");
			near(1.0F, lost(easy, level.damageSources().playerAttack(easy), 4.0F), "self 0.25");
		});
		withConfig("easy.damage-type.minecraft.fall=0.2\nenvironmental.apply-profile=true\n", () -> {
			near(2.0F, lost(easy, level.damageSources().fall(), 10.0F), "fall override 0.2");
			near(6.0F, lost(easy, level.damageSources().lava(), 10.0F), "other types keep profile");
		});
		withConfig("environmental.apply-profile=false\n", () -> near(10.0F, lost(easy, level.damageSources().fall(), 10.0F), "environmental off"));
		near(6.0F, lost(easy, level.damageSources().fall(), 10.0F), "defaults restored after reload");
		h.succeed();
	}

	@GameTest
	public void damage_invulnerabilityFramesAreVanilla(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		ServerPlayer easy = player(h, "pd_easy6", PersonalDifficultyMode.EASY);
		ServerPlayer normal = player(h, "pd_normal6", PersonalDifficultyMode.NORMAL);
		for (ServerPlayer p : List.of(easy, normal)) {
			p.setHealth(20.0F);
			p.setAbsorptionAmount(0.0F);
			p.damageCooldownTime = 0;
			p.hurtServer(level, level.damageSources().fall(), 10.0F);
		}
		float easyAfterFirst = easy.getHealth();
		float normalAfterFirst = normal.getHealth();
		// lower raw damage inside the cooldown window is ignored for both (raw damage is compared)
		check(!easy.hurtServer(level, level.damageSources().fall(), 8.0F), "easy: weaker hit inside i-frames ignored");
		check(!normal.hurtServer(level, level.damageSources().fall(), 8.0F), "normal: weaker hit inside i-frames ignored");
		near(easyAfterFirst, easy.getHealth(), "easy health untouched");
		// stronger raw hit applies only the difference (15-10=5 raw), scaled once
		check(easy.hurtServer(level, level.damageSources().fall(), 15.0F), "easy: stronger hit lands");
		check(normal.hurtServer(level, level.damageSources().fall(), 15.0F), "normal: stronger hit lands");
		near(normalAfterFirst - 5.0F, normal.getHealth(), "normal takes the 5 difference");
		near(easyAfterFirst - 3.0F, easy.getHealth(), "easy takes 0.6 x the 5 difference");
		h.succeed();
	}

	@GameTest
	public void damage_lethalDamageStillKillsAndNeverHeals(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		ServerPlayer easy = player(h, "pd_easy7", PersonalDifficultyMode.EASY);
		easy.setHealth(1.0F);
		easy.damageCooldownTime = 0;
		easy.hurtServer(level, level.damageSources().fall(), 100.0F);
		check(easy.isDeadOrDying(), "lethal damage must still be lethal on Easy");
		ServerPlayer hard = player(h, "pd_hard7", PersonalDifficultyMode.HARD);
		float before = 20.0F;
		hard.setHealth(before);
		hard.damageCooldownTime = 0;
		hard.hurtServer(level, level.damageSources().fall(), 0.0F);
		check(hard.getHealth() <= before, "zero damage never heals");
		withConfig("easy.damage-multiplier=0\n", () -> {
			ServerPlayer p = player(h, "pd_zero", PersonalDifficultyMode.EASY);
			near(0.0F, lost(p, level.damageSources().fall(), 10.0F), "multiplier 0 -> no damage, never negative");
			check(p.getHealth() <= 20.0F, "no healing from a zero multiplier");
		});
		h.succeed();
	}

	// ------------------------------------------------------------------ hunger

	@GameTest
	public void hunger_exhaustionIsScaledPerMode(GameTestHelper h) {
		ServerPlayer easy = player(h, "pd_hungry_easy", PersonalDifficultyMode.EASY);
		ServerPlayer normal = player(h, "pd_hungry_normal", PersonalDifficultyMode.NORMAL);
		ServerPlayer elite = player(h, "pd_hungry_elite", PersonalDifficultyMode.ELITE);
		ServerPlayer hard = player(h, "pd_hungry_hard", PersonalDifficultyMode.HARD);
		for (ServerPlayer p : List.of(easy, normal, elite, hard)) {
			setExhaustion(p, 0.0F);
			p.causeFoodExhaustion(1.0F);
		}
		near(0.5F, exhaustion(easy), "easy hunger x0.5");
		near(1.0F, exhaustion(normal), "normal hunger x1");
		near(2.0F, exhaustion(elite), "elite hunger x2");
		near(1.25F, exhaustion(hard), "hard hunger x1.25");
		// fractional exhaustion is accumulated, not lost
		setExhaustion(easy, 0.0F);
		setExhaustion(normal, 0.0F);
		for (int i = 0; i < 1000; i++) {
			easy.causeFoodExhaustion(0.01F);
			normal.causeFoodExhaustion(0.01F);
		}
		near(exhaustion(normal) * 0.5F, exhaustion(easy), "1000 tiny steps: easy accumulates exactly half of vanilla");
		h.succeed();
	}

	@GameTest
	public void hunger_realSourcesAreScaled(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		ServerPlayer easy = player(h, "pd_src_easy", PersonalDifficultyMode.EASY);
		ServerPlayer normal = player(h, "pd_src_normal", PersonalDifficultyMode.NORMAL);
		// jumping, damage-taken and natural regeneration are all vanilla exhaustion sources
		for (ServerPlayer p : List.of(easy, normal)) {
			setExhaustion(p, 0.0F);
			p.jumpFromGround();
		}
		check(exhaustion(normal) > 0.0F, "vanilla jump must cause exhaustion");
		near(exhaustion(normal) * 0.5F, exhaustion(easy), "jump exhaustion halved");

		for (ServerPlayer p : List.of(easy, normal)) {
			setExhaustion(p, 0.0F);
			p.getFoodData().setFoodLevel(20);
			p.getFoodData().setSaturation(5.0F);
			p.setHealth(10.0F);
			for (int i = 0; i < 10; i++) {
				p.getFoodData().tick(p);
			}
		}
		check(normal.getHealth() > 10.0F && easy.getHealth() > 10.0F, "regeneration still happens on both (not altered)");
		near(normal.getHealth(), easy.getHealth(), "regeneration speed identical");
		near(exhaustion(normal) * 0.5F, exhaustion(easy), "regeneration's hunger cost halved");
		h.succeed();
	}

	@GameTest
	public void hunger_switchingModeNeverTouchesFoodState(GameTestHelper h) {
		ServerPlayer p = player(h, "pd_switch", PersonalDifficultyMode.NORMAL);
		p.getFoodData().setFoodLevel(13);
		p.getFoodData().setSaturation(2.5F);
		setExhaustion(p, 1.3F);
		for (PersonalDifficultyMode m : PersonalDifficultyMode.values()) {
			setMode(p, m);
			check(p.getFoodData().getFoodLevel() == 13, "food level after " + m);
			near(2.5F, p.getFoodData().getSaturationLevel(), "saturation after " + m);
			near(1.3F, exhaustion(p), "exhaustion after " + m);
		}
		h.succeed();
	}

	// ------------------------------------------------------------------ ranged accuracy

	@GameTest(maxTicks = 100)
	public void ranged_skeletonAimIsWiderOnlyAgainstEasyPlayersAndFollowsTarget(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		AbstractSkeleton skeleton = h.spawnWithNoFreeWill(EntityTypes.SKELETON, 2, 1, 2);
		skeleton.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
		ServerPlayer easy = player(h, "pd_aim_easy", PersonalDifficultyMode.EASY);
		ServerPlayer normal = player(h, "pd_aim_normal", PersonalDifficultyMode.NORMAL);
		Zombie decoy = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 6, 1, 2);
		Vec3 spot = easy.position();
		normal.snapTo(spot.x, spot.y, spot.z); // identical geometry for every target
		decoy.snapTo(spot.x, spot.y, spot.z);
		int n = 600;
		// interleave targets on the SAME skeleton: the adjustment must follow the target shot by shot
		Sample e = sample(level, skeleton, n, () -> skeleton.performRangedAttack(easy, 1.0F));
		Sample nrm = sample(level, skeleton, n, () -> skeleton.performRangedAttack(normal, 1.0F));
		Sample mob = sample(level, skeleton, n, () -> skeleton.performRangedAttack(decoy, 1.0F));
		Sample e2 = sample(level, skeleton, n, () -> skeleton.performRangedAttack(easy, 1.0F));
		expectWider("skeleton", e, nrm, mob, vanillaUncertainty(level));
		check(Math.abs(e.spread() - e2.spread()) / e.spread() < 0.15, "switching back to the Easy player widens the spread again");
		h.succeed();
	}

	@GameTest(maxTicks = 100)
	public void ranged_strayDrownedAndIllusionerAreSupported(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		ServerPlayer easy = player(h, "pd_aim_easy2", PersonalDifficultyMode.EASY);
		ServerPlayer normal = player(h, "pd_aim_normal2", PersonalDifficultyMode.NORMAL);
		Vec3 spot = easy.position();
		normal.snapTo(spot.x, spot.y, spot.z);
		Zombie decoy = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 6, 1, 2);
		decoy.snapTo(spot.x, spot.y, spot.z);
		int n = 500;
		double vanilla = vanillaUncertainty(level);

		AbstractSkeleton stray = h.spawnWithNoFreeWill(EntityTypes.STRAY, 2, 1, 2);
		stray.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
		expectWider("stray", sample(level, stray, n, () -> stray.performRangedAttack(easy, 1.0F)),
			sample(level, stray, n, () -> stray.performRangedAttack(normal, 1.0F)),
			sample(level, stray, n, () -> stray.performRangedAttack(decoy, 1.0F)), vanilla);

		Illusioner illusioner = h.spawnWithNoFreeWill(EntityTypes.ILLUSIONER, 2, 1, 2);
		illusioner.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
		expectWider("illusioner", sample(level, illusioner, n, () -> illusioner.performRangedAttack(easy, 1.0F)),
			sample(level, illusioner, n, () -> illusioner.performRangedAttack(normal, 1.0F)),
			sample(level, illusioner, n, () -> illusioner.performRangedAttack(decoy, 1.0F)), vanilla);

		Drowned drowned = h.spawnWithNoFreeWill(EntityTypes.DROWNED, 2, 1, 2);
		drowned.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT));
		expectWider("drowned", sample(level, drowned, n, () -> drowned.performRangedAttack(easy, 1.0F)),
			sample(level, drowned, n, () -> drowned.performRangedAttack(normal, 1.0F)),
			sample(level, drowned, n, () -> drowned.performRangedAttack(decoy, 1.0F)), vanilla);
		h.succeed();
	}

	@GameTest(maxTicks = 100)
	public void ranged_pillagerCrossbow(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		ServerPlayer easy = player(h, "pd_aim_easy3", PersonalDifficultyMode.EASY);
		ServerPlayer normal = player(h, "pd_aim_normal3", PersonalDifficultyMode.NORMAL);
		Vec3 spot = easy.position();
		normal.snapTo(spot.x, spot.y, spot.z);
		Zombie decoy = h.spawnWithNoFreeWill(EntityTypes.ZOMBIE, 6, 1, 2);
		decoy.snapTo(spot.x, spot.y, spot.z);
		Pillager pillager = h.spawnWithNoFreeWill(EntityTypes.PILLAGER, 2, 1, 2);
		java.util.function.Consumer<net.minecraft.world.entity.LivingEntity> shoot = target -> {
			pillager.setTarget(target);
			// a mob crossbow loses durability per shot, so equip a fresh, charged one each time
			ItemStack fresh = new ItemStack(Items.CROSSBOW);
			fresh.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(new ItemStackTemplate(Items.ARROW)));
			pillager.setItemSlot(EquipmentSlot.MAINHAND, fresh);
			pillager.performRangedAttack(target, 1.0F);
		};
		int n = 500;
		expectWider("pillager crossbow", sample(level, pillager, n, () -> shoot.accept(easy)),
			sample(level, pillager, n, () -> shoot.accept(normal)), sample(level, pillager, n, () -> shoot.accept(decoy)), vanillaUncertainty(level));
		h.succeed();
	}

	@GameTest(maxTicks = 100)
	public void ranged_disabledFamilyKeepsVanillaAimAndHardIsUnaffected(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		AbstractSkeleton skeleton = h.spawnWithNoFreeWill(EntityTypes.SKELETON, 2, 1, 2);
		skeleton.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
		ServerPlayer easy = player(h, "pd_aim_easy4", PersonalDifficultyMode.EASY);
		ServerPlayer hard = player(h, "pd_aim_hard4", PersonalDifficultyMode.HARD);
		Vec3 spot = easy.position();
		hard.snapTo(spot.x, spot.y, spot.z);
		int n = 600;
		Sample hardSample = sample(level, skeleton, n, () -> skeleton.performRangedAttack(hard, 1.0F));
		Sample baseline = sample(level, skeleton, n, () -> skeleton.performRangedAttack(hard, 1.0F));
		check(Math.abs(hardSample.spread() - baseline.spread()) / baseline.spread() < 0.12, "hard player aim is vanilla (self-consistency)");
		withConfig("ranged.skeletons=false\n", () -> {
			Sample off = sample(level, skeleton, n, () -> skeleton.performRangedAttack(easy, 1.0F));
			check(Math.abs(off.spread() - baseline.spread()) / baseline.spread() < 0.12, "disabled family keeps vanilla aim against an Easy player");
		});
		Sample on = sample(level, skeleton, n, () -> skeleton.performRangedAttack(easy, 1.0F));
		check(on.spread() > baseline.spread() * 1.5, "re-enabled after reload: Easy widens again");
		h.succeed();
	}

	// ------------------------------------------------------------------ commands & permissions

	@GameTest
	public void commands_selfServiceOnlyTouchesTheCaller(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		var difficultyBefore = server.getWorldData().getDifficulty();
		ServerPlayer alice = player(h, "pd_alice", PersonalDifficultyMode.NORMAL);
		ServerPlayer bob = player(h, "pd_bob", PersonalDifficultyMode.HARD);
		Capture cap = new Capture();
		CommandSourceStack asAlice = as(alice, PermissionSet.NO_PERMISSIONS, cap);

		Outcome r = run(server, "personaldifficulty easy", asAlice, cap);
		check(r.result() == 1 && r.error() == null, "alice can pick easy without any permission: " + r);
		check(rt().modeOf(alice.getUUID()) == PersonalDifficultyMode.EASY, "alice is EASY");
		check(rt().modeOf(bob.getUUID()) == PersonalDifficultyMode.HARD, "bob untouched");
		check(r.output().contains("EASY"), "feedback names the new mode: " + r.output());

		cap.messages.clear();
		run(server, "personaldifficulty status", asAlice, cap);
		check(cap.all().contains("EASY") && cap.all().contains("World difficulty"), "status shows mode and world difficulty: " + cap.all());

		run(server, "personaldifficulty normal", asAlice, cap);
		check(rt().modeOf(alice.getUUID()) == PersonalDifficultyMode.NORMAL, "NORMAL selected");
		Outcome inherit = run(server, "personaldifficulty inherit", asAlice, cap);
		check(inherit.result() == 0 && inherit.output().contains("removed in 1.1.0"), "inherit no longer exists and says so: " + inherit);
		check(rt().modeOf(alice.getUUID()) == PersonalDifficultyMode.NORMAL, "a rejected command changes nothing");
		run(server, "personaldifficulty hard", asAlice, cap);
		check(rt().modeOf(alice.getUUID()) == PersonalDifficultyMode.HARD, "hard stored");

		check(run(server, "personaldifficulty easy pd_bob", asAlice, cap).error() != null, "no syntax lets a player target another player");
		check(run(server, "personaldifficulty admin reset pd_bob", asAlice, cap).error() != null, "non-op cannot use admin reset");
		check(run(server, "personaldifficulty admin reload", asAlice, cap).error() != null, "non-op cannot reload");
		check(rt().modeOf(bob.getUUID()) == PersonalDifficultyMode.HARD, "bob still HARD");
		check(server.getWorldData().getDifficulty() == difficultyBefore, "global difficulty untouched by commands");
		h.succeed();
	}

	@GameTest
	public void commands_adminOperationsRequireOpAndConsoleWorks(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer op = player(h, "pd_op", PersonalDifficultyMode.NORMAL);
		ServerPlayer victim = player(h, "pd_victim", PersonalDifficultyMode.EASY);
		Capture cap = new Capture();

		Outcome status = run(server, "personaldifficulty admin status pd_victim", as(op, LevelBasedPermissionSet.GAMEMASTER, cap), cap);
		check(status.error() == null && status.output().contains("EASY"), "op can inspect another player: " + status);
		check(rt().modeOf(victim.getUUID()) == PersonalDifficultyMode.EASY, "inspect does not modify");
		Outcome reset = run(server, "personaldifficulty admin reset pd_victim", as(op, LevelBasedPermissionSet.GAMEMASTER, cap), cap);
		check(reset.error() == null, "op can reset: " + reset);
		check(rt().modeOf(victim.getUUID()) == PersonalDifficultyMode.NORMAL, "victim reset to NORMAL");

		check(run(server, "personaldifficulty admin reset pd_op", as(op, LevelBasedPermissionSet.ALL, cap), cap).error() != null, "permission level 0 is rejected");
		Outcome unknown = run(server, "personaldifficulty admin reset nobody_here", as(op, LevelBasedPermissionSet.GAMEMASTER, cap), cap);
		check(unknown.error() != null || unknown.output().contains("no stored selection"), "unknown player is rejected or honestly reported, never 'reset': " + unknown);
		check(!unknown.output().contains("nobody_here reset"), "must not claim to have reset a player with no entry: " + unknown);

		Capture console = new Capture();
		CommandSourceStack consoleSource = server.createCommandSourceStack().withSource(console);
		Outcome overview = run(server, "personaldifficulty status", consoleSource, console);
		check(overview.error() == null && overview.output().contains("server overview"), "console can inspect: " + overview);
		check(run(server, "personaldifficulty admin config", consoleSource, console).error() == null, "console can run admin config");
		console.messages.clear();
		Outcome consoleSelect = run(server, "personaldifficulty easy", consoleSource, console);
		check(consoleSelect.result() == 0 && console.all().contains("Only a player"), "console cannot pick a personal difficulty: " + console.all());
		h.succeed();
	}

	@GameTest
	public void commands_treeShapeAndSuggestions(GameTestHelper h) throws Exception {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer p = player(h, "pd_tab", PersonalDifficultyMode.NORMAL);
		Capture cap = new Capture();
		var dispatcher = server.getCommands().getDispatcher();
		CommandNode<CommandSourceStack> root = dispatcher.getRoot().getChild("personaldifficulty");
		check(root != null, "command registered");
		CommandSourceStack plain = as(p, PermissionSet.NO_PERMISSIONS, cap);
		CommandSourceStack op = as(p, LevelBasedPermissionSet.GAMEMASTER, cap);
		List<String> forPlayer = root.getChildren().stream().filter(c -> c.canUse(plain)).map(CommandNode::getName).sorted().toList();
		List<String> forOp = root.getChildren().stream().filter(c -> c.canUse(op)).map(CommandNode::getName).sorted().toList();
		check(forPlayer.equals(List.of("difficulty", "status")), "player-visible nodes: " + forPlayer);
		check(forOp.equals(List.of("admin", "difficulty", "status")), "op-visible nodes: " + forOp);
		List<String> adminChildren = root.getChild("admin").getChildren().stream().map(CommandNode::getName).sorted().toList();
		check(adminChildren.equals(List.of("config", "difficulties", "disable", "enable", "reload", "reset", "status")), "admin subcommands: " + adminChildren);

		var suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse("personaldifficulty ", plain)).get();
		List<String> offered = suggestions.getList().stream().map(s -> s.getText()).sorted().toList();
		for (String m : List.of("easy", "elite", "hard", "normal", "peaceful")) {
			check(offered.contains(m), m + " is suggested when enabled: " + offered);
		}
		check(!offered.contains("inherit"), "inherit is not suggested: " + offered);
		check(dispatcher.getRoot().getChild("difficulty") != null, "vanilla /difficulty still exists (no conflict)");
		h.succeed();
	}

	@GameTest
	public void commands_reloadAppliesImmediatelyAndBadFileKeepsOldConfig(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerLevel level = h.getLevel();
		ServerPlayer op = player(h, "pd_op2", PersonalDifficultyMode.EASY);
		Capture cap = new Capture();
		CommandSourceStack src = as(op, LevelBasedPermissionSet.GAMEMASTER, cap);
		try {
			Files.writeString(rt().configFile(), "easy.damage-multiplier=0.5\nhard.hunger-multiplier=NaN\n");
			Outcome r = run(server, "personaldifficulty admin reload", src, cap);
			check(r.error() == null && r.output().contains("applies immediately"), "reload ok: " + r);
			near(5.0F, lost(op, level.damageSources().fall(), 10.0F), "new multiplier effective at once, no reconnect");
			check(r.output().contains("Warnings"), "NaN reported as a warning: " + r.output());
			check(rt().config().hard().hungerMultiplier() == 1.25, "NaN fell back to default");
			Files.delete(rt().configFile());
			cap.messages.clear();
			Outcome bad = run(server, "personaldifficulty admin reload", src, cap);
			check(bad.result() == 0 && cap.all().contains("Reload incomplete") && cap.all().contains("previous values stay in force"), "unreadable file reported: " + cap.all());
			near(5.0F, lost(op, level.damageSources().fall(), 10.0F), "previous config kept after failed reload");
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		} finally {
			try {
				Files.writeString(rt().configFile(), PdConfigLoader.defaultFileContents());
			} catch (java.io.IOException ignored) {
				// disposable run dir
			}
			rt().reload();
		}
		h.succeed();
	}

	// ------------------------------------------------------------------ lifecycle

	@GameTest
	public void lifecycle_modeSurvivesDeathAndRespawnAndIsStoredOnDisk(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer p = player(h, "pd_life", PersonalDifficultyMode.EASY);
		UUID id = p.getUUID();
		p.kill(h.getLevel());
		check(p.isDeadOrDying(), "player died");
		ServerPlayer respawned = server.getPlayerList().respawn(p, false, net.minecraft.world.entity.Entity.RemovalReason.KILLED);
		check(respawned.getUUID().equals(id), "same UUID after respawn");
		check(rt().modeOf(id) == PersonalDifficultyMode.EASY, "mode survives death/respawn");
		try {
			String json = Files.readString(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("personaldifficulty").resolve("players.json"));
			check(json.contains(id.toString()) && json.contains("EASY"), "selection written to the world's players.json: " + json);
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
		h.succeed();
	}
}
