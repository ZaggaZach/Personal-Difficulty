package com.zaggazach.personaldifficulty.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.zaggazach.personaldifficulty.PdHooks;
import com.zaggazach.personaldifficulty.PdRuntime;
import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.config.PdConfig;
import com.zaggazach.personaldifficulty.store.AvailabilityPolicy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

/**
 * {@code /personaldifficulty [<difficulty>|status]} is self-service and needs no permission; it only
 * ever touches the executing player. Everything that can touch another player, the server policy or
 * the config lives under {@code /personaldifficulty admin ...} and requires gamemaster (op 2); the
 * {@code requires} check is enforced by Brigadier when the command is parsed, so it holds for players,
 * command blocks, {@code /execute} chains and the console alike (they all carry a permission set).
 *
 * <p>The difficulty is a validated word argument rather than one literal per mode so that (a) a disabled
 * or unknown difficulty yields a clear message instead of "unknown command", and (b) suggestions are
 * computed live from the availability policy and can never offer a disabled mode to a player.
 * The vanilla {@code /difficulty} command and the world difficulty are never touched.
 */
public final class PdCommands {
	public static final String ROOT = "personaldifficulty";

	private PdCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT).executes(PdCommands::status);
		root.then(Commands.literal("status").executes(PdCommands::status));
		root.then(Commands.argument("difficulty", StringArgumentType.word()).suggests(SELECTABLE).executes(PdCommands::select));
		root.then(
			Commands.literal("admin")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("config").executes(PdCommands::adminConfig))
				.then(Commands.literal("reload").executes(PdCommands::adminReload))
				.then(Commands.literal("difficulties").executes(PdCommands::adminDifficulties))
				.then(Commands.literal("enable").then(Commands.argument("difficulty", StringArgumentType.word()).suggests(ENABLEABLE).executes(ctx -> adminSetEnabled(ctx, true))))
				.then(Commands.literal("disable").then(Commands.argument("difficulty", StringArgumentType.word()).suggests(DISABLEABLE).executes(ctx -> adminSetEnabled(ctx, false))))
				.then(Commands.literal("status").then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(PdCommands::adminStatus)))
				.then(Commands.literal("reset").then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(PdCommands::adminReset)))
		);
		dispatcher.register(root);
	}

	// ------------------------------------------------------------------ suggestions

	/** What an ordinary player may pick right now: enabled difficulties only. */
	private static final SuggestionProvider<CommandSourceStack> SELECTABLE = (ctx, builder) -> {
		PdRuntime rt = PdRuntime.get();
		return SharedSuggestionProvider.suggest(rt == null ? List.<String>of() : rt.availability().enabledModes().stream().map(PersonalDifficultyMode::commandName).toList(), builder);
	};

	private static final SuggestionProvider<CommandSourceStack> ENABLEABLE = (ctx, builder) -> {
		PdRuntime rt = PdRuntime.get();
		List<String> out = new ArrayList<>();
		if (rt != null) {
			for (PersonalDifficultyMode m : PersonalDifficultyMode.values()) {
				if (!rt.availability().isEnabled(m)) {
					out.add(m.commandName());
				}
			}
			if (!out.isEmpty()) {
				out.add("all");
			}
		}
		return SharedSuggestionProvider.suggest(out, builder);
	};

	private static final SuggestionProvider<CommandSourceStack> DISABLEABLE = (ctx, builder) -> {
		PdRuntime rt = PdRuntime.get();
		return SharedSuggestionProvider.suggest(rt == null ? List.<String>of() : rt.availability().enabledModes().stream().map(PersonalDifficultyMode::commandName).toList(), builder);
	};

	// ------------------------------------------------------------------ helpers

	private static PdRuntime runtime() {
		return PdRuntime.get();
	}

	private static boolean notRunning(CommandSourceStack source, PdRuntime rt) {
		if (rt == null) {
			source.sendFailure(Component.literal("Personal Difficulty is not running."));
			return true;
		}
		return false;
	}

	private static String names(Collection<PersonalDifficultyMode> modes) {
		return modes.stream().map(PersonalDifficultyMode::commandName).collect(Collectors.joining(", "));
	}

	private static String upper(PersonalDifficultyMode m) {
		return m.name();
	}

	// ------------------------------------------------------------------ self-service

	private static int select(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		String typed = StringArgumentType.getString(ctx, "difficulty");
		PdRuntime rt = runtime();
		if (notRunning(source, rt)) {
			return 0;
		}
		Optional<PersonalDifficultyMode> parsed = PersonalDifficultyMode.parse(typed);
		if (parsed.isEmpty()) {
			String hint = typed.equalsIgnoreCase("inherit") ? " ('inherit' was removed in 1.1.0; 'normal' is the default)" : "";
			source.sendFailure(Component.literal("Unknown difficulty '" + typed + "'" + hint + ". Available: " + names(rt.availability().enabledModes()) + "."));
			return 0;
		}
		PersonalDifficultyMode mode = parsed.get();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.literal("Only a player can choose a personal difficulty. Use '/personaldifficulty admin ...' from the console."));
			return 0;
		}
		PersonalDifficultyMode current = rt.modeOf(player.getUUID());
		if (!rt.availability().isEnabled(mode)) {
			source.sendFailure(Component.literal(
				upper(mode) + " is currently disabled by the server, so it cannot be selected. Your difficulty is unchanged (" + upper(current)
					+ "). Available: " + names(rt.availability().enabledModes()) + "."
			));
			return 0;
		}
		if (current == mode) {
			source.sendSuccess(() -> Component.literal("Your personal difficulty is already " + upper(mode) + "; nothing changed."), false);
			return 1;
		}
		try {
			rt.settings().set(player.getUUID(), mode);
		} catch (IOException e) {
			source.sendFailure(Component.literal("Could not save your setting, so it was NOT changed (still " + upper(rt.modeOf(player.getUUID())) + "): " + e.getMessage()));
			return 0;
		}
		if (rt.modeOf(player.getUUID()) != mode) {
			source.sendFailure(Component.literal("Your setting could not be applied."));
			return 0;
		}
		PdHooks.onModeChanged(player, current, mode);
		source.sendSuccess(() -> Component.literal("Your personal difficulty is now " + upper(mode) + ". " + describeEffect(rt.config(), mode)), false);
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		PdRuntime rt = runtime();
		if (notRunning(source, rt)) {
			return 0;
		}
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendSuccess(() -> Component.literal(serverOverview(rt)), false);
			return 1;
		}
		PersonalDifficultyMode mode = rt.modeOf(player.getUUID());
		String world = player.level().getDifficulty().name();
		String restricted = rt.availability().isEnabled(mode) ? ""
			: "\nNote: " + upper(mode) + " is currently disabled by the server for NEW selections. Your existing selection is kept, but once you switch away you cannot switch back.";
		source.sendSuccess(() -> Component.literal(
			"Personal difficulty: " + upper(mode)
				+ "\nWorld difficulty: " + world + " (unchanged by this mod)"
				+ "\n" + describeEffect(rt.config(), mode)
				+ restricted
				+ "\nYou can select: " + names(rt.availability().enabledModes())
		), false);
		return 1;
	}

	static String describeEffect(PdConfig c, PersonalDifficultyMode mode) {
		return switch (mode) {
			case NORMAL -> "No personal changes; the server's difficulty rules apply as-is.";
			case EASY -> String.format(Locale.ROOT,
				"Incoming damage x%.2f (applied once, after armour/enchantments), hunger x%.2f, ranged attackers aim with at least uncertainty %.1f. PvP x%.2f, self x%.2f, void %s, admin %s, environmental %s.",
				c.easy().damageMultiplier(), c.easy().hungerMultiplier(), c.easyRangedMinUncertainty(),
				c.pvpDamageMultiplier(), c.selfDamageMultiplier(), applied(c.voidApplyProfile()), applied(c.adminApplyProfile()), applied(c.environmentalApplyProfile()));
			case HARD -> String.format(Locale.ROOT,
				"Incoming damage x%.2f (on top of the world's own difficulty, applied once), hunger x%.2f. Ranged accuracy is not changed in Hard. PvP x%.2f, self x%.2f, void %s, admin %s, environmental %s.",
				c.hard().damageMultiplier(), c.hard().hungerMultiplier(),
				c.pvpDamageMultiplier(), c.selfDamageMultiplier(), applied(c.voidApplyProfile()), applied(c.adminApplyProfile()), applied(c.environmentalApplyProfile()));
			case ELITE -> String.format(Locale.ROOT,
				"Armour is only %.0f%% as effective, then incoming damage x%.2f (after resistance/enchantments, before absorption; each applied once), hunger x%.2f, supported ranged attackers aim with at most uncertainty %.2f. PvP x%.2f, self x%.2f, void %s, admin %s, environmental %s.",
				c.eliteArmorEffectiveness() * 100, c.elite().damageMultiplier(), c.elite().hungerMultiplier(), c.eliteRangedMaxUncertainty(),
				c.pvpDamageMultiplier(), c.selfDamageMultiplier(), applied(c.voidApplyProfile()), applied(c.adminApplyProfile()), applied(c.environmentalApplyProfile()));
			case PEACEFUL -> "You take no damage except from the void and /kill, hostile mobs will not target you, you lose no hunger, harmful effects are blocked, and you cannot damage other players (nor they you).";
		};
	}

	private static String applied(boolean on) {
		return on ? "scaled" : "unchanged";
	}

	private static String serverOverview(PdRuntime rt) {
		return "Personal Difficulty (server overview)"
			+ "\nPlayers with a non-default selection: " + rt.settings().explicitCount()
			+ "\nConfig file: " + rt.configFile()
			+ "\nSelectable difficulties: " + names(rt.availability().enabledModes())
			+ "\nEASY: " + describeEffect(rt.config(), PersonalDifficultyMode.EASY)
			+ "\nHARD: " + describeEffect(rt.config(), PersonalDifficultyMode.HARD)
			+ "\nELITE: " + describeEffect(rt.config(), PersonalDifficultyMode.ELITE);
	}

	// ------------------------------------------------------------------ administrative (op level 2)

	private static int adminConfig(CommandContext<CommandSourceStack> ctx) {
		PdRuntime rt = runtime();
		if (notRunning(ctx.getSource(), rt)) {
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.literal(serverOverview(rt)), false);
		return 1;
	}

	private static int adminReload(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		PdRuntime rt = runtime();
		if (notRunning(source, rt)) {
			return 0;
		}
		PdRuntime.Reload result = rt.reload();
		List<String> problems = new ArrayList<>(result.config().warnings());
		problems.addAll(result.availability().warnings());
		if (result.anyFailed()) {
			String which = (result.config().readFailed() ? "config" : "") + (result.config().readFailed() && result.availability().failed() ? " and " : "") + (result.availability().failed() ? "availability policy" : "");
			source.sendFailure(Component.literal("Reload incomplete: the " + which + " could not be applied, so the previous values stay in force. " + String.join("; ", problems)));
			return 0;
		}
		String warn = problems.isEmpty() ? "" : " Warnings: " + String.join("; ", problems);
		source.sendSuccess(() -> Component.literal("Config and difficulty availability reloaded; applies immediately to everyone, no reconnect needed." + warn), true);
		return 1;
	}

	private static int adminDifficulties(CommandContext<CommandSourceStack> ctx) {
		PdRuntime rt = runtime();
		if (notRunning(ctx.getSource(), rt)) {
			return 0;
		}
		StringBuilder sb = new StringBuilder("Difficulty availability (a restriction only blocks NEW selections; saved selections are never changed):");
		for (PersonalDifficultyMode m : PersonalDifficultyMode.values()) {
			boolean on = rt.availability().isEnabled(m);
			sb.append("\n  ").append(m.commandName()).append(": ").append(on ? "ENABLED" : "DISABLED");
			long using = rt.settings().countUsing(m);
			if (!on && using > 0) {
				sb.append(" (").append(using).append(" player(s) still use it; kept)");
			}
		}
		sb.append("\nPlayers can select: ").append(names(rt.availability().enabledModes()));
		ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
		return 1;
	}

	private static int adminSetEnabled(CommandContext<CommandSourceStack> ctx, boolean enable) {
		CommandSourceStack source = ctx.getSource();
		PdRuntime rt = runtime();
		if (notRunning(source, rt)) {
			return 0;
		}
		String typed = StringArgumentType.getString(ctx, "difficulty");
		AvailabilityPolicy policy = rt.availability();
		try {
			if (enable && typed.equalsIgnoreCase("all")) {
				AvailabilityPolicy.Change change = policy.enableAll();
				if (change == AvailabilityPolicy.Change.UNCHANGED) {
					source.sendSuccess(() -> Component.literal("Every difficulty is already enabled; nothing changed."), false);
					return 1;
				}
				source.sendSuccess(() -> Component.literal("All difficulties are enabled again. Players can select: " + names(policy.enabledModes()) + "."), true);
				return 1;
			}
			Optional<PersonalDifficultyMode> parsed = PersonalDifficultyMode.parse(typed);
			if (parsed.isEmpty()) {
				source.sendFailure(Component.literal("Unknown difficulty '" + typed + "'. Known: " + names(List.of(PersonalDifficultyMode.values())) + (enable ? ", all" : "") + "."));
				return 0;
			}
			PersonalDifficultyMode mode = parsed.get();
			AvailabilityPolicy.Change change = policy.setEnabled(mode, enable);
			switch (change) {
				case UNCHANGED -> {
					source.sendSuccess(() -> Component.literal(upper(mode) + " is already " + (enable ? "enabled" : "disabled") + "; nothing changed."), false);
					return 1;
				}
				case REFUSED_LAST_ENABLED -> {
					source.sendFailure(Component.literal("Refused: " + upper(mode) + " is the last enabled difficulty, and at least one must stay selectable. Nothing changed."));
					return 0;
				}
				case CHANGED -> {
					long using = rt.settings().countUsing(mode);
					String tail = enable
						? " It can be selected again now (no restart needed)."
						: " Players cannot newly select it; the " + using + " saved selection(s) using it are kept, and those players keep playing on it until they switch away.";
					source.sendSuccess(() -> Component.literal(upper(mode) + " is now " + (enable ? "ENABLED" : "DISABLED") + "." + tail), true);
					return 1;
				}
			}
			return 1;
		} catch (IOException e) {
			source.sendFailure(Component.literal("Could not save the availability policy, so nothing was changed: " + e.getMessage()));
			return 0;
		}
	}

	private static int adminStatus(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		PdRuntime rt = runtime();
		if (notRunning(ctx.getSource(), rt)) {
			return 0;
		}
		Collection<NameAndId> targets = GameProfileArgument.getGameProfiles(ctx, "player");
		for (NameAndId p : targets) {
			PersonalDifficultyMode mode = rt.modeOf(p.id());
			String restricted = rt.availability().isEnabled(mode) ? "" : " (currently disabled for new selections; kept)";
			ctx.getSource().sendSuccess(() -> Component.literal(p.name() + " (" + p.id() + "): " + upper(mode) + restricted), false);
		}
		return targets.size();
	}

	private static int adminReset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		PdRuntime rt = runtime();
		if (notRunning(ctx.getSource(), rt)) {
			return 0;
		}
		int done = 0;
		for (NameAndId p : GameProfileArgument.getGameProfiles(ctx, "player")) {
			if (!rt.settings().hasExplicit(p.id())) {
				ctx.getSource().sendSuccess(() -> Component.literal(p.name() + " (" + p.id() + ") has no stored selection; already NORMAL. Nothing changed."), false);
				continue;
			}
			try {
				PersonalDifficultyMode before = rt.modeOf(p.id());
				rt.settings().set(p.id(), PersonalDifficultyMode.NORMAL);
				done++;
				ctx.getSource().sendSuccess(() -> Component.literal(p.name() + " reset to NORMAL (was " + upper(before) + ")."), true);
			} catch (IOException e) {
				ctx.getSource().sendFailure(Component.literal("Could not reset " + p.name() + " (unchanged): " + e.getMessage()));
			}
		}
		return done;
	}
}
