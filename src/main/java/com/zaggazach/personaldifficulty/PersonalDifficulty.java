package com.zaggazach.personaldifficulty;

import com.zaggazach.personaldifficulty.command.PdCommands;
import com.zaggazach.personaldifficulty.config.PdConfigLoader;
import com.zaggazach.personaldifficulty.store.AvailabilityPolicy;
import com.zaggazach.personaldifficulty.store.PlayerSettings;
import java.nio.file.Path;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
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

		// Peaceful: cancel the hit before any vanilla processing (no knockback, sound, effects or health change).
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> PdHooks.allowDamage(entity, source));

		// Elite combat effects: observe damage that really happened (never fires for cancelled/Peaceful hits)
		ServerLivingEntityEvents.AFTER_DAMAGE.register(PdMobEffects::afterDamage);

		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			Path configDir = FabricLoader.getInstance().getConfigDir();
			Path configFile = configDir.resolve(PdConfigLoader.FILE_NAME);
			PdConfigLoader.Result config = PdConfigLoader.loadOrCreate(configFile);
			config.warnings().forEach(w -> LOGGER.warn("[config] {}", w));
			if (config.readFailed()) {
				LOGGER.error("Could not read {}; running with built-in defaults", configFile);
			}

			AvailabilityPolicy availability = new AvailabilityPolicy(configDir.resolve(AvailabilityPolicy.FILE_NAME));
			availability.load();
			availability.loadWarnings().forEach(w -> LOGGER.warn("[availability] {}", w));

			Path playersFile = server.getWorldPath(LevelResource.ROOT).resolve(MOD_ID).resolve("players.json");
			PlayerSettings settings = new PlayerSettings(playersFile);
			settings.load();
			settings.loadWarnings().forEach(w -> LOGGER.warn("[players] {}", w));
			if (settings.writeBlockedReason() != null) {
				LOGGER.error("[players] PLAYER SETTINGS ARE NOT LOADED/WRITABLE: {}", settings.writeBlockedReason());
			}
			if (settings.quarantinedCount() > 0) {
				LOGGER.error("[players] {} player(s) have an invalid stored record, kept verbatim and treated as locked; see '/personaldifficulty admin status <player>'", settings.quarantinedCount());
			}

			PdRuntime.install(new PdRuntime(configFile, config.config(), settings, availability));
			LOGGER.info(
				"Personal Difficulty ready: {} non-default player selection(s) loaded from {} ({} legacy INHERIT/NORMAL entr(ies) read as NORMAL); selectable: {}",
				settings.explicitCount(), playersFile, settings.legacyNormalisedCount(), availability.enabledModes()
			);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> PdRuntime.install(null));
	}
}
