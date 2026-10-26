package com.zaggazach.personaldifficulty.e2e.fixture;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;

/** Test-only: registers {@code /pdfixture menu}, which opens {@link FixtureMenu} for the calling player. */
public final class FixtureMod implements ModInitializer {
	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			Commands.literal("pdfixture").then(Commands.literal("menu").executes(ctx -> {
				var player = ctx.getSource().getPlayer();
				if (player == null) {
					return 0;
				}
				FixtureMenu.open(player);
				return 1;
			}))));
	}
}
