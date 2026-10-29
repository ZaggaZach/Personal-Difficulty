package com.zaggazach.personaldifficulty.gametest;

import static com.zaggazach.personaldifficulty.gametest.PdTestSupport.*;

import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.gametest.EliteAiGameTests.Arena;
import com.zaggazach.personaldifficulty.logic.HuntingMob;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Phase 5: the alert network and last-known-position search, on live vanilla AI.
 *
 * <p>The feature is off by default, so the {@code ai_alert_*} tests run in their own test environment
 * ({@code personaldifficulty-gametest:elite_alerts}), whose setup and teardown functions switch it on and off around that batch;
 * no other test runs while it is on. {@code ai_alertOff_*} runs in the default environment and proves nothing happens while it is off.
 *
 * <p>The scenario: a "sentry" zombie that cannot move (slowness) sees an Elite player; a "listener" zombie 50 blocks away is cut off
 * from the player by a wall. After the sentry has seen the player, the player is moved behind a second wall where nobody can see
 * them. The listener must walk to the point where the sentry SAW the player, and must not head for where the player is now.
 */
public class EliteAlertGameTests {
	private static final String ALERTS = "personaldifficulty-gametest:elite_alerts";
	private static final double SENTRY_X = 5;
	private static final double SENTRY_Z = 60;
	private static final double SEEN_X = 25;
	private static final double SEEN_Z = 60;
	private static final double LISTENER_X = 5;
	private static final double LISTENER_Z = 10;
	private static final double HIDDEN_X = 25;
	private static final double HIDDEN_Z = 95;

	/** A wall along x at a fixed z (Arena.wall builds one along z). */
	private static void wallAlongX(Arena a, int z, int xFrom, int xTo) {
		for (int x = xFrom; x <= xTo; x++) {
			for (int dy = 1; dy <= 10; dy++) {
				a.level.setBlock(new BlockPos(a.x0 + x, a.y + dy, a.z0 + z), Blocks.STONE.defaultBlockState(), 18);
			}
		}
	}

	/** The scenario's mobs, player and walls; returns {sentry, listener} and the player through {@code out}. */
	private record Scene(Arena arena, Mob sentry, Mob listener, ServerPlayer player, Vec3 seen, Vec3 hidden) {
	}

