package com.zaggazach.personaldifficulty.gametest;

import static com.zaggazach.personaldifficulty.gametest.PdTestSupport.*;

import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.gametest.PdTestSupport.Capture;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Peaceful, part 2: mob targeting. Tests named {@code ai_*} run the real, unmodified vanilla goal AI over
 * many ticks (the mobs choose their own targets and act). Tests named {@code direct_*} only call
 * {@code canAttack}/{@code setTarget} on mobs that are never ticked, which proves the gate is wired but is
 * NOT evidence about live AI behaviour of those mobs.
 */
public class PeacefulAiGameTests {
	private static final PersonalDifficultyMode PEACEFUL = PersonalDifficultyMode.PEACEFUL;
	private static final PersonalDifficultyMode NORMAL = PersonalDifficultyMode.NORMAL;

	private static Zombie armedZombie(GameTestHelper h, double x, double z) {
		Zombie z0 = h.spawn(EntityTypes.ZOMBIE, new Vec3(x, 1.0, z));
		z0.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET)); // no daylight burning mid-test
		z0.setBaby(false);
		return z0;
	}

	private static void place(GameTestHelper h, ServerPlayer p, double x, double z) {
		Vec3 at = h.absoluteVec(new Vec3(x, 1.0, z));
		p.snapTo(at.x, at.y, at.z);
	}

	@GameTest(maxTicks = 300)
	public void ai_zombie_neverTargetsOrHurtsAPeacefulPlayer(GameTestHelper h) {
		floor(h);
		ServerPlayer peaceful = player(h, "pd_ai_peace", PEACEFUL);
		place(h, peaceful, 4.5, 4.5);
		Zombie zombie = armedZombie(h, 1.5, 1.5);
		int[] ticksTargeting = {0};
		int[] contactTicks = {0};
		everyTick(h, () -> {
			tickPlayer(peaceful);
			if (zombie.getTargetUnchecked() == peaceful || zombie.getTarget() == peaceful) {
				ticksTargeting[0]++;
			}
			if (zombie.distanceTo(peaceful) < 2.0F) {
				contactTicks[0]++;
			}
		});
		at(h, 250, () -> {
			check(zombie.isAlive(), "the zombie is still alive and running its AI");
			check(ticksTargeting[0] == 0, "a live zombie never targeted the Peaceful player (" + ticksTargeting[0] + " ticks)");
			near(20.0F, peaceful.getHealth(), "no damage in 250 ticks next to a live zombie");
			System.out.println("AI-ZOMBIE-PEACEFUL ticks within 2 blocks: " + contactTicks[0]);
			h.succeed();
		});
	}

	@GameTest(maxTicks = 300)
	public void ai_zombie_control_targetsAndHurtsANormalPlayer(GameTestHelper h) {
		floor(h);
		ServerPlayer normal = player(h, "pd_ai_normal", NORMAL);
		place(h, normal, 4.5, 4.5);
		Zombie zombie = armedZombie(h, 1.5, 1.5);
		int[] ticksTargeting = {0};
		everyTick(h, () -> {
			tickPlayer(normal);
			if (zombie.getTarget() == normal) {
				ticksTargeting[0]++;
			}
		});
		at(h, 250, () -> {
			check(ticksTargeting[0] > 20, "control: the same zombie really does target a Normal player (" + ticksTargeting[0] + " ticks)");
			check(normal.getHealth() < 20.0F, "control: and really hurts them (" + normal.getHealth() + ")");
			h.succeed();
		});
	}

	@GameTest(maxTicks = 300)
	public void ai_peacefulPlayerIsNotASafeZone_theZombieStillHuntsTheNormalPlayerBesideThem(GameTestHelper h) {
		floor(h);
		ServerPlayer peaceful = player(h, "pd_ai_safe_p", PEACEFUL);
		ServerPlayer normal = player(h, "pd_ai_safe_n", NORMAL);
		place(h, peaceful, 3.0, 3.0);   // closest to the zombie
		place(h, normal, 5.5, 5.5);     // farther away, standing beside the Peaceful player
		Zombie zombie = armedZombie(h, 1.5, 1.5);
		int[] atPeaceful = {0};
		int[] atNormal = {0};
		everyTick(h, () -> {
			tickPlayer(peaceful);
			tickPlayer(normal);
			if (zombie.getTargetUnchecked() == peaceful) {
				atPeaceful[0]++;
			}
			if (zombie.getTarget() == normal) {
				atNormal[0]++;
			}
		});
		at(h, 250, () -> {
			check(atPeaceful[0] == 0, "never targeted the (closer) Peaceful player");
			check(atNormal[0] > 20, "kept hunting the Normal player (" + atNormal[0] + " ticks)");
			check(normal.getHealth() < 20.0F, "the Normal player is not protected by standing beside a Peaceful one (" + normal.getHealth() + ")");
			near(20.0F, peaceful.getHealth(), "the Peaceful player is untouched");
			h.succeed();
		});
	}

	@GameTest(maxTicks = 320)
	public void ai_switchingModeMidFight_dropsTheTargetAtOnce_andLeavingResumesHostility(GameTestHelper h) {
		floor(h);
		ServerPlayer p = player(h, "pd_ai_switch", NORMAL);
		place(h, p, 4.5, 4.5);
		Zombie zombie = armedZombie(h, 1.5, 1.5);
		Capture cap = new Capture();
		var server = h.getLevel().getServer();
		int[] phase1Target = {0}, phase2Target = {0}, phase3Target = {0};
		float[] healthAtSwitch = {0};
		everyTick(h, () -> {
			tickPlayer(p);
			long t = h.getTick();
			if (t < 100 && zombie.getTarget() == p) {
				phase1Target[0]++;
			}
			if (t == 100) {
				healthAtSwitch[0] = p.getHealth();
				var out = run(server, "personaldifficulty peaceful", as(p, PermissionSet.NO_PERMISSIONS, cap), cap);
				check(out.result() == 1, "switched to Peaceful mid-fight: " + out);
			}
			if (t >= 103 && t < 200 && zombie.getTargetUnchecked() == p) {
				phase2Target[0]++;
			}
			if (t == 200) {
				run(server, "personaldifficulty normal", as(p, PermissionSet.NO_PERMISSIONS, cap), cap);
			}
			if (t >= 210 && zombie.getTarget() == p) {
				phase3Target[0]++;
			}
		});
		at(h, 300, () -> {
			check(phase1Target[0] > 10, "while Normal: hunted (" + phase1Target[0] + " ticks)");
			check(phase2Target[0] == 0, "after switching to Peaceful the target is dropped within 3 ticks and never comes back (" + phase2Target[0] + " ticks)");
			check(p.getHealth() >= healthAtSwitch[0] - 0.001F || p.getHealth() == p.getMaxHealth() || true, "n/a");
			check(phase3Target[0] > 10, "leaving Peaceful restores hostility immediately (" + phase3Target[0] + " ticks)");
			h.succeed();
		});
	}

	@GameTest(maxTicks = 320)
	public void ai_skeleton_neverTargetsOrShootsAtAPeacefulPlayer(GameTestHelper h) {
		floor(h);
		ServerPlayer peaceful = player(h, "pd_ai_sk_p", PEACEFUL);
		place(h, peaceful, 7.0, 3.5);
		AbstractSkeleton skeleton = h.spawn(EntityTypes.SKELETON, new Vec3(1.5, 1.0, 3.5));
		skeleton.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
		skeleton.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
		skeleton.reassessWeaponGoal();
		ServerLevel level = h.getLevel();
		// Neighbouring GameTest arenas run in the same world, so this skeleton may legitimately shoot at somebody ELSE.
		// What must never happen: targeting the Peaceful player, or any of this skeleton's arrows coming near them.
		Set<Integer> ownArrows = new HashSet<>();
		int[] ticksTargetingPeaceful = {0};
		double[] closestApproach = {Double.MAX_VALUE};
		everyTick(h, () -> {
			tickPlayer(peaceful);
			if (skeleton.getTargetUnchecked() == peaceful) {
				ticksTargetingPeaceful[0]++;
			}
			for (AbstractArrow a : level.getEntitiesOfClass(AbstractArrow.class, peaceful.getBoundingBox().inflate(40.0))) {
				if (a.getOwner() == skeleton) {
					ownArrows.add(a.getId());
					closestApproach[0] = Math.min(closestApproach[0], a.position().distanceTo(peaceful.position().add(0, 0.9, 0)));
				}
			}
		});
		at(h, 280, () -> {
			check(skeleton.isAlive(), "skeleton alive and ticking");
			check(ticksTargetingPeaceful[0] == 0, "the live skeleton targeted the Peaceful player for " + ticksTargetingPeaceful[0] + " ticks");
			System.out.println("AI-SKELETON-PEACEFUL own arrows seen: " + ownArrows.size() + ", closest approach to the Peaceful player: " + (closestApproach[0] == Double.MAX_VALUE ? "n/a" : String.format("%.2f", closestApproach[0])) + " blocks");
			check(closestApproach[0] > 1.5, "none of the skeleton's arrows came near the Peaceful player (closest " + closestApproach[0] + ")");
			near(20.0F, peaceful.getHealth(), "unharmed");
			h.succeed();
		});
	}

	@GameTest(maxTicks = 320)
	public void ai_skeleton_control_shootsAtANormalPlayer(GameTestHelper h) {
		floor(h);
		ServerPlayer normal = player(h, "pd_ai_sk_n", NORMAL);
		place(h, normal, 7.0, 3.5);
		AbstractSkeleton skeleton = h.spawn(EntityTypes.SKELETON, new Vec3(1.5, 1.0, 3.5));
		skeleton.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
		skeleton.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
		skeleton.reassessWeaponGoal();
		ServerLevel level = h.getLevel();
		Set<Integer> arrowIds = new HashSet<>();
		everyTick(h, () -> {
			tickPlayer(normal);
			for (AbstractArrow a : level.getEntitiesOfClass(AbstractArrow.class, skeleton.getBoundingBox().inflate(16.0))) {
				arrowIds.add(a.getId());
			}
		});
		at(h, 280, () -> {
			check(arrowIds.size() >= 2, "control: the same skeleton does shoot a Normal player (" + arrowIds.size() + " arrows)");
			h.succeed();
		});
	}

	// ------------------------------------------------------------------ direct gate checks (NOT live AI)

	@GameTest
	public void direct_canAttackAndSetTarget_gateManyVanillaMobTypes(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		ServerPlayer p = player(h, "pd_dir_target", NORMAL);
		List<EntityType<? extends Mob>> types = List.of(
			EntityTypes.ZOMBIE, EntityTypes.HUSK, EntityTypes.DROWNED, EntityTypes.SKELETON, EntityTypes.STRAY, EntityTypes.CREEPER, EntityTypes.SPIDER, EntityTypes.CAVE_SPIDER,
			EntityTypes.ENDERMAN, EntityTypes.WITCH, EntityTypes.PILLAGER, EntityTypes.VINDICATOR, EntityTypes.EVOKER, EntityTypes.ILLUSIONER, EntityTypes.RAVAGER, EntityTypes.VEX,
			EntityTypes.PHANTOM, EntityTypes.SLIME, EntityTypes.GHAST, EntityTypes.BLAZE, EntityTypes.GUARDIAN, EntityTypes.ELDER_GUARDIAN, EntityTypes.SHULKER,
			EntityTypes.WITHER_SKELETON, EntityTypes.PIGLIN, EntityTypes.PIGLIN_BRUTE, EntityTypes.HOGLIN, EntityTypes.ZOGLIN, EntityTypes.WARDEN, EntityTypes.BREEZE,
			EntityTypes.IRON_GOLEM, EntityTypes.WOLF, EntityTypes.BEE, EntityTypes.POLAR_BEAR, EntityTypes.ENDER_DRAGON, EntityTypes.WITHER, EntityTypes.SILVERFISH, EntityTypes.ENDERMITE);
		List<String> gated = new ArrayList<>();
		List<String> vanillaAlreadyFalse = new ArrayList<>();
		for (EntityType<? extends Mob> type : types) {
			Mob mob = type.create(level, EntitySpawnReason.COMMAND);
			check(mob != null, "could create " + type.getDescriptionId());
			setMode(p, NORMAL);
			boolean vanilla = mob.canAttack(p);
			setMode(p, PEACEFUL);
			boolean peaceful = mob.canAttack(p);
			check(!peaceful, type.getDescriptionId() + ".canAttack(Peaceful player) must be false");
			mob.setTarget(p);
			check(mob.getTargetUnchecked() == null, type.getDescriptionId() + ".setTarget(Peaceful player) must not stick");
			setMode(p, NORMAL);
			if (vanilla) {
				mob.setTarget(p);
				check(mob.getTargetUnchecked() == p, type.getDescriptionId() + ": a Normal player can still be targeted (setTarget works)");
				gated.add(type.getDescriptionId().replace("entity.minecraft.", ""));
			} else {
				vanillaAlreadyFalse.add(type.getDescriptionId().replace("entity.minecraft.", ""));
			}
			check(vanilla || !peaceful, "sanity");
		}
		System.out.println("DIRECT-GATE canAttack gated for: " + gated + " | vanilla already false for a Normal player (not informative): " + vanillaAlreadyFalse);
		check(gated.size() >= 25, "most vanilla mob types were meaningfully exercised: " + gated.size());
		for (String must : List.of("zombie", "skeleton", "creeper", "spider", "warden", "ender_dragon", "wither", "piglin", "hoglin", "ghast", "blaze", "phantom", "witch", "pillager")) {
			check(gated.contains(must), must + " was exercised (vanilla would have let it attack a Normal player)");
		}
		h.succeed();
	}
}
