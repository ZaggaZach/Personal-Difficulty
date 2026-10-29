package com.zaggazach.personaldifficulty.gametest;

import static com.zaggazach.personaldifficulty.gametest.PdTestSupport.*;

import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.gametest.EliteAiGameTests.Arena;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Elite target priority (1.3.0). {@code ai_*} tests run the live, unmodified vanilla goal AI over many ticks; what is injected
 * is stated in each test (starting positions, mode switches, walls, and - where noted - a target set to null to model
 * "the mob lost its target"). {@code sync_*} tests need a config that differs from the default, so they run entirely inside one
 * server tick (the config is global and other tests run in parallel) and advance the mob by calling its tick directly.
 */
public class EliteHuntGameTests {
	private static final PersonalDifficultyMode NORMAL = PersonalDifficultyMode.NORMAL;
	private static final PersonalDifficultyMode EASY = PersonalDifficultyMode.EASY;
	private static final PersonalDifficultyMode ELITE = PersonalDifficultyMode.ELITE;
	private static final PersonalDifficultyMode PEACEFUL = PersonalDifficultyMode.PEACEFUL;
	private static final int LANE_B = 80;

	private static void tickAll(ServerPlayer... players) {
		for (ServerPlayer p : players) {
			if (p.isDeadOrDying()) {
				continue;
			}
			p.setHealth(20.0F);
			p.getFoodData().setFoodLevel(20);
			tickPlayer(p);
		}
	}

