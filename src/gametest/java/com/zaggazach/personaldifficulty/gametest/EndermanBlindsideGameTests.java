package com.zaggazach.personaldifficulty.gametest;

import static com.zaggazach.personaldifficulty.gametest.PdTestSupport.*;

import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.config.PdConfig;
import com.zaggazach.personaldifficulty.gametest.EliteAiGameTests.Arena;
import com.zaggazach.personaldifficulty.logic.EndermanBlindside;
import com.zaggazach.personaldifficulty.mixin.EndermanAccess;
import java.util.Locale;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.projectile.arrow.Arrow;

/**
 * 1.4.0, Phase 6 (plan 6.5): Enderman Blindside, on vanilla's own teleport code. Two arenas (an Elite and a Normal player, each
 * looking along +z with an Enderman 30 blocks in front of them). Each trial puts the Enderman back and calls one of the two vanilla
 * teleports; a landing counts as "behind" if it is behind the player (more than 90 degrees off their view) and within 5 blocks.
 */
public class EndermanBlindsideGameTests {
	private static final int TRIALS = 60;

	private record Lane(Arena arena, ServerPlayer player, Enderman enderman) {
		void reset() {
			player.snapTo(arena.x0 + 60.5, arena.y + 1, arena.z0 + 30.5, 0f, 0f);
			player.setYHeadRot(0f);
			enderman.snapTo(arena.x0 + 60.5, arena.y + 1, arena.z0 + 60.5);
		}

		boolean landedBehind() {
			double dx = enderman.getX() - player.getX();
			double dz = enderman.getZ() - player.getZ();
			return Math.sqrt(dx * dx + dz * dz) <= 5.0 && EndermanBlindside.isBehind(player.getX(), player.getZ(), player.getYRot(), enderman.getX(), enderman.getZ());
		}
	}

	private static Lane lane(GameTestHelper h, int slot, String name, PersonalDifficultyMode mode) {
		Arena a = new Arena(h, slot);
		ServerPlayer p = a.player(h, name, mode, 60.5, 30.5);
		Enderman e = (Enderman) a.spawn(EntityTypes.ENDERMAN, 60.5, 60.5);
		e.setNoAi(true); // only the teleports under test move it
		Lane l = new Lane(a, p, e);
		l.reset();
		return l;
	}

	@GameTest(maxTicks = 100)
	public void enderman_blindside_closingTheDistanceAndDodgingAnArrow_landBehindAnElitePlayerAboutHalfTheTime_neverANormalOne(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		PdConfig.EliteEnderman en = rt().config().eliteEnderman();
		check(en.enabled() && en.chance() == 0.5 && en.distance() == 3, "the shipped defaults: " + en);
		Lane elite = lane(h, 107, "pd_bs_e", PersonalDifficultyMode.ELITE);
		Lane normal = lane(h, 108, "pd_bs_n", PersonalDifficultyMode.NORMAL);
		int[][] behind = new int[2][2]; // [elite, normal][approach, dodge]
		int[][] moved = new int[2][2];
		Lane[] lanes = {elite, normal};
		for (int i = 0; i < 2; i++) {
			Lane l = lanes[i];
			for (int t = 0; t < TRIALS; t++) {
				l.reset();
				if (((EndermanAccess) l.enderman()).personaldifficulty$teleportTowards(l.player())) {
					moved[i][0]++;
					behind[i][0] += l.landedBehind() ? 1 : 0;
				}
				l.reset();
				Arrow arrow = new Arrow(level, l.player(), new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW), null);
				double before = l.enderman().getX();
				double beforeZ = l.enderman().getZ();
				l.enderman().hurtServer(level, level.damageSources().arrow(arrow, l.player()), 1.0F);
				arrow.discard();
				if (l.enderman().getX() != before || l.enderman().getZ() != beforeZ) {
					moved[i][1]++;
					behind[i][1] += l.landedBehind() ? 1 : 0;
				}
			}
		}
		String report = String.format(Locale.ROOT,
			"BLINDSIDE %d trials each | closing the distance: Elite behind %d of %d teleports, Normal %d of %d | dodging an arrow: Elite behind %d of %d, Normal %d of %d",
			TRIALS, behind[0][0], moved[0][0], behind[1][0], moved[1][0], behind[0][1], moved[0][1], behind[1][1], moved[1][1]);
		System.out.println(report);
		for (int k = 0; k < 2; k++) {
			check(moved[0][k] >= TRIALS * 0.8 && moved[1][k] >= TRIALS * 0.8, "premise: the Enderman teleported in most trials: " + report);
			double share = behind[0][k] / (double) moved[0][k];
			check(share >= 0.3 && share <= 0.7, "an Elite player gets an Enderman behind them about half the time (chance 0.5): " + report);
			check(behind[1][k] <= 2, "a Normal player practically never does (vanilla's teleports land far and at random): " + report);
		}
		elite.arena().close();
		normal.arena().close();
		h.succeed();
	}
}
