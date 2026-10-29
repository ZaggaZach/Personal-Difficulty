package com.zaggazach.personaldifficulty.gametest;

import com.zaggazach.personaldifficulty.PdRuntime;
import com.zaggazach.personaldifficulty.config.PdConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/**
 * Every GameTest run starts from the shipped defaults. The run directory persists between runs, so without this its
 * {@code personaldifficulty.properties} would still hold an earlier build's template (the mod never edits an existing file, by
 * design) and an overrides file left by an interrupted run would still apply. Once the server has started, before any test
 * runs, the file is rewritten from the current template and the overrides are removed.
 */
public final class GameTestBootstrap implements ModInitializer {
	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			PdRuntime rt = PdRuntime.get();
			if (rt == null) {
				return;
			}
			try {
				Files.writeString(rt.configFile(), PdConfigLoader.defaultFileContents());
			} catch (IOException e) {
				throw new IllegalStateException("could not reset the test configuration", e);
			}
			ConfigCommandGameTests.cleanOverrides(); // also reloads
		});
	}
}