	private static void roof(Arena a, int x0, int x1, int z0, int z1) {
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				a.level.setBlock(new BlockPos(a.x0 + x, a.y + 4, a.z0 + z), Blocks.STONE.defaultBlockState(), 18);
			}
		}
	}

	// ------------------------------------------------------------------ per family: Elite beats a nearer ordinary player

	/**
	 * Lane A (control): two Normal players, the nearer one is hunted exactly as in vanilla. Lane B: a Normal player at the same
	 * near position and an Elite player farther away: the live AI hunts the Elite one and never the nearer Normal one.
	 */
	private static void prefersElite(GameTestHelper h, EntityType<? extends Mob> type, int slot, boolean dark) {
		Arena a = new Arena(h, slot);
		if (dark) {
			roof(a, 0, 40, 0, 20);
			roof(a, 0, 40, LANE_B, LANE_B + 20);
		}
		Mob mA = a.spawnOnIsland(type, 5, 5);
		Mob mB = a.spawnOnIsland(type, 5, LANE_B + 5);
		double rA = EliteAiGameTests.followRange(mA);
		double rB = EliteAiGameTests.followRange(mB);
		ServerPlayer nearA = a.player(h, "pd_pf_na", NORMAL, 5 + rA * 0.35, 5);
		ServerPlayer farA = a.player(h, "pd_pf_fa", NORMAL, 5 + rA * 0.7, 5 + 1.5);
		ServerPlayer nearB = a.player(h, "pd_pf_nb", NORMAL, 5 + rB * 0.35, LANE_B + 5);
		ServerPlayer eliteB = a.player(h, "pd_pf_eb", ELITE, 5 + rB * 0.7, LANE_B + 5 + 1.5);
		for (ServerPlayer p : List.of(nearA, farA, nearB, eliteB)) {
			p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.RESISTANCE, 100000, 4)); // an axe hit must not end the test early
		}
		int[] ticks = new int[5]; // 0 nearA, 1 farA, 2 nearB, 3 eliteB, 4 other-than-expected in lane B after tick 25
		everyTick(h, () -> {
			tickAll(nearA, farA, nearB, eliteB);
			long t = h.getTick();
			LivingEntity ta = mA.getTarget();
			LivingEntity tb = mB.getTarget();
			ticks[0] += ta == nearA ? 1 : 0;
			ticks[1] += ta == farA ? 1 : 0;
			ticks[2] += tb == nearB && t > 25 ? 1 : 0;
			ticks[3] += tb == eliteB ? 1 : 0;
		});
		at(h, 120, () -> {
			System.out.printf("AI-PRIORITY %-12s R=%.1f  vanilla lane: near=%d far=%d | elite lane: normal-after-25=%d elite=%d (alive %b/%b, y %.1f/%.1f)%n", type.toShortString(), rA, ticks[0], ticks[1], ticks[2], ticks[3], mA.isAlive(), mB.isAlive(), mA.getY() - a.y, mB.getY() - a.y);
			check(ticks[0] >= 60 && ticks[1] == 0, "control (no Elite present): the live mob hunts the nearer Normal player as in vanilla (" + ticks[0] + "/" + ticks[1] + ")");
			check(ticks[3] >= 60, "an Elite player at 0.7x range is hunted although a Normal player stands at 0.35x (" + ticks[3] + " ticks)");
			check(ticks[2] == 0, "and the nearer Normal player is not hunted after the first scan (" + ticks[2] + " ticks)");
			a.close();
			h.succeed();
		});
	}

	@GameTest(environment = LEGACY_AI, maxTicks = 200)
	public void ai_priority_zombie(GameTestHelper h) {
		prefersElite(h, EntityTypes.ZOMBIE, 40, false);
	}

	@GameTest(environment = LEGACY_AI, maxTicks = 200)
	public void ai_priority_husk(GameTestHelper h) {
		prefersElite(h, EntityTypes.HUSK, 41, false);
	}

	@GameTest(environment = LEGACY_AI, maxTicks = 200)
	public void ai_priority_skeleton(GameTestHelper h) {
		prefersElite(h, EntityTypes.SKELETON, 42, false);
	}

	@GameTest(environment = LEGACY_AI, maxTicks = 200)
	public void ai_priority_stray(GameTestHelper h) {
		prefersElite(h, EntityTypes.STRAY, 43, false);
	}

	@GameTest(environment = LEGACY_AI, maxTicks = 200)
	public void ai_priority_creeper(GameTestHelper h) {
		prefersElite(h, EntityTypes.CREEPER, 44, false);
	}

	@GameTest(environment = LEGACY_AI, maxTicks = 200)
	public void ai_priority_spider_inTheDark(GameTestHelper h) {
		prefersElite(h, EntityTypes.SPIDER, 45, true);
	}

	@GameTest(environment = LEGACY_AI, maxTicks = 200)
	public void ai_priority_caveSpider_inTheDark(GameTestHelper h) {
		prefersElite(h, EntityTypes.CAVE_SPIDER, 46, true);
	}

	@GameTest(environment = LEGACY_AI, maxTicks = 200)
	public void ai_priority_vindicator(GameTestHelper h) {
		prefersElite(h, EntityTypes.VINDICATOR, 47, false);
	}

	@GameTest(environment = LEGACY_AI, maxTicks = 200)
	public void ai_priority_pillager(GameTestHelper h) {
		prefersElite(h, EntityTypes.PILLAGER, 48, false);
	}

	// ------------------------------------------------------------------ switching, recovery, eligibility changes

	private static final class Lane {
		final Mob zombie;
		final ServerPlayer victim;
		final ServerPlayer newcomer;
		int toElite; // ticks hunting the newcomer
		int toVictimWhileEligible; // ticks hunting the victim while the newcomer is an eligible Elite player
		int toNewcomerWhileIneligible;
		boolean[] snap = new boolean[8];

		Lane(Mob zombie, ServerPlayer victim, ServerPlayer newcomer) {
			this.zombie = zombie;
			this.victim = victim;
			this.newcomer = newcomer;
		}
	}

	@GameTest(environment = LEGACY_AI, maxTicks = 300)
	public void ai_zombie_abandonsNormalAndEasyPlayersForANewElite_recoversWhenElitePeaceful_andReturnsWhenEliteAgain(GameTestHelper h) {
		Arena a = new Arena(h, 50);
		Lane normalLane = new Lane(a.spawn(EntityTypes.ZOMBIE, 5, 5), a.player(h, "pd_sw_n", NORMAL, 13, 5), a.player(h, "pd_sw_en", NORMAL, 19, 8));
		Lane easyLane = new Lane(a.spawn(EntityTypes.ZOMBIE, 5, LANE_B + 5), a.player(h, "pd_sw_e", EASY, 13, LANE_B + 5), a.player(h, "pd_sw_ee", NORMAL, 19, LANE_B + 8));
		List<Lane> lanes = List.of(normalLane, easyLane);
		everyTick(h, () -> {
			for (Lane l : lanes) {
				tickAll(l.victim, l.newcomer);
			}
			long t = h.getTick();
			for (Lane l : lanes) {
				LivingEntity target = l.zombie.getTarget();
				boolean eligible = (t >= 50 && t < 110) || t >= 180;
				if (t >= 75 && t < 110 || t >= 205) {
					l.toElite += target == l.newcomer ? 1 : 0;
					l.toVictimWhileEligible += target == l.victim ? 1 : 0;
				}
				if (t >= 114 && t < 180) {
					l.toNewcomerWhileIneligible += target == l.newcomer ? 1 : 0;
				}
				if (t == 49) {
					l.snap[0] = target == l.victim; // hunting the ordinary player first
				} else if (t == 70) {
					l.snap[1] = target == l.newcomer; // newly Elite player taken within 20 ticks
				} else if (t == 112) {
					l.snap[2] = target != l.newcomer; // dropped within 3 ticks of turning Peaceful
				} else if (t == 170) {
					l.snap[3] = target == l.victim; // recovered to the ordinary player
				} else if (t == 200) {
					l.snap[4] = target == l.newcomer; // Elite again: taken within 20 ticks
				}
				if (t == 50) {
					setMode(l.newcomer, ELITE);
				} else if (t == 110) {
					setMode(l.newcomer, PEACEFUL);
				} else if (t == 180) {
					setMode(l.newcomer, ELITE);
				}
			}
		});
		at(h, 260, () -> {
			for (Lane l : lanes) {
				String who = l == normalLane ? "Normal lane" : "Easy lane";
				System.out.printf("AI-SWITCH %s: first=%b taken-over=%b dropped-on-peaceful=%b recovered=%b retaken=%b; ticks on Elite=%d on victim while Elite eligible=%d on Peaceful=%d%n",
					who, l.snap[0], l.snap[1], l.snap[2], l.snap[3], l.snap[4], l.toElite, l.toVictimWhileEligible, l.toNewcomerWhileIneligible);
				check(l.snap[0], who + ": before the newcomer is Elite the zombie hunts the ordinary player");
				check(l.snap[1], who + ": a player who BECOMES Elite is taken as the target within 20 ticks (no restart needed)");
				check(l.toVictimWhileEligible == 0 || l.toElite > l.toVictimWhileEligible * 4, who + ": and stays on the Elite player (" + l.toElite + " vs " + l.toVictimWhileEligible + ")");
				check(l.snap[2], who + ": turning Peaceful removes the Elite priority within 3 ticks");
				check(l.toNewcomerWhileIneligible == 0, who + ": a Peaceful player is never targeted again (" + l.toNewcomerWhileIneligible + ")");
				check(l.snap[3], who + ": the zombie recovers to hunting the ordinary player");
				check(l.snap[4], who + ": switching back to Elite makes them the target again within 20 ticks");
			}
			a.close();
			h.succeed();
		});
	}

	@GameTest(environment = LEGACY_AI, maxTicks = 330)
	public void ai_severalElitePlayers_noOscillation_andRecoveryToTheOtherElite(GameTestHelper h) {
		Arena a = new Arena(h, 51);
		Mob zombie = a.spawn(EntityTypes.ZOMBIE, 30, 5);
		ServerPlayer east = a.player(h, "pd_ms_e", ELITE, 42, 5);
		ServerPlayer west = a.player(h, "pd_ms_w", ELITE, 18, 6);
		ServerPlayer normal = a.player(h, "pd_ms_n", NORMAL, 31, 20);
		int[] switches = {0};
		int[] drops = {0};
		LivingEntity[] last = {null};
		ServerPlayer[] first = {null};
		boolean[] recovered = {false};
		int[] onNormalWhileEliteExists = {0};
		everyTick(h, () -> {
			tickAll(east, west, normal);
			LivingEntity target = zombie.getTarget();
			long t = h.getTick();
			if (t < 120) {
				if (target != null && last[0] != null && target != last[0] && target instanceof ServerPlayer sp && last[0] instanceof ServerPlayer lp && sp != lp) {
					switches[0]++;
				}
				if (target == null && last[0] != null) {
					drops[0]++;
				}
				if (target != null) {
					last[0] = target;
					if (first[0] == null && target instanceof ServerPlayer sp) {
						first[0] = sp;
					}
				}
				onNormalWhileEliteExists[0] += (t > 25 && target == normal) ? 1 : 0;
			}
			if (t == 60 && first[0] != null) {
				// make the other Elite player the NEARER one by moving the hunted one away (still in range, still visible): a mob that
				// simply re-picked the nearest candidate every scan would swap now; the stability rule keeps the current target
				double dir = first[0] == east ? 1.0 : -1.0;
				first[0].snapTo(zombie.getX() + dir * 30.0, zombie.getY(), zombie.getZ() + 1.0);
			}
			if (t == 120) {
				setMode(first[0], NORMAL); // the hunted Elite player leaves Elite
			}
			if (t == 150) {
				ServerPlayer other = first[0] == east ? west : east;
				recovered[0] = target == other;
			}
		});
		at(h, 200, () -> {
			System.out.println("AI-OSCILLATION switches between the two Elite players in 120 ticks: " + switches[0] + ", target drops: " + drops[0] + ", ticks on the Normal player: " + onNormalWhileEliteExists[0]);
			check(first[0] != null && first[0] != normal, "premise: the zombie acquired one of the Elite players");
			check(switches[0] == 0, "no Elite-to-Elite flip-flopping while both stay valid (" + switches[0] + ")");
			check(onNormalWhileEliteExists[0] == 0, "the Normal player is not hunted while Elite players are eligible (" + onNormalWhileEliteExists[0] + ")");
			check(recovered[0], "when the hunted Elite player leaves Elite the zombie moves on to the other Elite player");
			a.close();
			h.succeed();
		});
	}

	@GameTest(environment = LEGACY_AI, maxTicks = 260)
	public void ai_hiddenEliteTarget_isKeptForItsMemory_notSwappedForAVisibleOrdinaryPlayer(GameTestHelper h) {
		Arena a = new Arena(h, 57);
		Mob zombie = a.spawnOnIsland(EntityTypes.ZOMBIE, 5, 5);
		ServerPlayer elite = a.player(h, "pd_hk_e", ELITE, 5 + 22, 5);
		ServerPlayer normal = a.player(h, "pd_hk_n", NORMAL, 5 + 3, 5 + 6);
		int[] onNormal = {0};
		boolean[] hunting = {false};
		everyTick(h, () -> {
			tickAll(elite, normal);
			long t = h.getTick();
			if (t == 60) {
				hunting[0] = zombie.getTarget() == elite;
				// a full-width wall between the zombie and the Elite player; the ordinary player stays in plain view
				a.wall(5 + 14, -4, 104);
			}
			if (t > 62 && t < 150 && zombie.getTarget() == normal) {
				onNormal[0]++;
			}
		});
		at(h, 200, () -> {
			check(hunting[0], "premise: the zombie hunts the Elite player (it was nearer-than-range and visible, the ordinary one is closer)");
			check(onNormal[0] == 0, "while the Elite target is merely hidden (inside its extended memory) the mob keeps it instead of switching to a visible ordinary player (" + onNormal[0] + " ticks)");
			a.close();
			h.succeed();
		});
	}

	@GameTest(environment = LEGACY_AI, maxTicks = 300)
	public void ai_nearestPlayerGoalCannotReplaceAHeldEliteTarget_andTheHeldTargetStillExpires(GameTestHelper h) {
		Arena a = new Arena(h, 58);
		Mob zombie = a.spawnOnIsland(EntityTypes.ZOMBIE, 5, 5);
		ServerPlayer elite = a.player(h, "pd_hg_e", ELITE, 5 + 22, 5);
		// Peaceful at first, so that no vanilla target goal is running when the Elite target is set from outside
		ServerPlayer normal = a.player(h, "pd_hg_n", PEACEFUL, 5 + 3, 5 + 6);
		a.wall(5 + 12, -4, 104); // the Elite player is out of sight from the start; the ordinary one is in plain view
		int[] onNormal = {0};
		boolean[] heldAt100 = {false};
		everyTick(h, () -> {
			tickAll(elite, normal);
			long t = h.getTick();
			if (t == 20) {
				// set from outside, as another mod or a goal that has since stopped could: no vanilla goal is running over it
				zombie.setTarget(elite);
			}
			if (t == 25) {
				setMode(normal, NORMAL); // now a visible ordinary player exists and the nearest-player goal is free to start
			}
			if (t > 30 && t < 110 && zombie.getTarget() == normal) {
				onNormal[0]++;
			}
			if (t == 100) {
				heldAt100[0] = zombie.getTarget() == elite;
			}
		});
		at(h, 250, () -> {
			check(onNormal[0] == 0, "the nearest-player goal does not start and swap a held Elite target for the visible ordinary player (" + onNormal[0] + " ticks)");
			check(heldAt100[0], "the hidden Elite target is still held inside its extended memory");
			check(zombie.getTarget() != elite, "...but is not held forever: it expires once it has been out of sight longer than the memory");
			a.close();
			h.succeed();
		});
	}

	@GameTest(environment = LEGACY_AI, maxTicks = 220)
	public void ai_lineOfSightAndRangeLimitsRemain_priorityDoesNotWidenThem(GameTestHelper h) {
		Arena a = new Arena(h, 52);
		Mob behindWall = a.spawnOnIsland(EntityTypes.ZOMBIE, 5, 5);
		Mob tooFar = a.spawnOnIsland(EntityTypes.ZOMBIE, 5, LANE_B + 5);
		double r = EliteAiGameTests.followRange(behindWall);
		double rFar = EliteAiGameTests.followRange(tooFar);
		ServerPlayer nearA = a.player(h, "pd_lo_na", NORMAL, 9, 5);
		ServerPlayer hidden = a.player(h, "pd_lo_h", ELITE, 5 + r * 0.6, 5);
		ServerPlayer nearB = a.player(h, "pd_lo_nb", NORMAL, 9, LANE_B + 5);
		ServerPlayer far = a.player(h, "pd_lo_f", ELITE, 5 + rFar * 1.7, LANE_B + 5);
		a.wall(5 + r * 0.3, -4, 49);
		int[] ticks = new int[4];
		everyTick(h, () -> {
			tickAll(nearA, hidden, nearB, far);
			LivingEntity ta = behindWall.getTarget();
			LivingEntity tb = tooFar.getTarget();
			ticks[0] += ta == nearA ? 1 : 0;
			ticks[1] += ta == hidden ? 1 : 0;
			ticks[2] += tb == nearB ? 1 : 0;
			ticks[3] += tb == far ? 1 : 0;
		});
		at(h, 170, () -> {
			check(ticks[1] == 0, "an Elite player behind a solid wall is never selected - no wallhack (" + ticks[1] + ")");
			check(ticks[0] >= 60, "the visible ordinary player is hunted instead (" + ticks[0] + ")");
			check(ticks[3] == 0, "an Elite player beyond the 1.25x acquisition range is never selected (" + ticks[3] + ")");
			check(ticks[2] >= 60, "the ordinary player in range is hunted instead (" + ticks[2] + ")");
			a.close();
			h.succeed();
		});
	}

	// ------------------------------------------------------------------ hidden re-acquisition

	private static void sealAround(Arena a, double x, double z) {
		int cx = (int) x;
		int cz = (int) z;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int dy = 1; dy <= 4; dy++) {
					boolean shell = Math.abs(dx) == 2 || Math.abs(dz) == 2 || dy == 4;
					if (shell) {
						a.level.setBlock(new BlockPos(a.x0 + cx + dx, a.y + dy, a.z0 + cz + dz), Blocks.STONE.defaultBlockState(), 18);
					}
				}
			}
		}
	}

	@GameTest(environment = LEGACY_AI, maxTicks = 260)
	public void ai_hiddenReacquire_followsAnObstacleCourseButNeverASealedRoom(GameTestHelper h) {
		Arena a = new Arena(h, 53);
		Mob viaGap = a.spawn(EntityTypes.ZOMBIE, 5, 5);
		Mob sealed = a.spawn(EntityTypes.ZOMBIE, 5, LANE_B + 5);
		ServerPlayer open = a.player(h, "pd_hr_o", ELITE, 29, 6);
		ServerPlayer shut = a.player(h, "pd_hr_s", ELITE, 29, LANE_B + 6);
		sealAround(a, 29, LANE_B + 6); // built before anything paths (a path-type cache keeps stale results for cells built later)
		// lane A: a wall across x=21 with a doorway at z=9..11; lane B: the player is inside a sealed stone room
		boolean[] huntedBefore = {false, false};
		boolean[] wasNulled = {false};
		boolean[] regainedA = {false};
		int[] regainedB = {0};
		everyTick(h, () -> {
			tickAll(open, shut);
			long t = h.getTick();
			if (t == 45) {
				huntedBefore[0] = viaGap.getTarget() == open;
				// the obstacle course appears only now, after the zombie has seen its target: wall at x=21 with a doorway at z=9..11
				a.wall(21, -4, 8);
				a.wall(21, 12, 40);
			}
			if (t == 47) {
				// model "the mob lost its target" (a retaliation swap or similar); the Elite player is out of sight behind the wall
				viaGap.setTarget(null);
				sealed.setTarget(null);
				// the sealed lane never had line of sight, so give that zombie the memory the other one earned by really seeing its target
				((com.zaggazach.personaldifficulty.logic.HuntingMob) sealed).personaldifficulty$rememberElite(shut.getUUID(), h.getLevel().getGameTime());
				wasNulled[0] = sealed.getTarget() == null;
			}
			if (t >= 60 && t <= 150 && viaGap.getTarget() == open) {
				regainedA[0] = true;
			}
			if (t >= 48 && sealed.getTarget() == shut) {
				regainedB[0]++;
			}
		});
		at(h, 200, () -> {
			check(huntedBefore[0], "premise: the zombie had acquired the visible Elite player autonomously");
			check(regainedA[0], "after losing the target the zombie re-acquires a hidden Elite player it recently saw when a complete route exists (around the wall, through the doorway)");
			check(regainedB[0] == 0, "...but never a player in a sealed room: no complete route, no line of sight (" + regainedB[0] + " ticks)");
			a.close();
			h.succeed();
		});
	}

	// ------------------------------------------------------------------ lifecycle

	@GameTest(environment = LEGACY_AI, maxTicks = 320)
	public void ai_deathRespawnDisconnectReconnect_eliteIsPreferredAgain(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerLevel level = h.getLevel();
		Arena a = new Arena(h, 54);
		Mob zombie = a.spawnOnIsland(EntityTypes.ZOMBIE, 5, 5);
		ServerPlayer normal = a.player(h, "pd_lc_n", NORMAL, 9, 5);
		ServerPlayer[] elite = {a.player(h, "pd_lc_e", ELITE, 5 + EliteAiGameTests.followRange(zombie) * 0.7, 6)};
		java.util.UUID id = elite[0].getUUID();
		Vec3 spot = new Vec3(a.x0 + 5 + EliteAiGameTests.followRange(zombie) * 0.7, a.y + 1, a.z0 + 6);
		boolean[] snap = new boolean[6];
		everyTick(h, () -> {
			tickAll(normal, elite[0]);
			long t = h.getTick();
			LivingEntity target = zombie.getTarget();
			if (t == 40) {
				snap[0] = target == elite[0];
				elite[0].kill(level);
			} else if (t == 55) {
				snap[1] = target != elite[0]; // dead: not the target any more (one scan interval later at the latest)
			} else if (t == 60) {
				ServerPlayer back = server.getPlayerList().respawn(elite[0], false, Entity.RemovalReason.KILLED);
				back.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
				back.snapTo(spot.x, spot.y, spot.z);
				elite[0] = back;
				a.players.add(back);
			} else if (t == 115) {
				snap[2] = target == elite[0] && rt().modeOf(id) == ELITE;
				server.getPlayerList().remove(elite[0]);
			} else if (t == 130) {
				snap[3] = target != elite[0];
			} else if (t == 135) {
				ServerPlayer again = placeNewPlayer(h, id, "pd_lc_e");
				again.snapTo(spot.x, spot.y, spot.z);
				elite[0] = again;
				a.players.add(again);
			} else if (t == 195) {
				snap[4] = target == elite[0];
			}
		});
		at(h, 230, () -> {
			check(snap[0], "premise: hunting the Elite player before death");
			check(snap[1], "a dead Elite player stops being the target");
			check(snap[2], "after respawn the player is still Elite and the zombie hunts them again (priority over the Normal player)");
			check(snap[3], "after disconnect the zombie is not stuck on the departed player");
			check(snap[4], "after reconnect (same UUID) they are hunted again");
			a.close();
			h.succeed();
		});
	}

	// ------------------------------------------------------------------ non-hostile entities are not drawn in

	@GameTest(environment = LEGACY_AI, maxTicks = 220)
	public void ai_passiveAndNeutralMobsAreNotTurnedIntoAttackers(GameTestHelper h) {
		Arena a = new Arena(h, 55);
		ServerPlayer elite = a.player(h, "pd_pn_e", ELITE, 20, 20);
		elite.setYRot(0.0F); // faces +z; the enderman stands behind, so no eye contact
		List<Mob> mobs = new ArrayList<>();
		mobs.add(a.spawnOnIsland(EntityTypes.COW, 14, 20));
		mobs.add(a.spawnOnIsland(EntityTypes.VILLAGER, 26, 20));
		mobs.add(a.spawnOnIsland(EntityTypes.IRON_GOLEM, 20, 28));
		mobs.add(a.spawnOnIsland(EntityTypes.WOLF, 14, 26));
		mobs.add(a.spawnOnIsland(EntityTypes.ZOMBIFIED_PIGLIN, 26, 26));
		mobs.add(a.spawnOnIsland(EntityTypes.ENDERMAN, 20, 12));
		int[] targeting = {0};
		everyTick(h, () -> {
			tickAll(elite);
			for (Mob m : mobs) {
				if (m.getTarget() == elite) {
					targeting[0]++;
				}
			}
		});
		at(h, 170, () -> {
			check(targeting[0] == 0, "cow, villager, iron golem, wolf, zombified piglin and enderman never target an Elite player they have no vanilla reason to attack (" + targeting[0] + " ticks)");
			a.close();
			h.succeed();
		});
	}

	// ------------------------------------------------------------------ config switches (single-tick, see class comment)

	@GameTest
	public void sync_priorityConfig_offMeansVanillaNearestPlayer_onMeansElite(GameTestHelper h) {
		ServerPlayer normal = player(h, "pd_cf_n", NORMAL);
		ServerPlayer elite = player(h, "pd_cf_e", ELITE);
		normal.snapTo(h.absoluteVec(new Vec3(1.5, 1.0, 1.5)));
		elite.snapTo(h.absoluteVec(new Vec3(7.5, 1.0, 1.5)));
		h.setBlock(0, 0, 0, Blocks.STONE);
		for (int x = -2; x < 10; x++) {
			for (int z = -2; z < 6; z++) {
				h.setBlock(x, 0, z, Blocks.STONE);
			}
		}
		int[][] seen = new int[2][2]; // [run][0 = ticks on the Normal player, 1 = ticks on the Elite player]
		int i = 0;
		for (String cfg : List.of("elite.priority.enabled=false\nelite.ai.acquisition-range-multiplier=1.0\n", "elite.priority.enabled=true\n")) {
			Mob zombie = h.spawn(EntityTypes.ZOMBIE, new Vec3(3.5, 1.0, 1.5));
			zombie.setPersistenceRequired();
			int[] counts = seen[i++];
			withConfig(cfg, () -> {
				for (int t = 0; t < 60; t++) {
					tickAll(normal, elite);
					zombie.tick();
					counts[0] += zombie.getTarget() == normal ? 1 : 0;
					counts[1] += zombie.getTarget() == elite ? 1 : 0;
				}
			});
			zombie.discard();
		}
		System.out.println("AI-CONFIG priority off: normal=" + seen[0][0] + " elite=" + seen[0][1] + " ticks; priority on: normal=" + seen[1][0] + " elite=" + seen[1][1] + " ticks");
		check(seen[0][0] > 20 && seen[0][1] == 0, "priority disabled (and no widened range): the zombie hunts the nearer ordinary player, like vanilla");
		check(seen[1][1] > 20 && seen[1][0] == 0, "priority enabled: the zombie hunts the Elite player and never the nearer ordinary one");
		h.succeed();
	}

	// ------------------------------------------------------------------ performance

	@GameTest(environment = LEGACY_AI, maxTicks = 700)
	public void ai_performance_priorityScanCostWithManyEngagedMobs(GameTestHelper h) {
		Arena a = new Arena(h, 56);
		List<Mob> mobs = new ArrayList<>();
		for (int i = 0; i < 120; i++) {
			mobs.add(a.spawnOnIsland(EntityTypes.ZOMBIE, 5 + (i % 12) * 7, 5 + (i / 12) * 7));
		}
		ServerPlayer normal = a.player(h, "pd_pf_n", NORMAL, 45, 40);
		ServerPlayer elite = a.player(h, "pd_pf_e", ELITE, 110, 90); // far from nearly everything: only the cheap checks run
		long[] last = {0};
		long[][] sums = new long[2][2];
		everyTick(h, () -> {
			tickAll(normal, elite);
			long now = System.nanoTime();
			long t = h.getTick();
			if (t > 30 && last[0] != 0) {
				int phase = (int) ((t / 100) % 2);
				sums[phase][0] += now - last[0];
				sums[phase][1]++;
			}
			if (t % 100 == 0) {
				// phase A: nobody is Elite (the scan exits at once); phase B: one Elite player exists, far from every mob
				setMode(elite, (t / 100) % 2 == 0 ? NORMAL : ELITE);
			}
			last[0] = now;
		});
		at(h, 640, () -> {
			double off = sums[0][0] / 1e6 / Math.max(1, sums[0][1]);
			double on = sums[1][0] / 1e6 / Math.max(1, sums[1][1]);
			System.out.printf("AI-PERF-PRIORITY 120 engaged zombies, 1 Elite player far away: mean tick %.3f ms (no Elite online) vs %.3f ms (one Elite online, out of range)%n", off, on);
			check(on < 45.0 && off < 45.0, "ticks stay far below the 50 ms budget");
			a.close();
			h.succeed();
		});
	}
}
