package com.zaggazach.personaldifficulty;

import com.zaggazach.personaldifficulty.config.PdConfig;
import com.zaggazach.personaldifficulty.config.PdConfigLoader;
import com.zaggazach.personaldifficulty.store.PlayerSettings;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Everything the mixins and commands need for the running server: the validated config and the
 * per-player selections.
 *
 * <p>There is exactly one server per JVM (dedicated or integrated), so a single volatile "current"
 * reference is created in SERVER_STARTING and dropped in SERVER_STOPPED. It carries no per-player
 * or per-world state of its own beyond the {@link PlayerSettings} that belong to that server's world.
 * Before startup / after shutdown mixins see {@code null} and leave vanilla behaviour untouched.
 */
public final class PdRuntime {
	private static volatile @Nullable PdRuntime current;

	private final Path configFile;
	private volatile PdConfig config;
	private final PlayerSettings settings;

	public PdRuntime(Path configFile, PdConfig config, PlayerSettings settings) {
		this.configFile = configFile;
		this.config = config;
		this.settings = settings;
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

	public PersonalDifficultyMode modeOf(UUID player) {
		return settings.get(player);
	}

	public Path configFile() {
		return configFile;
	}

	/** Re-reads the config file. On a read failure the previous config is kept. */
	public PdConfigLoader.Result reload() {
		PdConfigLoader.Result result = PdConfigLoader.load(configFile);
		if (!result.readFailed()) {
			this.config = result.config();
		}
		return result;
	}

	public static List<String> startupWarnings(PdConfigLoader.Result result) {
		return result.warnings();
	}
}
