package com.zaggazach.personaldifficulty.gametest;

import static com.zaggazach.personaldifficulty.gametest.PdTestSupport.*;

import com.zaggazach.personaldifficulty.PdHunt;
import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.config.PdConfig;
import com.zaggazach.personaldifficulty.gametest.EliteAiGameTests.Arena;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;

/**
 * The 1.4.0 Elite hunting defaults (plan C2, switched on after the Phase 5 performance gate), live, in the default test environment
 * with nothing configured: 3x detection inside the 128-block cap, 5x memory and the alert network on. (The 1.3.0 numbers are still
 * tested exactly, in {@link PdTestSupport#LEGACY_AI}.)
 */
public class EliteDefaultsGameTests {
	@GameTest(maxTicks = 260)
	public void ai_defaults_aZombieNoticesAnElitePlayerAt2_5xItsVanillaRange_neverANormalOne(GameTestHelper h) {
		PdConfig.EliteAi ai = rt().config().eliteAi();
		check(ai.acquisitionRangeMultiplier() == 3.0 && ai.targetMemoryMultiplier() == 5.0 && ai.absoluteRangeCap() == 128,
			"the run starts from the shipped defaults: " + ai);
		check(rt().config().eliteAlert().enabled(), "the alert network is on by default");

		Arena eliteArena = new Arena(h, 95);
		Arena normalArena = new Arena(h, 96);
		Mob mE = eliteArena.spawnOnIsland(EntityTypes.ZOMBIE, 5, 5);
		Mob mN = normalArena.spawnOnIsland(EntityTypes.ZOMBIE, 5, 5);
		double rE = EliteAiGameTests.followRange(mE);
		double rN = EliteAiGameTests.followRange(mN);
		ServerPlayer pE = eliteArena.player(h, "pd_df_e", PersonalDifficultyMode.ELITE, 5 + rE * 2.5, 5);
		ServerPlayer pN = normalArena.player(h, "pd_df_n", PersonalDifficultyMode.NORMAL, 5 + rN * 2.5, 5);
		pE.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100000, 4, false, false));
		double[] eliteAcquiredAt = {-1};
		int[] normalBeyondVanilla = {0};
		boolean[] had = {false};
		everyTick(h, () -> {
			tickPlayer(pE);
			tickPlayer(pN);
			boolean has = mE.getTarget() == pE;
			if (has && !had[0] && eliteAcquiredAt[0] < 0) {
				eliteAcquiredAt[0] = mE.distanceTo(pE);
			}
			had[0] = has;
			if (mN.getTarget() == pN && mN.distanceTo(pN) > rN + 0.5) {
				normalBeyondVanilla[0]++;
			}
		});
		at(h, 240, () -> {
			String report = String.format(java.util.Locale.ROOT, "DEFAULTS zombie vanilla range %.1f, Elite player at %.1f, acquired at %s; Normal targeted beyond vanilla range %d ticks",
				rE, rE * 2.5, eliteAcquiredAt[0] < 0 ? "never" : String.format(java.util.Locale.ROOT, "%.1f", eliteAcquiredAt[0]), normalBeyondVanilla[0]);
			System.out.println(report);
			check(eliteAcquiredAt[0] > rE * 2.0, "the Elite player is noticed beyond 2x the vanilla range (1.3.0 stopped at 1.25x): " + report);
			check(normalBeyondVanilla[0] == 0, "a Normal player at the same distance never is: " + report);
			eliteArena.close();
			normalArena.close();
			h.succeed();
		});
	}

	// ------------------------------------------------------------------ plan 6.2: pursuit beyond the path-search budget

	/**
	 * MEASUREMENT for plan 6.2 (not required yet): does an Elite target at 2.8x the vanilla range get reached with the mod's
	 * existing hooks (longer path length, held target), or are routes cut short so that a waypoint goal is needed?
	 * Two arenas: open ground, and a chasm the zombie can see across but must walk around (about 160 blocks of route for 98
	 * blocks of straight line). The target is set directly: this measures pursuit, not acquisition.
	 */
	@GameTest(maxTicks = 620, required = false)
	public void ai_measure_longPursuit_openGroundAndAroundAChasm(GameTestHelper h) {
		Arena open = new Arena(h, 97);
		Arena chasm = new Arena(h, 98);
		for (int x = 40; x <= 44; x++) { // a 5-wide gap in the floor from the edge to z = 70: too wide to jump, nothing blocks sight
			for (int z = -4; z <= 70; z++) {
				chasm.level.setBlock(new net.minecraft.core.BlockPos(chasm.x0 + x, chasm.y, chasm.z0 + z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 18);
			}
		}
		Arena[] arenas = {open, chasm};
		Mob[] mobs = new Mob[2];
		ServerPlayer[] players = new ServerPlayer[2];
		double[] start = new double[2];
		double[] closest = {Double.MAX_VALUE, Double.MAX_VALUE};
		int[] heldTicks = new int[2];
		boolean[] reachesAtStart = new boolean[2];
		int[] partialPlans = new int[2];
		for (int i = 0; i < 2; i++) {
			mobs[i] = arenas[i].spawn(EntityTypes.ZOMBIE, 5, 5);
			double r = EliteAiGameTests.followRange(mobs[i]);
			players[i] = arenas[i].player(h, "pd_lp_" + i, PersonalDifficultyMode.ELITE, 5 + r * 2.8, 5);
			players[i].addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100000, 4, false, false));
		}
		everyTick(h, () -> {
			for (int i = 0; i < 2; i++) {
				tickPlayer(players[i]);
				Mob m = mobs[i];
				if (h.getTick() == 5) {
					m.setTarget(players[i]);
					start[i] = m.distanceTo(players[i]);
					var path = m.getNavigation().createPath(players[i], 0);
					reachesAtStart[i] = path != null && path.canReach();
				}
				if (h.getTick() > 5) {
					heldTicks[i] += m.getTarget() == players[i] ? 1 : 0;
					closest[i] = Math.min(closest[i], m.distanceTo(players[i]));
					var current = m.getNavigation().getPath();
					if (current != null && !current.canReach() && h.getTick() % 20 == 0) {
						partialPlans[i]++;
					}
				}
			}
		});
		at(h, 600, () -> {
			for (int i = 0; i < 2; i++) {
				System.out.printf(java.util.Locale.ROOT, "PURSUIT-MEASURE %-6s start %.1f blocks, closest %.1f, target held %d/594 ticks, complete route at start %b, partial-route samples %d%n",
					i == 0 ? "open" : "chasm", start[i], closest[i], heldTicks[i], reachesAtStart[i], partialPlans[i]);
			}
			open.close();
			chasm.close();
			h.succeed();
		});
	}

	// ------------------------------------------------------------------ giving up on an unreachable Elite player

	/**
	 * PursuitStall, live, three arenas:
	 * <ul>
	 * <li><b>chasm</b>: a zombie hunting an Elite player 2.8x its vanilla range away walks to the edge of a chasm it cannot cross,
	 * makes no progress for five seconds, gives up: the player is beyond its vanilla range, so it lets go of them and does not
	 * take them back during the 30-second retry delay;</li>
	 * <li><b>pillar</b>: a zombie hunting an Elite player on top of an 8-block pillar gives up the Elite extensions too, but the
	 * player is inside its vanilla range, so it keeps hunting them exactly as a vanilla zombie would;</li>
	 * <li><b>open</b> (control): a zombie hunting an Elite player on open ground (complete routes, steady progress) never gives up.</li>
	 * </ul>
	 */
	@GameTest(maxTicks = 760)
	public void ai_stall_aZombieGivesUpOnAnUnreachableElitePlayer_neverOnAReachableOne(GameTestHelper h) {
		Arena chasm = new Arena(h, 100);
		Arena pillar = new Arena(h, 101);
		Arena open = new Arena(h, 102);
		for (int x = 40; x <= 44; x++) { // as in the long-pursuit measurement: too wide to jump, nothing blocks sight
			for (int z = -4; z <= 70; z++) {
				chasm.level.setBlock(new net.minecraft.core.BlockPos(chasm.x0 + x, chasm.y, chasm.z0 + z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 18);
			}
		}
		int px = 60;
		int pz = 50;
		for (int dy = 1; dy <= 8; dy++) {
			pillar.level.setBlock(new net.minecraft.core.BlockPos(pillar.x0 + px, pillar.y + dy, pillar.z0 + pz), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 18);
		}
		Mob mC = chasm.spawn(EntityTypes.ZOMBIE, 5, 5);
		Mob mP = pillar.spawn(EntityTypes.ZOMBIE, px - 30, pz);
		Mob mO = open.spawn(EntityTypes.ZOMBIE, 5, 5);
		ServerPlayer pC = chasm.player(h, "pd_st_c", PersonalDifficultyMode.ELITE, 5 + EliteAiGameTests.followRange(mC) * 2.8, 5);
		ServerPlayer pP = pillar.player(h, "pd_st_p", PersonalDifficultyMode.ELITE, px, pz);
		ServerPlayer pO = open.player(h, "pd_st_o", PersonalDifficultyMode.ELITE, 5 + EliteAiGameTests.followRange(mO) * 2.5, 5);
		for (ServerPlayer p : java.util.List.of(pC, pP, pO)) {
			p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100000, 4, false, false));
		}
		long[] chasmDroppedAt = {-1};
		int[] chasmRetaken = {0};
		long[] pillarGaveUpAt = {-1};
		int[] pillarHeldAfter = {0};
		int[] pillarTicksAfter = {0};
		int[] openHeld = {0};
		int[] openTicks = {0};
		boolean[] openGaveUp = {false};
		everyTick(h, () -> {
			tickPlayer(pC);
			tickPlayer(pP);
			tickPlayer(pO);
			pP.snapTo(pillar.x0 + px + 0.5, pillar.y + 9, pillar.z0 + pz + 0.5); // stays on top
			long t = h.getTick();
			if (t == 5) {
				mC.setTarget(pC);
				mP.setTarget(pP);
				mO.setTarget(pO);
			}
			if (t <= 5) {
				return;
			}
			if (chasmDroppedAt[0] < 0 && mC.getTarget() != pC) {
				chasmDroppedAt[0] = t;
			} else if (chasmDroppedAt[0] >= 0 && mC.getTarget() == pC) {
				chasmRetaken[0]++;
			}
			if (pillarGaveUpAt[0] < 0 && PdHunt.gaveUpOn(mP, pP)) {
				pillarGaveUpAt[0] = t;
			} else if (pillarGaveUpAt[0] >= 0) {
				pillarTicksAfter[0]++;
				pillarHeldAfter[0] += mP.getTarget() == pP ? 1 : 0;
			}
			openTicks[0]++;
			openHeld[0] += mO.getTarget() == pO ? 1 : 0;
			openGaveUp[0] |= PdHunt.gaveUpOn(mO, pO);
		});
		at(h, 740, () -> {
			String report = String.format(java.util.Locale.ROOT,
				"STALL chasm: dropped at tick %d, re-taken for %d ticks after | pillar: gave up at tick %d, then held %d/%d ticks (vanilla range) | open: held %d/%d, gave up %b",
				chasmDroppedAt[0], chasmRetaken[0], pillarGaveUpAt[0], pillarHeldAfter[0], pillarTicksAfter[0], openHeld[0], openTicks[0], openGaveUp[0]);
			System.out.println(report);
			check(chasmDroppedAt[0] > 0, "chasm: the zombie gave up on the Elite player it could not reach: " + report);
			check(chasmRetaken[0] == 0, "chasm: and did not take them back during the retry delay: " + report);
			check(pillarGaveUpAt[0] > 0, "pillar: the Elite extensions were given up: " + report);
			check(pillarHeldAfter[0] > pillarTicksAfter[0] / 2, "pillar: inside its vanilla range the zombie still hunts, as vanilla would: " + report);
			check(!openGaveUp[0] && openHeld[0] == openTicks[0], "open: a reachable Elite player is never given up: " + report);
			chasm.close();
			pillar.close();
			open.close();
			h.succeed();
		});
	}
}
