package com.zaggazach.personaldifficulty.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.zaggazach.personaldifficulty.PdHooks;
import com.zaggazach.personaldifficulty.PdPermissions;
import com.zaggazach.personaldifficulty.PdRuntime;
import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.config.ConfigReport;
import com.zaggazach.personaldifficulty.config.ConfigState;
import com.zaggazach.personaldifficulty.config.FamilyDef;
import com.zaggazach.personaldifficulty.config.FamilyKey;
import com.zaggazach.personaldifficulty.config.OverridesLayer;
import com.zaggazach.personaldifficulty.config.PdConfig;
import com.zaggazach.personaldifficulty.config.SetResult;
import com.zaggazach.personaldifficulty.config.SettingDef;
import com.zaggazach.personaldifficulty.config.SettingRegistry;
import com.zaggazach.personaldifficulty.config.StrictValue;
import com.zaggazach.personaldifficulty.logic.EliteSurvival;
import com.zaggazach.personaldifficulty.logic.SelectionPolicy;
import com.zaggazach.personaldifficulty.store.AvailabilityPolicy;
import com.zaggazach.personaldifficulty.store.PlayerRecord;
import com.zaggazach.personaldifficulty.store.PlayerSettings;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
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
				.requires(PdPermissions.adminRequirement())
				.then(Commands.literal("config").executes(PdCommands::adminConfig)
					.then(Commands.argument("prefix", StringArgumentType.word()).suggests(CONFIG_PREFIXES).executes(PdCommands::adminConfig)))
				.then(Commands.literal("get").then(Commands.argument("key", StringArgumentType.word()).suggests(SETTING_KEYS).executes(PdCommands::adminGet)))
				.then(Commands.literal("set").then(Commands.argument("key", StringArgumentType.word()).suggests(SETTING_KEYS)
					.then(Commands.argument("value", StringArgumentType.word()).suggests(VALUE_HINTS).executes(PdCommands::adminSet))))
				.then(Commands.literal("unset").then(Commands.argument("key", StringArgumentType.word()).suggests(OVERRIDDEN_KEYS).executes(PdCommands::adminUnset)))
				.then(Commands.literal("reload").executes(PdCommands::adminReload))
				.then(Commands.literal("difficulties").executes(PdCommands::adminDifficulties))
				.then(Commands.literal("enable").then(Commands.argument("difficulty", StringArgumentType.word()).suggests(ENABLEABLE).executes(ctx -> adminSetEnabled(ctx, true))))
				.then(Commands.literal("disable").then(Commands.argument("difficulty", StringArgumentType.word()).suggests(DISABLEABLE).executes(ctx -> adminSetEnabled(ctx, false))))
				.then(Commands.literal("status").then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(PdCommands::adminStatus)))
				.then(Commands.literal("reset").then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(PdCommands::adminReset)))
				.then(Commands.literal("assign").then(Commands.argument("player", GameProfileArgument.gameProfile())
					.then(Commands.argument("difficulty", StringArgumentType.word()).suggests(ALL_MODES)
						.executes(ctx -> adminAssign(ctx, false))
						.then(Commands.literal("lock").executes(ctx -> adminAssign(ctx, true))))))
				.then(Commands.literal("lock").then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(ctx -> adminSetLock(ctx, true))))
				.then(Commands.literal("unlock").then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(ctx -> adminSetLock(ctx, false))))
				.then(Commands.literal("restrict").then(Commands.argument("player", GameProfileArgument.gameProfile())
					.then(Commands.argument("modes", StringArgumentType.greedyString()).suggests(RESTRICT_ARGS).executes(PdCommands::adminRestrict))))
				.then(Commands.literal("unrestrict").then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(PdCommands::adminUnrestrict)))
		);
		dispatcher.register(root);
	}

	// ------------------------------------------------------------------ suggestions

	/** What an ordinary player may pick right now: enabled difficulties only. */
	private static final SuggestionProvider<CommandSourceStack> SELECTABLE = (ctx, builder) -> {
		PdRuntime rt = PdRuntime.get();
		if (rt == null) {
			return SharedSuggestionProvider.suggest(List.<String>of(), builder);
		}
		ServerPlayer player = ctx.getSource().getPlayer();
		Collection<PersonalDifficultyMode> offered = player == null
			? rt.availability().enabledModes()
			: SelectionPolicy.selectable(rt.settings().record(player.getUUID()), rt.availability().enabledModes());
		return SharedSuggestionProvider.suggest(offered.stream().map(PersonalDifficultyMode::commandName).toList(), builder);
	};

	/** Administrators can assign any mode, whatever the global policy says. */
	private static final SuggestionProvider<CommandSourceStack> ALL_MODES = (ctx, builder) ->
		SharedSuggestionProvider.suggest(Arrays.stream(PersonalDifficultyMode.values()).map(PersonalDifficultyMode::commandName).toList(), builder);

	/** {@code <mode>[,<mode>...] [switch|keep]}: completes the mode being typed, then the flag. */
	private static final SuggestionProvider<CommandSourceStack> RESTRICT_ARGS = (ctx, builder) -> {
		String typed = builder.getRemaining();
		int space = typed.lastIndexOf(' ');
		if (space >= 0) {
			return SharedSuggestionProvider.suggest(List.of("switch", "keep"), builder.createOffset(builder.getStart() + space + 1));
		}
		int comma = typed.lastIndexOf(',');
		return SharedSuggestionProvider.suggest(Arrays.stream(PersonalDifficultyMode.values()).map(PersonalDifficultyMode::commandName).toList(), builder.createOffset(builder.getStart() + comma + 1));
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

	/** Setting keys; the damage-type family is offered as its prefixes and, once a prefix is typed, as the live server's damage types. */
	private static final SuggestionProvider<CommandSourceStack> SETTING_KEYS = (ctx, builder) -> {
		PdRuntime rt = PdRuntime.get();
		List<String> out = new ArrayList<>();
		if (rt != null) {
			SettingRegistry.scalars().forEach(d -> out.add(d.key()));
			String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
			for (FamilyDef f : SettingRegistry.families()) {
				out.add(f.prefix());
				if (typed.startsWith(f.prefix())) {
					rt.damageTypes().ids().forEach(id -> out.add(f.prefix() + id.replace(':', '.')));
				}
			}
		}
		return SharedSuggestionProvider.suggest(out, builder);
	};

	/** Keys that currently have an override (what {@code unset} can remove). */
	private static final SuggestionProvider<CommandSourceStack> OVERRIDDEN_KEYS = (ctx, builder) -> {
		PdRuntime rt = PdRuntime.get();
		List<String> out = new ArrayList<>();
		if (rt != null) {
			OverridesLayer layer = rt.overrides().layer();
			out.addAll(layer.scalars().keySet());
			layer.family().forEach((profile, entries) -> entries.keySet().forEach(id -> out.add(profile + ".damage-type." + id.replace(':', '.'))));
		}
		return SharedSuggestionProvider.suggest(out, builder);
	};

	private static final SuggestionProvider<CommandSourceStack> CONFIG_PREFIXES = (ctx, builder) ->
		SharedSuggestionProvider.suggest(List.of("easy.", "hard.", "elite.", "elite.ai.", "elite.effects.", "elite.priority.", "pvp.", "self.", "ranged.", "environmental.", "void.", "admin."), builder);

	/** Value hints for {@code admin set <key> <value>}: true/false, or the current value, the default and the bounds. */
	private static final SuggestionProvider<CommandSourceStack> VALUE_HINTS = (ctx, builder) -> {
		PdRuntime rt = PdRuntime.get();
		List<String> out = new ArrayList<>();
		try {
			String key = StringArgumentType.getString(ctx, "key");
			Optional<SettingDef> def = SettingRegistry.find(key);
			if (rt != null && def.isPresent()) {
				SettingDef d = def.get();
				if (d.type() == SettingDef.Type.BOOL) {
					out.addAll(List.of("true", "false"));
				} else {
					out.add(StrictValue.format(rt.state().effectiveValue(d)));
					out.add(StrictValue.format(d.defaultValue()));
					out.add(d.type() == SettingDef.Type.INT ? Integer.toString((int) d.min()) : Double.toString(d.min()));
					out.add(d.type() == SettingDef.Type.INT ? Integer.toString((int) d.max()) : Double.toString(d.max()));
				}
			} else if (rt != null) {
				out.addAll(List.of("0.5", "1.0", "2.0"));
			}
		} catch (RuntimeException ignored) {
			// no key typed yet: nothing to hint
		}
		return SharedSuggestionProvider.suggest(out.stream().distinct().toList(), builder);
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
		return modes.stream().sorted().map(PersonalDifficultyMode::commandName).collect(Collectors.joining(", "));
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
		PlayerRecord record = rt.settings().record(player.getUUID());
		PersonalDifficultyMode current = record.mode();
		// R8: an operator choosing their own difficulty is an ordinary player; only 'admin assign' overrides.
		SelectionPolicy.Actor actor = PdPermissions.canAdminister(player) ? SelectionPolicy.Actor.OPERATOR_SELF : SelectionPolicy.Actor.PLAYER;
		SelectionPolicy.Verdict verdict = SelectionPolicy.decide(actor, record, mode, rt.availability().enabledModes());
		Set<PersonalDifficultyMode> selectable = SelectionPolicy.selectable(record, rt.availability().enabledModes());
		switch (verdict) {
			case REFUSED_INVALID_RECORD -> {
				source.sendFailure(Component.literal(
					"Your saved difficulty settings could not be read, so they cannot be changed until an administrator repairs them. Your difficulty is treated as locked on "
						+ upper(current) + ". (Ask an administrator to check '/personaldifficulty admin status " + player.getName().getString() + "'.)"
				));
				return 0;
			}
			case REFUSED_LOCKED -> {
				source.sendFailure(Component.literal(
					"Your personal difficulty is locked by an administrator, so you cannot change it. Only an administrator can unlock it. Your difficulty is unchanged (" + upper(current) + ")."
				));
				return 0;
			}
			case REFUSED_DISABLED -> {
				source.sendFailure(Component.literal(
					upper(mode) + " is currently disabled by the server, so it cannot be selected. Your difficulty is unchanged (" + upper(current)
						+ "). Available: " + names(selectable) + "."
				));
				return 0;
			}
			case REFUSED_NOT_IN_ALLOW_LIST -> {
				source.sendFailure(Component.literal(
					upper(mode) + " is not in the list of difficulties an administrator allows you, so it cannot be selected. Your difficulty is unchanged (" + upper(current)
						+ "). Available to you: " + names(selectable) + "."
				));
				return 0;
			}
			case UNCHANGED -> {
				source.sendSuccess(() -> Component.literal("Your personal difficulty is already " + upper(mode) + "; nothing changed."), false);
				return 1;
			}
			case ALLOWED -> {
				// proceed
			}
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
		PlayerRecord record = rt.settings().record(player.getUUID());
		PersonalDifficultyMode mode = record.mode();
		String world = player.level().getDifficulty().name();
		String restricted = rt.availability().isEnabled(mode) ? ""
			: "\nNote: " + upper(mode) + " is currently disabled by the server for NEW selections. Your existing selection is kept, but once you switch away you cannot switch back.";
		String lock = record.quarantined()
			? "\nYour saved settings could not be read, so your difficulty is treated as locked until an administrator repairs them."
			: record.locked() ? "\nYour difficulty is locked by an administrator; only an administrator can change it." : "";
		String allowList = record.allowed() == null ? ""
			: "\nAn administrator limits you to: " + names(record.allowed()) + (record.isException() ? " (your current " + upper(mode) + " is a kept exception; once you switch away you cannot switch back)." : ".");
		Set<PersonalDifficultyMode> selectable = SelectionPolicy.selectable(record, rt.availability().enabledModes());
		source.sendSuccess(() -> Component.literal(
			"Personal difficulty: " + upper(mode)
				+ "\nWorld difficulty: " + world + " (unchanged by this mod)"
				+ "\n" + describeEffect(rt.config(), mode)
				+ restricted
				+ lock
				+ allowList
				+ "\nYou can select: " + (selectable.isEmpty() ? "nothing right now" : names(selectable))
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
				"Armour is only %.0f%% as effective, then incoming damage x%.2f (after resistance/enchantments, before absorption; each applied once), hunger x%.2f, supported ranged attackers aim with at most uncertainty %.2f, natural regeneration at x%.2f speed (Regeneration, healing and absorption unchanged). Hostile mobs hunting you: %s. %s. PvP x%.2f, self x%.2f, void %s, admin %s, environmental %s.",
				c.eliteArmorEffectiveness() * 100, c.elite().damageMultiplier(), c.elite().hungerMultiplier(), c.eliteRangedMaxUncertainty(),
				c.eliteRegenMultiplier(), describeEliteAi(c.eliteAi()), describeEliteHunt(c),
				c.pvpDamageMultiplier(), c.selfDamageMultiplier(), applied(c.voidApplyProfile()), applied(c.adminApplyProfile()), applied(c.environmentalApplyProfile()));
			case PEACEFUL -> "You take no damage except from the void and /kill, hostile mobs will not target you, you lose no hunger, harmful effects are blocked, and you cannot damage other players (nor they you).";
		};
	}

	private static String describeEliteAi(PdConfig.EliteAi ai) {
		StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "notice you from x%.2f and give chase up to x%.2f of their vanilla range (never through walls)",
			ai.acquisitionRangeMultiplier(), EliteSurvival.pursuitFactor(ai)));
		if (ai.improvedPursuit()) {
			sb.append(String.format(Locale.ROOT, ", remember you x%.1f longer out of sight, re-check melee chases faster", ai.targetMemoryMultiplier()));
		}
		if (ai.advancedCombat()) {
			sb.append(", skeleton-family archers shoot at the Hard rate");
		}
		return sb.toString();
	}

	private static String describeEliteHunt(PdConfig c) {
		PdConfig.EliteHunt hunt = c.eliteHunt();
		PdConfig.EliteEffects fx = c.eliteEffects();
		StringBuilder sb = new StringBuilder();
		sb.append(hunt.priorityEnabled()
			? "Hostile mobs prefer you over other players (re-checked every " + hunt.scanIntervalTicks() + " ticks"
				+ (hunt.reacquireHiddenSeconds() > 0 ? ", hidden targets re-acquired for " + hunt.reacquireHiddenSeconds() + " s when a route exists" : "") + ")"
			: "Hostile mobs do not prefer you over other players");
		if (fx.enabled()) {
			sb.append(String.format(Locale.ROOT, ". Hits can infect/slow you: zombie %.0f%%, spider %.0f%%, cave spider %.0f%%, skeleton arrow %.0f%%, drowned %.0f%% (max %d s)",
				fx.zombie().chance() * 100, fx.spider().chance() * 100, fx.caveSpider().chance() * 100, fx.skeleton().chance() * 100, fx.drowned().chance() * 100, fx.maxDurationSeconds()));
		} else {
			sb.append(". Mob hit effects are off");
		}
		return sb.toString();
	}

	private static String applied(boolean on) {
		return on ? "scaled" : "unchanged";
	}

	private static String serverOverview(PdRuntime rt) {
		return "Personal Difficulty (server overview)"
			+ "\nPlayers with a stored record: " + rt.settings().explicitCount()
			+ " (locked: " + rt.settings().lockedCount() + ", restricted to an allow-list: " + rt.settings().restrictedCount() + ")"
			+ (rt.settings().writeBlockedReason() == null ? "" : "\nWARNING: changes to players cannot be saved: " + rt.settings().writeBlockedReason())
			+ (rt.settings().quarantinedCount() == 0 ? "" : "\nWARNING: " + rt.settings().quarantinedCount() + " player(s) have an INVALID stored record (kept verbatim, treated as locked); see 'admin status <player>' and repair with 'admin reset <player>'")
			+ (rt.settings().loadState() == PlayerSettings.LoadState.OK || rt.settings().loadState() == PlayerSettings.LoadState.NOT_PRESENT || rt.settings().loadWarnings().isEmpty() ? ""
				: "\nWhen the players file was loaded (" + rt.settings().loadState() + "): " + String.join(" | ", rt.settings().loadWarnings().stream().limit(5).toList()))
			+ "\nConfig file: " + rt.configFile()
			+ "\nSelectable difficulties: " + names(rt.availability().enabledModes())
			+ "\nEASY: " + describeEffect(rt.config(), PersonalDifficultyMode.EASY)
			+ "\nHARD: " + describeEffect(rt.config(), PersonalDifficultyMode.HARD)
			+ "\nELITE: " + describeEffect(rt.config(), PersonalDifficultyMode.ELITE);
	}

	// ------------------------------------------------------------------ administrative (op level 2)

	/**
	 * Unfiltered: the server overview (with every Phase 1 diagnostic line), the state of the overrides file, then the table of
	 * every effective setting with its source. With a prefix: the overrides banner and only the matching rows.
	 */
	private static int adminConfig(CommandContext<CommandSourceStack> ctx) {
		PdRuntime rt = runtime();
		if (notRunning(ctx.getSource(), rt)) {
			return 0;
		}
		String prefix = null;
		try {
			prefix = StringArgumentType.getString(ctx, "prefix");
		} catch (IllegalArgumentException noPrefix) {
			// unfiltered
		}
		CommandSourceStack source = ctx.getSource();
		if (prefix == null) {
			source.sendSuccess(() -> Component.literal(serverOverview(rt)), false);
		}
		ConfigState state = rt.state();
		for (String line : ConfigReport.banner(state, rt.overrides())) {
			source.sendSuccess(() -> Component.literal(line), false);
		}
		for (String message : ConfigReport.table(state, rt.overrides(), prefix)) {
			source.sendSuccess(() -> Component.literal(message), false);
		}
		return 1;
	}

	private static int adminGet(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		PdRuntime rt = runtime();
		if (notRunning(source, rt)) {
			return 0;
		}
		String key = StringArgumentType.getString(ctx, "key");
		ConfigState state = rt.state();
		List<String> lines;
		Optional<SettingDef> def = SettingRegistry.find(key);
		if (def.isPresent()) {
			lines = ConfigReport.describe(def.get(), state, rt.overrides());
		} else {
			FamilyKey.Parsed fk = FamilyKey.parse(key);
			if (fk instanceof FamilyKey.Ok ok) {
				lines = ConfigReport.describe(ok.key(), state, rt.overrides(), rt.damageTypes().exists(ok.key().id()));
			} else if (fk instanceof FamilyKey.Invalid invalid) {
				source.sendFailure(Component.literal("Invalid damage-type key '" + key + "': " + invalid.message() + "."));
				return 0;
			} else {
				source.sendFailure(Component.literal(PdRuntime.unknownKey(key)));
				return 0;
			}
		}
		source.sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
		return 1;
	}

	private static int adminSet(CommandContext<CommandSourceStack> ctx) {
		PdRuntime rt = runtime();
		if (notRunning(ctx.getSource(), rt)) {
			return 0;
		}
		return report(ctx.getSource(), rt.setValue(StringArgumentType.getString(ctx, "key"), StringArgumentType.getString(ctx, "value")));
	}

	private static int adminUnset(CommandContext<CommandSourceStack> ctx) {
		PdRuntime rt = runtime();
		if (notRunning(ctx.getSource(), rt)) {
			return 0;
		}
		return report(ctx.getSource(), rt.unsetValue(StringArgumentType.getString(ctx, "key")));
	}

	/** Changes are broadcast to operators and logged; refusals go to the sender only. */
	private static int report(CommandSourceStack source, SetResult result) {
		switch (result.kind()) {
			case APPLIED -> source.sendSuccess(() -> Component.literal(result.message()), true);
			case UNCHANGED -> source.sendSuccess(() -> Component.literal(result.message()), false);
			case REFUSED -> source.sendFailure(Component.literal(result.message()));
		}
		return result.ok() ? 1 : 0;
	}

	private static int adminReload(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		PdRuntime rt = runtime();
		if (notRunning(source, rt)) {
			return 0;
		}
		PdRuntime.Reload result = rt.reload();
		List<String> problems = new ArrayList<>(result.warnings()); // each distinct problem once, across the three layers
		// a players file that is currently refused (corrupt, unsupported, a directory) is re-read; a healthy one is never replaced
		boolean stillBlocked = false;
		if (rt.settings().writeBlockedReason() != null) {
			if (rt.settings().reloadIfBlocked()) {
				problems.add("the players file is usable again (" + rt.settings().loadState() + ")");
				problems.addAll(rt.settings().loadWarnings());
			} else {
				stillBlocked = true;
				source.sendFailure(Component.literal("The players file still cannot be used, so changes to players are still refused: " + rt.settings().writeBlockedReason()));
			}
		}
		if (result.anyFailed()) {
			List<String> failed = new ArrayList<>();
			if (result.config().readFailed()) {
				failed.add("config");
			}
			if (result.overridesFailed()) {
				failed.add("overrides file");
			}
			if (result.availability().failed()) {
				failed.add("availability policy");
			}
			source.sendFailure(Component.literal("Reload incomplete: the " + String.join(" and ", failed) + " could not be applied, so the previous values stay in force. "
				+ (result.overridesBlocked() == null ? "" : "Setting changes are refused until the overrides file is fixed: " + result.overridesBlocked() + " ") + String.join("; ", problems)));
			return 0;
		}
		if (stillBlocked) {
			return 0; // config and availability were applied, but the players file is still unusable (reported above)
		}
		String warn = problems.isEmpty() ? "" : " Warnings: " + String.join("; ", problems);
		source.sendSuccess(() -> Component.literal("Config, setting overrides and difficulty availability reloaded; applies immediately to everyone, no reconnect needed." + warn), true);
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
			PlayerRecord rec = rt.settings().record(p.id());
			PersonalDifficultyMode mode = rec.mode();
			Optional<PlayerSettings.Quarantine> invalid = rt.settings().quarantineOf(p.id());
			if (invalid.isPresent()) {
				PlayerSettings.Quarantine q = invalid.get();
				ctx.getSource().sendSuccess(() -> Component.literal(
					p.name() + " (" + p.id() + "): INVALID STORED RECORD, kept verbatim in players.json and NOT trusted"
						+ "\n  reason: " + q.reason()
						+ "\n  stored: " + q.rawJson()
						+ "\n  in force: treated as LOCKED on " + upper(q.salvagedMode()) + " (the mode is used only if the stored mode field alone was valid); no allow-list"
						+ "\n  every change for this player is refused until you run '/personaldifficulty admin reset " + p.name() + "' (a verified backup is made first; unknown fields are kept)"
				), false);
				continue;
			}
			StringBuilder sb = new StringBuilder(p.name() + " (" + p.id() + "): " + upper(mode));
			if (!rt.availability().isEnabled(mode)) {
				sb.append(" (currently disabled for new selections; kept)");
			}
			sb.append("\n  locked: ").append(rec.locked() ? "YES (players cannot change it themselves)" : "no");
			if (rec.allowed() == null) {
				sb.append("\n  allow-list: none (follows the server policy)");
			} else {
				sb.append("\n  allow-list: ").append(names(rec.allowed()));
				if (rec.isException()) {
					sb.append(" (current mode ").append(upper(mode)).append(" is outside it: kept as an exception)");
				}
			}
			if (rec.by() != null || rec.at() != 0L) {
				sb.append("\n  last administrative change: ").append(rec.by() == null ? "console/command block" : rec.by().toString())
					.append(rec.at() == 0L ? "" : " at " + Instant.ofEpochMilli(rec.at()));
			}
			ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
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
			if (rt.settings().isQuarantined(p.id())) {
				// the explicit recovery path for an unreadable stored entry: verified backup first, then replace
				try {
					PlayerSettings.Repair repair = rt.settings().repairQuarantined(p.id());
					if (repair != null) {
						done++;
						String backup = repair.backup() == null ? "no file to back up" : "backup: " + repair.backup().getFileName();
						String kept = repair.keptUnknownFields().isEmpty() ? "" : " Unknown fields kept: " + repair.keptUnknownFields() + ".";
						ctx.getSource().sendSuccess(() -> Component.literal(p.name() + ": the invalid stored record was replaced by a default one (NORMAL, unlocked, no allow-list). "
							+ "The old entry, including its invalid values, is in the verified " + backup + "." + kept), true);
					}
				} catch (IOException e) {
					ctx.getSource().sendFailure(Component.literal("Could not repair " + p.name() + ", so nothing was changed: " + e.getMessage()));
				}
				continue;
			}
			if (!rt.settings().hasExplicit(p.id())) {
				ctx.getSource().sendSuccess(() -> Component.literal(p.name() + " (" + p.id() + ") has no stored selection, lock or allow-list; already NORMAL. Nothing changed."), false);
				continue;
			}
			// R7: Normal, lock cleared, allow-list cleared (unknown stored fields are kept)
			PlayerRecord before = rt.settings().record(p.id());
			PlayerRecord after = new PlayerRecord(PersonalDifficultyMode.NORMAL, false, null, null, 0L, before.extras());
			if (applyAdministrative(ctx.getSource(), rt, p, before, after, p.name() + " reset to NORMAL (was " + upper(before.mode())
				+ (before.locked() ? ", lock cleared" : "") + (before.allowed() != null ? ", allow-list cleared" : "") + ").", "reset")) {
				done++;
			}
		}
		return done;
	}

	// ------------------------------------------------------------------ administrative player commands (1.4.0)

	/**
	 * Stores {@code after} for the player and, if they are online and their mode changed, runs the same mode-change
	 * hook as a self-selection (so entering Peaceful clears harmful effects). Reports failures and changes nothing on error.
	 */
	private static boolean applyAdministrative(CommandSourceStack source, PdRuntime rt, NameAndId target, PlayerRecord before, PlayerRecord after, String success, String verb) {
		try {
			rt.settings().put(target.id(), after);
		} catch (IOException e) {
			source.sendFailure(Component.literal("Could not " + verb + " " + target.name() + ", so nothing was changed: " + e.getMessage()));
			return false;
		}
		if (before.mode() != after.mode()) {
			ServerPlayer online = source.getServer().getPlayerList().getPlayer(target.id());
			if (online != null) {
				PdHooks.onModeChanged(online, before.mode(), after.mode());
			}
		}
		source.sendSuccess(() -> Component.literal(success), true);
		return true;
	}

	/** An unreadable stored record is never modified by an ordinary change: say so and point to the explicit repair. */
	private static boolean refuseInvalidRecord(CommandSourceStack source, PdRuntime rt, NameAndId target) {
		Optional<PlayerSettings.Quarantine> q = rt.settings().quarantineOf(target.id());
		if (q.isEmpty()) {
			return false;
		}
		source.sendFailure(Component.literal("Refused: " + target.name() + " has an invalid stored record (" + q.get().reason() + "), which is kept untouched. Nothing changed. "
			+ "Repair it first with '/personaldifficulty admin reset " + target.name() + "' (a verified backup is made first)."));
		return true;
	}

	private static @Nullable UUID actingAdmin(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		return player == null ? null : player.getUUID();
	}

	private static int adminAssign(CommandContext<CommandSourceStack> ctx, boolean lock) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		PdRuntime rt = runtime();
		if (notRunning(source, rt)) {
			return 0;
		}
		String typed = StringArgumentType.getString(ctx, "difficulty");
		Optional<PersonalDifficultyMode> parsed = PersonalDifficultyMode.parse(typed);
		if (parsed.isEmpty()) {
			source.sendFailure(Component.literal("Unknown difficulty '" + typed + "'. Known: " + names(List.of(PersonalDifficultyMode.values())) + "."));
			return 0;
		}
		PersonalDifficultyMode mode = parsed.get();
		int done = 0;
		for (NameAndId p : GameProfileArgument.getGameProfiles(ctx, "player")) {
			if (refuseInvalidRecord(source, rt, p)) {
				continue;
			}
			PlayerRecord before = rt.settings().record(p.id());
			// R2: administrators may assign any mode regardless of global policy, allow-list or lock
			PlayerRecord after = before.withMode(mode, actingAdmin(source), System.currentTimeMillis());
			if (lock) {
				after = after.withLocked(true, actingAdmin(source), System.currentTimeMillis());
			}
			if (after.sameState(before)) {
				source.sendSuccess(() -> Component.literal(p.name() + " is already " + upper(mode) + (lock ? " and locked" : "") + "; nothing changed."), false);
				continue;
			}
			SelectionPolicy.Verdict own = SelectionPolicy.decide(SelectionPolicy.Actor.PLAYER, before, mode, rt.availability().enabledModes());
			String override = own.refused() ? " (an administrator override: the player could not have chosen this themselves)" : "";
			String lockNote = after.locked() ? " Locked: only an administrator can change it." : "";
			if (applyAdministrative(source, rt, p, before, after, p.name() + " is now " + upper(mode) + "." + lockNote + override, "assign")) {
				done++;
			}
		}
		return done;
	}

	private static int adminSetLock(CommandContext<CommandSourceStack> ctx, boolean lock) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		PdRuntime rt = runtime();
		if (notRunning(source, rt)) {
			return 0;
		}
		int done = 0;
		for (NameAndId p : GameProfileArgument.getGameProfiles(ctx, "player")) {
			if (refuseInvalidRecord(source, rt, p)) {
				continue;
			}
			PlayerRecord before = rt.settings().record(p.id());
			if (before.locked() == lock) {
				source.sendSuccess(() -> Component.literal(p.name() + " is already " + (lock ? "locked" : "unlocked") + "; nothing changed."), false);
				continue;
			}
			PlayerRecord after = before.withLocked(lock, actingAdmin(source), System.currentTimeMillis());
			String msg = lock
				? p.name() + " is now locked on " + upper(before.mode()) + "; only an administrator can change it."
				: p.name() + " is unlocked; they can choose again" + (before.allowed() == null ? "." : " (within their allow-list: " + names(before.allowed()) + ").");
			if (applyAdministrative(source, rt, p, before, after, msg, lock ? "lock" : "unlock")) {
				done++;
			}
		}
		return done;
	}

	private static int adminUnrestrict(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		PdRuntime rt = runtime();
		if (notRunning(source, rt)) {
			return 0;
		}
		int done = 0;
		for (NameAndId p : GameProfileArgument.getGameProfiles(ctx, "player")) {
			if (refuseInvalidRecord(source, rt, p)) {
				continue;
			}
			PlayerRecord before = rt.settings().record(p.id());
			if (before.allowed() == null) {
				source.sendSuccess(() -> Component.literal(p.name() + " has no allow-list; nothing changed."), false);
				continue;
			}
			PlayerRecord after = before.withAllowed(null, actingAdmin(source), System.currentTimeMillis());
			if (applyAdministrative(source, rt, p, before, after, p.name() + " is no longer restricted to an allow-list; the server policy applies again" + (before.locked() ? " (still locked)." : "."), "unrestrict")) {
				done++;
			}
		}
		return done;
	}

	private static int adminRestrict(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		PdRuntime rt = runtime();
		if (notRunning(source, rt)) {
			return 0;
		}
		String[] tokens = StringArgumentType.getString(ctx, "modes").trim().split("\\s+");
		SelectionPolicy.RestrictFlag flag = SelectionPolicy.RestrictFlag.NONE;
		if (tokens.length > 2) {
			source.sendFailure(Component.literal("Usage: admin restrict <player> <mode>[,<mode>...] [switch|keep] (no spaces inside the list)."));
			return 0;
		}
		if (tokens.length == 2) {
			if (tokens[1].equalsIgnoreCase("switch")) {
				flag = SelectionPolicy.RestrictFlag.SWITCH;
			} else if (tokens[1].equalsIgnoreCase("keep")) {
				flag = SelectionPolicy.RestrictFlag.KEEP;
			} else {
				source.sendFailure(Component.literal("Unknown option '" + tokens[1] + "'; expected 'switch' or 'keep' (and no spaces inside the mode list)."));
				return 0;
			}
		}
		EnumSet<PersonalDifficultyMode> list = EnumSet.noneOf(PersonalDifficultyMode.class);
		for (String part : tokens[0].split(",", -1)) {
			if (part.isBlank()) {
				continue;
			}
			Optional<PersonalDifficultyMode> m = PersonalDifficultyMode.parse(part);
			if (m.isEmpty()) {
				source.sendFailure(Component.literal("Unknown difficulty '" + part + "'. Known: " + names(List.of(PersonalDifficultyMode.values())) + "."));
				return 0;
			}
			list.add(m.get());
		}
		if (list.isEmpty()) {
			source.sendFailure(Component.literal("An allow-list cannot be empty. Use 'admin unrestrict <player>' to remove a restriction."));
			return 0;
		}
		int done = 0;
		for (NameAndId p : GameProfileArgument.getGameProfiles(ctx, "player")) {
			if (refuseInvalidRecord(source, rt, p)) {
				continue;
			}
			PlayerRecord before = rt.settings().record(p.id());
			SelectionPolicy.RestrictPlan plan = SelectionPolicy.planRestrict(before, list, flag, actingAdmin(source), System.currentTimeMillis());
			switch (plan.status()) {
				case REFUSED_INVALID_RECORD -> refuseInvalidRecord(source, rt, p);
				case REFUSED_EMPTY -> source.sendFailure(Component.literal("An allow-list cannot be empty. Nothing changed."));
				case NEEDS_CHOICE -> source.sendFailure(Component.literal(
					"Refused: " + p.name() + " is on " + upper(before.mode()) + ", which is not in the new list (" + names(list) + "). Nothing changed. Re-run with 'switch' (moves them to "
						+ upper(SelectionPolicy.switchTarget(list)) + ") or 'keep' (they stay on " + upper(before.mode()) + " as an exception outside the list)."
				));
				case APPLY -> {
					PlayerRecord after = plan.result();
					if (after.sameState(before)) {
						source.sendSuccess(() -> Component.literal(p.name() + " is already restricted to " + names(list) + "; nothing changed."), false);
					} else {
						String how = plan.modeSwitched() ? " They were moved to " + upper(after.mode()) + "."
							: after.isException() ? " They stay on " + upper(after.mode()) + " as an exception outside the list; once they switch away they cannot switch back." : "";
						String lockNote = after.locked() ? " They are still locked." : "";
						if (applyAdministrative(source, rt, p, before, after, p.name() + " is now restricted to " + names(list) + "." + how + lockNote, "restrict")) {
							done++;
						}
					}
				}
			}
		}
		return done;
	}
}
