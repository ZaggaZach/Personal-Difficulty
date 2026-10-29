package com.zaggazach.personaldifficulty.gametest;

import static com.zaggazach.personaldifficulty.gametest.PdTestSupport.*;

import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.gametest.EliteAiGameTests.Arena;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Phase 5 performance measurement (plan section 8): server tick time with 200 hostile mobs and 5 players, with no Elite player
 * online (the baseline) and with the 5 players on Elite under each feature setup, separately and together.
 *
 * <p>Opt-in: it adds about two minutes, so it does nothing unless the build is run with {@code -PpdPerf=true} (see build.gradle). It
 * runs in a test environment of its own, so no other test runs while it changes the configuration, and it restores the
 * configuration at the end. It reports numbers; it fails only if the scenario itself broke (mobs or players missing).
 *
 * <p>Method: the same crowd and the same players are used throughout; window types alternate (twice each, in the same order) so
 * that slow drift (mobs bunching up around the players over time) affects every type alike. The first {@link #SETTLE} ticks after
 * every switch are not measured. Tick time is the server's own measurement of the work done in each tick
 * ({@code MinecraftServer.getTickTimesNanos}), which excludes the time it sleeps between ticks.
 */
public class PerformanceGameTests {
	private static final String PERF = "personaldifficulty-gametest:elite_perf";
	private static final int ZOMBIES = 150;
	private static final int SKELETONS = 50;
	private static final int PLAYERS = 5;
	private static final int WARMUP = 200;
	private static final int WINDOW = 200;
	private static final int SETTLE = 50;

	/** Window types, in the order they are measured. */
	enum Setup {
		NO_ELITE("baseline: nobody on Elite", false, null),
		ELITE_13("Elite, 1.3 behaviour (defaults)", true, null),
		ELITE_RANGE("Elite + 3x detection, 5x memory, 128 cap", true, "range"),
		ELITE_ALERTS("Elite + alert network only", true, "alerts"),
		ELITE_ALL("Elite + everything new", true, "all");

		final String label;
		final boolean elite;
		final String features;

		Setup(String label, boolean elite, String features) {
			this.label = label;
			this.elite = elite;
			this.features = features;
		}
	}

	private static void apply(Setup s, List<ServerPlayer> players) {
		ConfigCommandGameTests.cleanOverrides();
		if ("range".equals(s.features) || "all".equals(s.features)) {
			rt().setValue("elite.ai.acquisition-range-multiplier", "3.0");
			rt().setValue("elite.ai.target-memory-multiplier", "5.0");
			rt().setValue("elite.ai.absolute-range-cap", "128");
		}
		if ("alerts".equals(s.features) || "all".equals(s.features)) {
			rt().setValue("elite.alert.enabled", "true");
		}
		for (ServerPlayer p : players) {
			setMode(p, s.elite ? PersonalDifficultyMode.ELITE : PersonalDifficultyMode.NORMAL);
		}
	}

	@GameTest(environment = PERF, maxTicks = WARMUP + 2 * 5 * WINDOW + 100)
	public void perf_tickTimeWith200MobsAnd5Players_baselineVersusEachEliteFeature(GameTestHelper h) {
		if (!Boolean.getBoolean("pd.perf")) {
			h.succeed(); // opt-in only: ./gradlew build -PpdPerf=true
			return;
		}
		MinecraftServer server = h.getLevel().getServer();
		Arena a = new Arena(h, 94);
		List<Mob> mobs = new ArrayList<>();
		for (int i = 0; i < ZOMBIES + SKELETONS; i++) {
			double x = 6 + (i % 20) * 5.5;
			double z = 6 + (i / 20) * 9.0;
			Mob m = a.spawn(i < ZOMBIES ? EntityTypes.ZOMBIE : EntityTypes.SKELETON, x, z);
			m.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET)); // nobody burns in daylight mid-measurement
			mobs.add(m);
		}
		List<ServerPlayer> players = new ArrayList<>();
		for (int i = 0; i < PLAYERS; i++) {
			ServerPlayer p = a.player(h, "pd_perf_" + i, PersonalDifficultyMode.NORMAL, 20 + i * 20, 50);
			p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100000, 4, false, false));
			players.add(p);
		}
		Setup[] order = Setup.values();
		Map<Setup, List<Long>> samples = new EnumMap<>(Setup.class);
		for (Setup s : order) {
			samples.put(s, new ArrayList<>());
		}
		Setup[] current = {Setup.NO_ELITE};
		apply(current[0], players);
		long start = h.getTick();
		everyTick(h, () -> {
			for (ServerPlayer p : players) {
				tickPlayer(p);
				p.setHealth(p.getMaxHealth()); // the measurement must not end because a player died
			}
			long t = h.getTick() - start;
			if (t < WARMUP) {
				return;
			}
			long inPhase = (t - WARMUP) % WINDOW;
			int window = (int) ((t - WARMUP) / WINDOW);
			if (window >= 2 * order.length) {
				return;
			}
			Setup s = order[window % order.length];
			if (inPhase == 0 && s != current[0]) {
				current[0] = s;
				apply(s, players);
			}
			if (inPhase >= SETTLE) {
				long[] times = server.getTickTimesNanos();
				samples.get(s).add(times[(server.getTickCount() + times.length - 1) % times.length]); // the tick that just finished
			}
		});
		at(h, WARMUP + 2L * order.length * WINDOW + 20, () -> {
			try {
				long alive = mobs.stream().filter(Mob::isAlive).count();
				Runtime r = Runtime.getRuntime();
				StringBuilder out = new StringBuilder();
				out.append("PERF scenario: ").append(ZOMBIES).append(" zombies + ").append(SKELETONS).append(" skeletons (").append(alive).append(" alive at the end), ")
					.append(PLAYERS).append(" players; ").append(WINDOW - SETTLE).append(" measured ticks per window, 2 windows per setup\n");
				out.append("PERF machine: ").append(r.availableProcessors()).append(" CPU threads, max heap ").append(r.maxMemory() >> 20).append(" MB, Java ")
					.append(System.getProperty("java.version")).append(", ").append(System.getProperty("os.name")).append('\n');
				double base = mean(samples.get(Setup.NO_ELITE));
				for (Setup s : order) {
					List<Long> v = samples.get(s);
					double mean = mean(v);
					out.append(String.format(Locale.ROOT, "PERF %-42s mean %6.2f ms  sd %5.2f  p95 %6.2f  (n=%d)  vs baseline %+6.2f ms%n",
						s.label, mean / 1e6, sd(v, mean) / 1e6, p95(v) / 1e6, v.size(), (mean - base) / 1e6));
				}
				double all = mean(samples.get(Setup.ELITE_ALL));
				out.append(String.format(Locale.ROOT, "PERF target (plan): everything new adds at most 1.00 ms per tick over the baseline -> measured %+.2f ms: %s",
					(all - base) / 1e6, (all - base) / 1e6 <= 1.0 ? "WITHIN TARGET" : "OVER TARGET"));
				System.out.println(out);
				check(alive > (ZOMBIES + SKELETONS) * 0.9, "the crowd survived the measurement (" + alive + " alive)");
			} finally {
				ConfigCommandGameTests.cleanOverrides();
				a.close();
			}
			h.succeed();
		});
	}

	private static double mean(List<Long> v) {
		return v.stream().mapToLong(Long::longValue).average().orElse(0);
	}

	private static double sd(List<Long> v, double mean) {
		return Math.sqrt(v.stream().mapToDouble(x -> (x - mean) * (x - mean)).average().orElse(0));
	}

	private static double p95(List<Long> v) {
		if (v.isEmpty()) {
			return 0;
		}
		List<Long> sorted = v.stream().sorted().toList();
		return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.95) - 1));
	}
}