	private static Scene scene(GameTestHelper h, int slot, String name, PersonalDifficultyMode mode) {
		Arena a = new Arena(h, slot);
		Mob sentry = a.spawn(EntityTypes.ZOMBIE, SENTRY_X, SENTRY_Z);
		sentry.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 100000, 10, false, false)); // it sees and reports, but cannot walk
		Mob listener = a.spawn(EntityTypes.ZOMBIE, LISTENER_X, LISTENER_Z);
		ServerPlayer p = a.player(h, name, mode, SEEN_X, SEEN_Z);
		p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100000, 4, false, false));
		a.wall(15, 0, 45); // between the listener and the player
		wallAlongX(a, 80, 0, 45); // hides the later hiding place from the sentry and from the remembered point
		Vec3 seen = new Vec3(a.x0 + SEEN_X, a.y + 1, a.z0 + SEEN_Z);
		Vec3 hidden = new Vec3(a.x0 + HIDDEN_X, a.y + 1, a.z0 + HIDDEN_Z);
		return new Scene(a, sentry, listener, p, seen, hidden);
	}

	@GameTest(environment = ALERTS, maxTicks = 900)
	public void ai_alert_aListenerSearchesWhereTheSentrySawTheElitePlayer_notWhereTheyAreNow(GameTestHelper h) {
		check(rt().config().eliteAlert().enabled(), "the test environment switched the alert network on");
		Scene elite = scene(h, 90, "pd_al_e", PersonalDifficultyMode.ELITE);
		Scene normal = scene(h, 91, "pd_al_n", PersonalDifficultyMode.NORMAL);
		long[] sentrySawAt = {-1, -1};
		boolean[] moved = {false, false};
		double[] minToSeen = {Double.MAX_VALUE, Double.MAX_VALUE};
		double[] minToLiveAfterMove = {Double.MAX_VALUE, Double.MAX_VALUE};
		int[] listenerTargetedBeforeArrival = {0};
		Scene[] scenes = {elite, normal};
		everyTick(h, () -> {
			for (int i = 0; i < 2; i++) {
				Scene s = scenes[i];
				tickPlayer(s.player());
				long now = h.getTick();
				if (sentrySawAt[i] < 0 && s.sentry().getTarget() == s.player()) {
					sentrySawAt[i] = now;
				}
				// 20 ticks after the sentry has the player (it reports on its own scan, every 10 ticks), the player hides
				if (!moved[i] && sentrySawAt[i] >= 0 && now >= sentrySawAt[i] + 20) {
					s.player().snapTo(s.hidden().x, s.hidden().y, s.hidden().z);
					moved[i] = true;
				}
				minToSeen[i] = Math.min(minToSeen[i], s.listener().position().distanceTo(s.seen()));
				if (moved[i]) {
					minToLiveAfterMove[i] = Math.min(minToLiveAfterMove[i], s.listener().position().distanceTo(s.player().position()));
				}
				if (i == 0 && s.listener().getTarget() == s.player() && minToSeen[0] > 4.0) {
					listenerTargetedBeforeArrival[0]++;
				}
			}
		});
		at(h, 850, () -> {
			String report = "ALERT-SEARCH elite: sentry saw at " + sentrySawAt[0] + ", listener closest to the remembered point " + fmt(minToSeen[0])
				+ ", closest to the live player after hiding " + fmt(minToLiveAfterMove[0]) + " | normal control: sentry saw at " + sentrySawAt[1]
				+ ", listener closest to the point " + fmt(minToSeen[1]);
			System.out.println(report);
			check(sentrySawAt[0] >= 0, "the sentry saw the Elite player: " + report);
			check(moved[0], "the player hid: " + report);
			check(minToSeen[0] <= 4.0, "the listener walked to the remembered point: " + report);
			check(minToLiveAfterMove[0] > 12.0, "and never homed in on where the player is now: " + report);
			check(listenerTargetedBeforeArrival[0] == 0, "nobody told it where the player is: it never targeted them on the way (" + listenerTargetedBeforeArrival[0] + " ticks)");
			check(minToSeen[1] > 25.0, "control: a NORMAL player's sighting is never passed on: " + report);
			check(((HuntingMob) elite.listener()).personaldifficulty$searchedPlayer() != null, "the search was recorded, so the same sighting is not searched again");
			elite.arena().close();
			normal.arena().close();
			h.succeed();
		});
	}

	@GameTest(maxTicks = 500)
	public void ai_alertOff_nothingIsPassedOnWhileTheSettingIsOff(GameTestHelper h) {
		check(!rt().config().eliteAlert().enabled(), "off by default");
		Scene s = scene(h, 92, "pd_al_off", PersonalDifficultyMode.ELITE);
		double[] minToSeen = {Double.MAX_VALUE};
		long[] sawAt = {-1};
		everyTick(h, () -> {
			tickPlayer(s.player());
			if (sawAt[0] < 0 && s.sentry().getTarget() == s.player()) {
				sawAt[0] = h.getTick();
			}
			minToSeen[0] = Math.min(minToSeen[0], s.listener().position().distanceTo(s.seen()));
		});
		at(h, 450, () -> {
			check(sawAt[0] >= 0, "the sentry did see the Elite player");
			check(minToSeen[0] > 25.0, "with the alert network off the listener never heads for the sighting (closest " + fmt(minToSeen[0]) + ")");
			s.arena().close();
			h.succeed();
		});
	}

	// ------------------------------------------------------------------ witch root cause (diagnostic only; it reports, never fails)

	@GameTest(maxTicks = 260, required = false)
	public void diag_witch_targetingAgainstEliteAndNormalPlayers(GameTestHelper h) {
		Arena a = new Arena(h, 93);
		Mob near = a.spawnOnIsland(EntityTypes.WITCH, 5, 5);
		Mob far = a.spawnOnIsland(EntityTypes.WITCH, 5, 80 + 5);
		double rNear = near.getAttributeValue(Attributes.FOLLOW_RANGE);
		double rFar = far.getAttributeValue(Attributes.FOLLOW_RANGE);
		ServerPlayer pNear = a.player(h, "pd_wd_n", PersonalDifficultyMode.ELITE, 5 + rNear * 0.5, 5);
		ServerPlayer pFar = a.player(h, "pd_wd_f", PersonalDifficultyMode.ELITE, 5 + rFar * 1.12, 80 + 5);
		int[] ticks = new int[2];
		int[] canAttackTrue = new int[2];
		int[] samples = new int[2];
		everyTick(h, () -> {
			tickPlayer(pNear);
			tickPlayer(pFar);
			ticks[0] += near.getTarget() == pNear ? 1 : 0;
			ticks[1] += far.getTarget() == pFar ? 1 : 0;
			Boolean c0 = canAttack(near);
			Boolean c1 = canAttack(far);
			if (c0 != null) {
				samples[0]++;
				canAttackTrue[0] += c0 ? 1 : 0;
			}
			if (c1 != null) {
				samples[1]++;
				canAttackTrue[1] += c1 ? 1 : 0;
			}
		});
		at(h, 240, () -> {
			System.out.println("WITCH-DIAG follow ranges " + fmt(rNear) + " / " + fmt(rFar) + "; huntsPlayers " + ((HuntingMob) near).personaldifficulty$huntsPlayers()
				+ "; inside vanilla range (0.5x): targeted " + ticks[0] + " ticks, canAttack " + canAttackTrue[0] + "/" + samples[0]
				+ "; extended range (1.12x): targeted " + ticks[1] + " ticks, canAttack " + canAttackTrue[1] + "/" + samples[1]
				+ "; witch is " + (near instanceof Witch ? "a Witch" : near.getClass().getSimpleName()));
			a.close();
			h.succeed();
		});
	}

	/** The private canAttack flag of a witch's player-targeting goal, read reflectively for the diagnostic; null if unreadable. */
	private static Boolean canAttack(Mob witch) {
		try {
			var goalField = Witch.class.getDeclaredField("attackPlayersGoal");
			goalField.setAccessible(true);
			Object goal = goalField.get(witch);
			if (goal == null) {
				return null;
			}
			var flag = goal.getClass().getDeclaredField("canAttack");
			flag.setAccessible(true);
			return flag.getBoolean(goal);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	private static String fmt(double v) {
		return v == Double.MAX_VALUE ? "never" : String.format(java.util.Locale.ROOT, "%.1f", v);
	}
}
