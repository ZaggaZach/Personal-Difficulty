package com.zaggazach.personaldifficulty.gametest;

import static com.zaggazach.personaldifficulty.gametest.PdTestSupport.*;

import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.gametest.EliteAiGameTests.Arena;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

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
		border(a);
		List<Mob> mobs = new ArrayList<>();
		for (int i = 0; i < ZOMBIES + SKELETONS; i++) {
			mobs.add(spawnCrowdMember(a, i));
		}
		Map<String, Integer> deaths = new TreeMap<>();
		int[] replaced = {0};
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
		java.util.Set<Mob> counted = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		Setup[] current = {Setup.NO_ELITE};
		apply(current[0], players);
		long start = h.getTick();
		everyTick(h, () -> {
			for (ServerPlayer p : players) {
				tickPlayer(p);
				p.setHealth(p.getMaxHealth()); // the measurement must not end because a player died
			}
			for (int i = 0; i < mobs.size(); i++) {
				Mob m = mobs.get(i);
				if (m.isDeadOrDying() && !counted.contains(m)) {
					counted.add(m);
					var src = m.getLastDamageSource();
					deaths.merge(src == null ? "unknown" : src.getMsgId(), 1, Integer::sum);
				}
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
			if (inPhase == 0) {
				// every window starts with the full crowd: a mob that died (the crowd crams, skeletons hit zombies) is replaced
				for (int i = 0; i < mobs.size(); i++) {
					if (!mobs.get(i).isAlive()) {
						mobs.set(i, spawnCrowdMember(a, i));
						replaced[0]++;
					}
				}
				if (s != current[0]) {
					current[0] = s;
					apply(s, players);
				}
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
					.append(PLAYERS).append(" players; ").append(replaced[0]).append(" replaced at window starts; deaths by cause ").append(deaths).append("; ").append(WINDOW - SETTLE).append(" measured ticks per window, 2 windows per setup\n");
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
				check(alive > (ZOMBIES + SKELETONS) / 2, "the crowd was still there at the end (" + alive + " alive; every window started with all of them)");
			} finally {
				ConfigCommandGameTests.cleanOverrides();
				a.close();
			}
			h.succeed();
		});
	}

	// ------------------------------------------------------------------ worst case: hunting a player who cannot be reached

	private static final String PERF_UNREACHABLE = "personaldifficulty-gametest:elite_perf_unreachable";
	private static final int HUNTERS = 30;

	/**
	 * Worst case for Elite path searches: every search for an UNREACHABLE target fails after using its whole node budget, every
	 * time the mob re-plans. {@value #HUNTERS} zombies around a player standing on top of an 8-block stone pillar (visible, never
	 * reachable). (A 9x node budget, tried for plan 6.2, measured +7.73 ms here and was removed.)
	 *
	 * <p>Two parts, windows alternating Normal / Elite in each:
	 * <ul>
	 * <li><b>far</b> (44-58 blocks): beyond vanilla range, so a Normal player is hunted by nobody and an Elite player by all. The
	 * difference is the cost of the FEATURE (more mobs hunting), not of any inefficiency.</li>
	 * <li><b>near</b> (18-26 blocks): inside vanilla range, so the zombies hunt the player either way (and fail either way). The
	 * difference is what the mod adds to a hunt that vanilla also does (the 3x search length, the Elite hooks). This one is judged
	 * against the 1 ms budget.</li>
	 * </ul>
	 * Opt-in like the main measurement; it fails only if the scenario broke (nobody hunted).
	 */
	@GameTest(environment = PERF_UNREACHABLE, maxTicks = WARMUP + 9 * WINDOW + 100)
	public void perf_unreachablePlayer_30HuntersThatCanNeverReachThem(GameTestHelper h) {
		if (!Boolean.getBoolean("pd.perf")) {
			h.succeed();
			return;
		}
		MinecraftServer server = h.getLevel().getServer();
		Arena a = new Arena(h, 99);
		border(a);
		int px = 90;
		int pz = 50;
		for (int dy = 1; dy <= 8; dy++) {
			a.level.setBlock(new BlockPos(a.x0 + px, a.y + dy, a.z0 + pz), Blocks.STONE.defaultBlockState(), 18);
		}
		List<Mob> mobs = new ArrayList<>();
		for (int i = 0; i < HUNTERS; i++) {
			double[] at = ring(i, 52 + (i % 3) * 3, 0.85, px, pz); // 44+ blocks: beyond vanilla range
			mobs.add(a.spawn(EntityTypes.ZOMBIE, at[0], at[1]));
		}
		ServerPlayer p = a.player(h, "pd_perf_pillar", PersonalDifficultyMode.NORMAL, px, pz);
		p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100000, 4, false, false));
		// [part 0 = far, 1 = near][0 = Normal, 1 = Elite]
		List<List<List<Long>>> samples = List.of(List.of(new ArrayList<>(), new ArrayList<>()), List.of(new ArrayList<>(), new ArrayList<>()));
		int[][] hunting = new int[2][2];
		int[][] counted = new int[2][2];
		long start = h.getTick();
		everyTick(h, () -> {
			tickPlayer(p);
			p.setHealth(p.getMaxHealth());
			p.snapTo(a.x0 + px + 0.5, a.y + 9, a.z0 + pz + 0.5); // stays on the pillar
			long t = h.getTick() - start;
			if (t < WARMUP) {
				return;
			}
			int window = (int) ((t - WARMUP) / WINDOW);
			long inPhase = (t - WARMUP) % WINDOW;
			if (window >= 9) {
				return;
			}
			// windows 0-3 far (N E N E), window 4 moves the crowd in (not measured), windows 5-8 near (N E N E)
			if (window == 4) {
				if (inPhase == 0) {
					setMode(p, PersonalDifficultyMode.NORMAL);
					for (int i = 0; i < mobs.size(); i++) {
						Mob m = mobs.get(i);
						m.setTarget(null);
						m.getNavigation().stop();
						double[] at = ring(i, 18 + (i % 3) * 4, 1.0, px, pz);
						m.snapTo(a.x0 + at[0], a.y + 1, a.z0 + at[1]);
					}
				}
				return;
			}
			int part = window < 4 ? 0 : 1;
			int lane = (window < 4 ? window : window - 5) % 2;
			if (inPhase == 0) {
				setMode(p, lane == 1 ? PersonalDifficultyMode.ELITE : PersonalDifficultyMode.NORMAL);
			}
			if (inPhase >= SETTLE) {
				long[] times = server.getTickTimesNanos();
				samples.get(part).get(lane).add(times[(server.getTickCount() + times.length - 1) % times.length]);
				counted[part][lane]++;
				hunting[part][lane] += (int) mobs.stream().filter(m -> m.isAlive() && m.getTarget() == p).count();
			}
		});
		at(h, WARMUP + 9L * WINDOW + 20, () -> {
			try {
				StringBuilder out = new StringBuilder();
				double nearDiff = 0;
				for (int part = 0; part < 2; part++) {
					String name = part == 0 ? "far 44-58 blocks" : "near 18-26 blocks";
					double[] mean = new double[2];
					for (int lane = 0; lane < 2; lane++) {
						List<Long> v = samples.get(part).get(lane);
						mean[lane] = mean(v);
						out.append(String.format(Locale.ROOT, "PERF-UNREACHABLE %-17s player %-6s mean %6.2f ms  sd %5.2f  p95 %6.2f  (n=%d)  zombies hunting %.1f%n",
							name, lane == 1 ? "Elite" : "Normal", mean[lane] / 1e6, sd(v, mean[lane]) / 1e6, p95(v) / 1e6, v.size(),
							counted[part][lane] == 0 ? 0.0 : hunting[part][lane] / (double) counted[part][lane]));
					}
					double diff = (mean[1] - mean[0]) / 1e6;
					out.append(String.format(Locale.ROOT, "PERF-UNREACHABLE %-17s difference %+6.2f ms%s%n", name, diff,
						part == 0 ? " (the feature: Elite players are hunted from further away)" : " (what the mod adds to a hunt vanilla also does)"));
					if (part == 1) {
						nearDiff = diff;
					}
				}
				out.append(String.format(Locale.ROOT, "PERF-UNREACHABLE target: the near difference at most +1.00 ms -> %s", nearDiff <= 1.0 ? "WITHIN TARGET" : "OVER TARGET"));
				System.out.println(out);
				double huntedFar = counted[0][1] == 0 ? 0 : hunting[0][1] / (double) counted[0][1];
				check(huntedFar > HUNTERS / 2.0, "premise: the zombies hunted the far Elite player (" + huntedFar + " on average)");
			} finally {
				a.close();
			}
			h.succeed();
		});
	}

	/** Position {@code i} of {@value #HUNTERS} on an arc on the far side of the pillar, {@code dist} out, z squeezed by {@code squeeze}. */
	private static double[] ring(int i, double dist, double squeeze, int px, int pz) {
		double angle = Math.PI * 0.6 + Math.PI * 0.8 * i / (HUNTERS - 1);
		return new double[] {px + Math.cos(angle) * dist, pz + Math.sin(angle) * dist * squeeze};
	}

	/** Crowd member {@code i} at its fixed starting spot (zombies first, then skeletons). */
	private static Mob spawnCrowdMember(Arena a, int i) {
		double x = 6 + (i % 20) * 5.5;
		double z = 6 + (i / 20) * 9.0;
		Mob m = a.spawn(i < ZOMBIES ? EntityTypes.ZOMBIE : EntityTypes.SKELETON, x, z);
		m.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET)); // nobody burns in daylight mid-measurement
		return m;
	}

	/**
	 * A wall round the platform's edge. The platform floats; in the first run (rc.3 build) 112 of the 200 mobs were pushed or
	 * knocked off its open edges by the crowd and the skeletons' arrows, so later windows measured fewer mobs than earlier ones.
	 */
	private static void border(Arena a) {
		BlockState stone = Blocks.STONE.defaultBlockState();
		for (int dy = 1; dy <= 4; dy++) {
			for (int x = -4; x <= 120; x++) {
				a.level.setBlock(new BlockPos(a.x0 + x, a.y + dy, a.z0 - 4), stone, 18);
				a.level.setBlock(new BlockPos(a.x0 + x, a.y + dy, a.z0 + 100), stone, 18);
			}
			for (int z = -4; z <= 100; z++) {
				a.level.setBlock(new BlockPos(a.x0 - 4, a.y + dy, a.z0 + z), stone, 18);
				a.level.setBlock(new BlockPos(a.x0 + 120, a.y + dy, a.z0 + z), stone, 18);
			}
		}
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
