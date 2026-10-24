package com.zaggazach.personaldifficulty.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.zaggazach.personaldifficulty.PdRuntime;
import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.config.PdConfig;
import com.zaggazach.personaldifficulty.config.PdConfigLoader;
import java.io.IOException;
import java.util.Collection;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

/**
 * {@code /personaldifficulty [easy|normal|hard|inherit|status]} is self-service and needs no
 * permission; it only ever touches the executing player. Everything that can touch another player
 * or the server lives under {@code /personaldifficulty admin ...} and requires gamemaster (op 2).
 * The vanilla {@code /difficulty} command and the world difficulty are never touched.
 */
public final class PdCommands {
	public static final String ROOT = "personaldifficulty";

	private PdCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT).executes(PdCommands::status);
		root.then(Commands.literal("status").executes(PdCommands::status));
		for (PersonalDifficultyMode mode : PersonalDifficultyMode.values()) {
			root.then(Commands.literal(mode.commandName()).executes(ctx -> select(ctx, mode)));
		}
		root.then(
			Commands.literal("admin")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("config").executes(PdCommands::adminConfig))
				.then(Commands.literal("reload").executes(PdCommands::adminReload))
				.then(Commands.literal("status").then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(PdCommands::adminStatus)))
				.then(Commands.literal("reset").then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(PdCommands::adminReset)))
		);
		dispatcher.register(root);
	}

	private static PdRuntime runtime(CommandContext<CommandSourceStack> ctx) {
		return PdRuntime.get();
	}

	private static int select(CommandContext<CommandSourceStack> ctx, PersonalDifficultyMode mode) {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayer();
		PdRuntime rt = runtime(ctx);
		if (player == null) {
			source.sendFailure(Component.literal("Only a player can choose a personal difficulty. Use '/personaldifficulty admin ...' from the console."));
			return 0;
		}
		if (rt == null) {
			source.sendFailure(Component.literal("Personal Difficulty is not running."));
			return 0;
		}
		try {
			rt.settings().set(player.getUUID(), mode);
		} catch (IOException e) {
			source.sendFailure(Component.literal("Could not save your setting, so it was NOT changed (still " + rt.modeOf(player.getUUID()).commandName() + "): " + e.getMessage()));
			return 0;
		}
		if (rt.modeOf(player.getUUID()) != mode) {
			source.sendFailure(Component.literal("Your setting could not be applied."));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("Your personal difficulty is now " + mode.commandName().toUpperCase(Locale.ROOT) + ". " + describeEffect(rt.config(), mode)), false);
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		PdRuntime rt = runtime(ctx);
		if (rt == null) {
			source.sendFailure(Component.literal("Personal Difficulty is not running."));
			return 0;
		}
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendSuccess(() -> Component.literal(serverOverview(rt)), false);
			return 1;
		}
		PersonalDifficultyMode mode = rt.modeOf(player.getUUID());
		String world = player.level().getDifficulty().name();
		source.sendSuccess(() -> Component.literal(
			"Personal difficulty: " + mode.name() + (mode == PersonalDifficultyMode.INHERIT ? " (never chosen; follows the world)" : "")
				+ "\nWorld difficulty: " + world + " (unchanged by this mod)"
				+ "\n" + describeEffect(rt.config(), mode)
				+ "\nLimits: PvP/self/void/admin damage follow the exceptions in the config; only listed ranged mobs aim worse; other mods' hunger/damage code is not covered."
		), false);
		return 1;
	}

	private static String describeEffect(PdConfig c, PersonalDifficultyMode mode) {
		return switch (mode) {
			case INHERIT -> "No personal changes; the server's difficulty rules apply as-is.";
			case NORMAL -> "Explicit vanilla: no personal changes; the server's difficulty rules apply as-is.";
			case EASY -> String.format(Locale.ROOT,
				"Incoming damage x%.2f (applied once, after armour/enchantments), hunger x%.2f, ranged attackers aim with at least uncertainty %.1f. PvP x%.2f, self x%.2f, void %s, admin %s, environmental %s.",
				c.easy().damageMultiplier(), c.easy().hungerMultiplier(), c.easyRangedMinUncertainty(),
				c.pvpDamageMultiplier(), c.selfDamageMultiplier(), applied(c.voidApplyProfile()), applied(c.adminApplyProfile()), applied(c.environmentalApplyProfile()));
			case HARD -> String.format(Locale.ROOT,
				"Incoming damage x%.2f (on top of the world's own difficulty, applied once), hunger x%.2f. Ranged accuracy is not changed in Hard. PvP x%.2f, self x%.2f, void %s, admin %s, environmental %s.",
				c.hard().damageMultiplier(), c.hard().hungerMultiplier(),
				c.pvpDamageMultiplier(), c.selfDamageMultiplier(), applied(c.voidApplyProfile()), applied(c.adminApplyProfile()), applied(c.environmentalApplyProfile()));
		};
	}

	private static String applied(boolean on) {
		return on ? "scaled" : "unchanged";
	}

	private static String serverOverview(PdRuntime rt) {
		return "Personal Difficulty (server overview)"
			+ "\nExplicit player selections stored: " + rt.settings().explicitCount()
			+ "\nConfig file: " + rt.configFile()
			+ "\nEASY: " + describeEffect(rt.config(), PersonalDifficultyMode.EASY)
			+ "\nHARD: " + describeEffect(rt.config(), PersonalDifficultyMode.HARD);
	}

	// ---- administrative (op level 2) ----

	private static int adminConfig(CommandContext<CommandSourceStack> ctx) {
		PdRuntime rt = runtime(ctx);
		if (rt == null) {
			ctx.getSource().sendFailure(Component.literal("Personal Difficulty is not running."));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.literal(serverOverview(rt)), false);
		return 1;
	}

	private static int adminReload(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		PdRuntime rt = runtime(ctx);
		if (rt == null) {
			source.sendFailure(Component.literal("Personal Difficulty is not running."));
			return 0;
		}
		PdConfigLoader.Result result = rt.reload();
		if (result.readFailed()) {
			source.sendFailure(Component.literal("Config NOT reloaded (previous values kept): " + String.join("; ", result.warnings())));
			return 0;
		}
		String warn = result.warnings().isEmpty() ? "" : " Warnings: " + String.join("; ", result.warnings());
		source.sendSuccess(() -> Component.literal("Config reloaded; applies immediately to everyone, no reconnect needed." + warn), true);
		return 1;
	}

	private static int adminStatus(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		PdRuntime rt = runtime(ctx);
		if (rt == null) {
			ctx.getSource().sendFailure(Component.literal("Personal Difficulty is not running."));
			return 0;
		}
		Collection<NameAndId> targets = GameProfileArgument.getGameProfiles(ctx, "player");
		for (NameAndId p : targets) {
			PersonalDifficultyMode mode = rt.modeOf(p.id());
			ctx.getSource().sendSuccess(() -> Component.literal(p.name() + " (" + p.id() + "): " + mode.name()), false);
		}
		return targets.size();
	}

	private static int adminReset(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		PdRuntime rt = runtime(ctx);
		if (rt == null) {
			ctx.getSource().sendFailure(Component.literal("Personal Difficulty is not running."));
			return 0;
		}
		int done = 0;
		for (NameAndId p : GameProfileArgument.getGameProfiles(ctx, "player")) {
			if (!rt.settings().hasExplicit(p.id())) {
				ctx.getSource().sendSuccess(() -> Component.literal(p.name() + " (" + p.id() + ") has no stored selection; already INHERIT. Nothing changed."), false);
				continue;
			}
			try {
				rt.settings().set(p.id(), PersonalDifficultyMode.INHERIT);
				done++;
				ctx.getSource().sendSuccess(() -> Component.literal(p.name() + " reset to INHERIT."), true);
			} catch (IOException e) {
				ctx.getSource().sendFailure(Component.literal("Could not reset " + p.name() + " (unchanged): " + e.getMessage()));
			}
		}
		return done;
	}
}
