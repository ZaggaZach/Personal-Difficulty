package com.zaggazach.personaldifficulty;

import com.zaggazach.personaldifficulty.command.PdCommands;
import com.zaggazach.personaldifficulty.config.ConfigState;
import com.zaggazach.personaldifficulty.config.DamageTypeCatalog;
import com.zaggazach.personaldifficulty.config.OverridesStore;
import com.zaggazach.personaldifficulty.config.PdConfigLoader;
import com.zaggazach.personaldifficulty.config.PropertiesLayer;
import com.zaggazach.personaldifficulty.store.AvailabilityPolicy;
import com.zaggazach.personaldifficulty.store.PlayerSettings;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.Registries;
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
			// layer 1 + 2: built-in defaults and the hand-edited properties file (created with comments on first start, never rewritten)
			PdConfigLoader.LayerResult propertiesResult = PdConfigLoader.loadOrCreateLayer(configFile);
			PropertiesLayer propertiesLayer = propertiesResult.readFailed() ? PdConfigLoader.layerOf(new Properties()) : propertiesResult.layer();
			// layer 3: the mod-managed overrides file. A corrupt or unsupported file never stops the server: the overrides are simply not applied
			DamageTypeCatalog damageTypes = () -> {
				try {
					return server.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).keySet().stream().map(Object::toString).toList();
				} catch (RuntimeException e) {
					return List.of(); // registry not available: nothing is rejected on that ground
				}
			};
			OverridesStore overrides = new OverridesStore(configDir.resolve(OverridesStore.FILE_NAME), OverridesStore.Io.REAL, System::currentTimeMillis, damageTypes::exists);
			overrides.load();
			ConfigState configState = ConfigState.compose(propertiesLayer, overrides.layer());
			for (String w : new LinkedHashSet<>(propertiesResult.warnings())) { // each distinct problem once per load
				LOGGER.warn("[config] {}", w);
			}
			if (propertiesResult.readFailed()) {
				LOGGER.error("Could not read {}; running with built-in defaults", configFile);
			}
			for (String w : overrides.loadWarnings()) {
				LOGGER.warn("[overrides] {}", w);
			}
			if (overrides.blockedReason() != null) {
				LOGGER.error("[overrides] THE OVERRIDES FILE IS NOT IN USE AND IS NOT WRITABLE: {}", overrides.blockedReason());
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

			PdRuntime.install(new PdRuntime(configFile, configState, overrides, damageTypes, settings, availability));
			LOGGER.info(
				"Personal Difficulty ready: {} non-default player selection(s) loaded from {} ({} legacy INHERIT/NORMAL entr(ies) read as NORMAL); selectable: {}; {} setting override(s) active",
				settings.explicitCount(), playersFile, settings.legacyNormalisedCount(), availability.enabledModes(), overrides.layer().size()
			);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> PdRuntime.install(null));
	}
}
