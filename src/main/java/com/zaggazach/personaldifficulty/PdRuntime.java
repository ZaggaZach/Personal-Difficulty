package com.zaggazach.personaldifficulty;

import com.zaggazach.personaldifficulty.config.PdConfig;
import com.zaggazach.personaldifficulty.config.PdConfigLoader;
import com.zaggazach.personaldifficulty.store.AvailabilityPolicy;
import com.zaggazach.personaldifficulty.store.PlayerSettings;
import java.nio.file.Path;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Everything the mixins and commands need for the running server: the validated config, the server's
 * availability policy and the per-player selections.
 *
 * <p>There is exactly one server per JVM (dedicated or integrated), so a single volatile "current"
 * reference is created in SERVER_STARTING and dropped in SERVER_STOPPED. It carries no per-player
 * or per-world state of its own beyond the {@link PlayerSettings} that belong to that server's world.
 * Before startup / after shutdown mixins see {@code null} and leave vanilla behaviour untouched.
 */
public final class PdRuntime {
	private static volatile @Nullable PdRuntime current;

	/** Result of {@link #reload()}: config and availability are re-read independently. */
	public record Reload(PdConfigLoader.Result config, AvailabilityPolicy.Parsed availability) {
		public boolean anyFailed() {
			return config.readFailed() || availability.failed();
		}
	}

	private final Path configFile;
	private volatile PdConfig config;
	private final PlayerSettings settings;
	private final AvailabilityPolicy availability;

	public PdRuntime(Path configFile, PdConfig config, PlayerSettings settings, AvailabilityPolicy availability) {
		this.configFile = configFile;
		this.config = config;
		this.settings = settings;
		this.availability = availability;
	}

	public static @Nullable PdRuntime get() {
		return current;
	}

	public static void install(@Nullable PdRuntime runtime) {
		current = runtime;
	}

	public PdConfig config() {
		return config;
	}

	public PlayerSettings settings() {
		return settings;
	}

	public AvailabilityPolicy availability() {
		return availability;
	}

	public PersonalDifficultyMode modeOf(UUID player) {
		return settings.get(player);
	}

	public Path configFile() {
		return configFile;
	}

	/**
	 * Re-reads the config file and the availability file. Each is applied only if it read cleanly;
	 * otherwise the previous values stay in force.
	 */
	public Reload reload() {
		PdConfigLoader.Result result = PdConfigLoader.load(configFile);
		if (!result.readFailed()) {
			this.config = result.config();
		}
		return new Reload(result, availability.reload());
	}
}
