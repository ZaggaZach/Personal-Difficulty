package com.zaggazach.personaldifficulty;

import com.zaggazach.personaldifficulty.command.PdCommands;
import com.zaggazach.personaldifficulty.config.PdConfigLoader;
import com.zaggazach.personaldifficulty.store.PlayerSettings;
import java.nio.file.Path;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PersonalDifficulty implements ModInitializer {
	public static final String MOD_ID = "personaldifficulty";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> PdCommands.register(dispatcher));

		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			Path configFile = FabricLoader.getInstance().getConfigDir().resolve(PdConfigLoader.FILE_NAME);
			PdConfigLoader.Result config = PdConfigLoader.loadOrCreate(configFile);
			config.warnings().forEach(w -> LOGGER.warn("[config] {}", w));
			if (config.readFailed()) {
				LOGGER.error("Could not read {}; running with built-in defaults", configFile);
			}

			Path playersFile = server.getWorldPath(LevelResource.ROOT).resolve(MOD_ID).resolve("players.json");
			PlayerSettings settings = new PlayerSettings(playersFile);
			settings.load();
			settings.loadWarnings().forEach(w -> LOGGER.warn("[players] {}", w));

			PdRuntime.install(new PdRuntime(configFile, config.config(), settings));
			LOGGER.info("Personal Difficulty ready: {} explicit player selection(s) loaded from {}", settings.explicitCount(), playersFile);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> PdRuntime.install(null));
	}
}
