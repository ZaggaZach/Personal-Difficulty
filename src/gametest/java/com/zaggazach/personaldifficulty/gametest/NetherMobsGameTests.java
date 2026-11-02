package com.zaggazach.personaldifficulty.gametest;

import static com.zaggazach.personaldifficulty.gametest.PdTestSupport.*;

import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.gametest.EliteAiGameTests.Arena;
import java.util.Locale;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;

/**
 * 1.4.0, Phase 7 spike (plan 6.8): which goal-based Nether mobs the Elite hunting already applies to, measured live. Blazes and
 * wither skeletons are {@code Monster}s with a player-targeting goal, so the hunting scan covers them by construction (since
 * 1.3.0, never claimed); ghasts and magma cubes are not {@code Monster}s, so it does not. Each test gives the mob its own arena per
 * lane: an Elite player at {@code factor} times the mob's vanilla follow range, and a Normal player at the same distance.
 *
 * <p>Reports only for the mobs expected NOT to be covered (they document the current behaviour); the covered ones must pass.
 */
public class NetherMobsGameTests {
	private record Result(double range, double distance, boolean eliteBeyondVanilla, int normalBeyondVanilla) {
	}

	private static void lane(GameTestHelper h, EntityType<? extends Mob> type, int slotElite, int slotNormal, double factor, String name,
		java.util.function.Consumer<Result> verdict) {
		Arena aE = new Arena(h, slotElite);
		Arena aN = new Arena(h, slotNormal);
		Mob mE = aE.spawnOnIsland(type, 5, 50);
		Mob mN = aN.spawnOnIsland(type, 5, 50);
		double rE = EliteAiGameTests.followRange(mE);
		double rN = EliteAiGameTests.followRange(mN);
		double dE = Math.min(rE * factor, 110);
		ServerPlayer pE = aE.player(h, "pd_nm_e_" + name, PersonalDifficultyMode.ELITE, 5 + dE, 50);
		ServerPlayer pN = aN.player(h, "pd_nm_n_" + name, PersonalDifficultyMode.NORMAL, 5 + Math.min(rN * factor, 110), 50);
		for (ServerPlayer p : java.util.List.of(pE, pN)) {
			p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100000, 4, false, false));
			p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 100000, 0, false, false));
		}
		boolean[] eliteBeyond = {false};
		int[] normalBeyond = {0};
		boolean[] hadN = {false};
		everyTick(h, () -> {
			tickPlayer(pE);
			tickPlayer(pN);
			pE.setHealth(pE.getMaxHealth());
			pN.setHealth(pN.getMaxHealth());
			if (mE.getTarget() == pE && mE.distanceTo(pE) > rE + 0.5) {
				eliteBeyond[0] = true;
			}
			boolean hasN = mN.getTarget() == pN;
			if (hasN && !hadN[0] && mN.distanceTo(pN) > rN + 0.5) {
				normalBeyond[0]++;
			}
			hadN[0] = hasN;
		});
		at(h, 240, () -> {
			Result r = new Result(rE, dE, eliteBeyond[0], normalBeyond[0]);
			System.out.println(String.format(Locale.ROOT, "NETHER-MOB %-16s vanilla range %.1f, players at %.1f: Elite acquired beyond vanilla range %b, Normal acquired beyond it %d time(s)",
				type.toShortString(), r.range(), r.distance(), r.eliteBeyondVanilla(), r.normalBeyondVanilla()));
			verdict.accept(r);
			aE.close();
			aN.close();
			h.succeed();
		});
	}

	@GameTest(maxTicks = 260)
	public void nether_blaze_acquiresAnElitePlayerBeyondItsVanillaRange_neverANormalOne(GameTestHelper h) {
		lane(h, EntityTypes.BLAZE, 115, 116, 1.8, "bl", r -> {
			check(r.eliteBeyondVanilla(), "a blaze acquires an Elite player beyond its vanilla range: " + r);
			check(r.normalBeyondVanilla() == 0, "never a Normal player: " + r);
		});
	}

	@GameTest(maxTicks = 260)
	public void nether_witherSkeleton_acquiresAnElitePlayerBeyondItsVanillaRange_neverANormalOne(GameTestHelper h) {
		lane(h, EntityTypes.WITHER_SKELETON, 117, 118, 2.0, "ws", r -> {
			check(r.eliteBeyondVanilla(), "a wither skeleton acquires an Elite player beyond its vanilla range: " + r);
			check(r.normalBeyondVanilla() == 0, "never a Normal player: " + r);
		});
	}

	/** Not covered by construction (not a Monster): documents the behaviour, does not fail on it. */
	@GameTest(maxTicks = 260, required = false)
	public void nether_magmaCube_isNotCoveredByTheEliteHunting(GameTestHelper h) {
		lane(h, EntityTypes.MAGMA_CUBE, 119, 120, 2.0, "mc", r -> check(r.normalBeyondVanilla() == 0, "never a Normal player: " + r));
	}

	/** Not covered by construction (not a Monster): documents the behaviour, does not fail on it. */
	@GameTest(maxTicks = 260, required = false)
	public void nether_ghast_isNotCoveredByTheEliteHunting(GameTestHelper h) {
		lane(h, EntityTypes.GHAST, 121, 122, 1.08, "gh", r -> check(r.normalBeyondVanilla() == 0, "never a Normal player: " + r));
	}
}
