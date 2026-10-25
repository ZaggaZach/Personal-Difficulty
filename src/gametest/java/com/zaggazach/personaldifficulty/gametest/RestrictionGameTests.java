package com.zaggazach.personaldifficulty.gametest;

import static com.zaggazach.personaldifficulty.gametest.PdTestSupport.*;

import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.gametest.PdTestSupport.Capture;
import com.zaggazach.personaldifficulty.gametest.PdTestSupport.Outcome;
import com.zaggazach.personaldifficulty.store.AvailabilityPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;

/**
 * Operator-controlled availability restrictions, driven through the real Brigadier dispatcher with
 * real permission sets. Every test restores the policy in {@code finally} (the policy is global state
 * and every body is synchronous, so tests cannot interleave).
 */
public class RestrictionGameTests {
	private static final PersonalDifficultyMode[] ALL = PersonalDifficultyMode.values();

	private static AvailabilityPolicy policy() {
		return rt().availability();
	}

	private static Path policyFile() {
		return rt().configFile().resolveSibling(AvailabilityPolicy.FILE_NAME);
	}

	private static void restoreAll() {
		try {
			policy().enableAll();
			Files.deleteIfExists(policyFile());
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
	}

	private static List<String> suggestions(MinecraftServer server, String input, CommandSourceStack source) throws Exception {
		var d = server.getCommands().getDispatcher();
		return d.getCompletionSuggestions(d.parse(input, source)).get().getList().stream().map(s -> s.getText()).sorted().toList();
	}

	@GameTest
	public void restrictions_defaultIsAllFiveEnabled_andEveryModeIsSelectableByAnOrdinaryPlayer(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer p = player(h, "pd_rs_all", PersonalDifficultyMode.NORMAL);
		Capture cap = new Capture();
		CommandSourceStack asPlayer = as(p, PermissionSet.NO_PERMISSIONS, cap);
		check(policy().enabledModes().size() == 5, "default: all five enabled");
		for (PersonalDifficultyMode m : new PersonalDifficultyMode[] {PersonalDifficultyMode.EASY, PersonalDifficultyMode.HARD, PersonalDifficultyMode.ELITE, PersonalDifficultyMode.PEACEFUL, PersonalDifficultyMode.NORMAL}) {
			Outcome o = run(server, "personaldifficulty " + m.commandName(), asPlayer, cap);
			check(o.error() == null && o.result() == 1, m + " selectable: " + o);
			check(rt().modeOf(p.getUUID()) == m, "stored " + m);
			check(o.output().contains(m.name()), "feedback names the mode: " + o.output());
		}
		Outcome again = run(server, "personaldifficulty normal", asPlayer, cap);
		check(again.output().contains("nothing changed"), "re-selecting the current mode says so instead of claiming a change: " + again);
		h.succeed();
	}

	@GameTest
	public void restrictions_disabledModeCannotBeSelected_savedSelectionUntouched_reEnableIsLive(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer p = player(h, "pd_rs_dis", PersonalDifficultyMode.EASY);
		Capture cap = new Capture();
		Capture console = new Capture();
		CommandSourceStack asPlayer = as(p, PermissionSet.NO_PERMISSIONS, cap);
		CommandSourceStack consoleSrc = console(server, console);
		try {
			for (PersonalDifficultyMode m : ALL) {
				if (m == PersonalDifficultyMode.EASY) {
					continue;
				}
				Outcome off = run(server, "personaldifficulty admin disable " + m.commandName(), consoleSrc, console);
				check(off.error() == null && off.output().contains("DISABLED"), "console disables " + m + ": " + off);
				check(!policy().isEnabled(m), m + " now disabled");

				Outcome blocked = run(server, "personaldifficulty " + m.commandName(), asPlayer, cap);
				check(blocked.result() == 0, m + " must not be selectable: " + blocked);
				check(blocked.output().contains("disabled by the server") && blocked.output().contains("unchanged"), "clear error: " + blocked.output());
				check(rt().modeOf(p.getUUID()) == PersonalDifficultyMode.EASY, "saved selection untouched after a refused attempt (" + m + ")");

				Outcome on = run(server, "personaldifficulty admin enable " + m.commandName(), consoleSrc, console);
				check(on.error() == null && on.output().contains("ENABLED") && on.output().contains("no restart"), "console re-enables " + m + ": " + on);
				Outcome ok = run(server, "personaldifficulty " + m.commandName(), asPlayer, cap);
				check(ok.result() == 1 && rt().modeOf(p.getUUID()) == m, m + " selectable again immediately, no restart: " + ok);
				run(server, "personaldifficulty easy", asPlayer, cap);
			}
		} finally {
			restoreAll();
		}
		h.succeed();
	}

	@GameTest
	public void restrictions_existingSelectionIsKeptAndStillApplied_butCannotBeReselected(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		var level = h.getLevel();
		ServerPlayer p = player(h, "pd_rs_keep", PersonalDifficultyMode.ELITE);
		Capture cap = new Capture();
		Capture console = new Capture();
		CommandSourceStack asPlayer = as(p, PermissionSet.NO_PERMISSIONS, cap);
		CommandSourceStack consoleSrc = console(server, console);
		try {
			Outcome off = run(server, "personaldifficulty admin disable elite", consoleSrc, console);
			check(off.output().contains("saved selection(s) using it are kept"), "operator is told the policy: " + off);
			check(rt().modeOf(p.getUUID()) == PersonalDifficultyMode.ELITE, "stored selection NOT erased by disabling");
			near(8.0F, lost(p, level.damageSources().fall(), 4.0F), "the player keeps playing on Elite (x2) until they switch away");

			Outcome status = run(server, "personaldifficulty status", asPlayer, cap);
			check(status.output().contains("ELITE") && status.output().contains("currently disabled") && status.output().contains("existing selection is kept"), "status explains: " + status.output());
			check(!status.output().contains("You can select: easy, hard, elite"), "status does not list disabled modes as selectable: " + status.output());

			Outcome list = run(server, "personaldifficulty admin difficulties", consoleSrc, console);
			check(list.output().contains("elite: DISABLED") && list.output().matches("(?s).*elite: DISABLED \\(\\d+ player\\(s\\) still use it; kept\\).*"), "difficulties view: " + list.output());

			Outcome same = run(server, "personaldifficulty elite", asPlayer, cap);
			check(same.result() == 0 && same.output().contains("disabled"), "even their current disabled mode cannot be 'newly selected': " + same);
			check(rt().modeOf(p.getUUID()) == PersonalDifficultyMode.ELITE, "still Elite");

			run(server, "personaldifficulty normal", asPlayer, cap);
			check(rt().modeOf(p.getUUID()) == PersonalDifficultyMode.NORMAL, "can leave");
			Outcome back = run(server, "personaldifficulty elite", asPlayer, cap);
			check(back.result() == 0 && rt().modeOf(p.getUUID()) == PersonalDifficultyMode.NORMAL, "cannot come back while disabled: " + back);

			run(server, "personaldifficulty admin enable elite", consoleSrc, console);
			check(run(server, "personaldifficulty elite", asPlayer, cap).result() == 1, "can come back once re-enabled");
		} finally {
			restoreAll();
		}
		h.succeed();
	}

	@GameTest
	public void restrictions_tabCompletionNeverOffersDisabledModesToPlayersOrOps(GameTestHelper h) throws Exception {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer p = player(h, "pd_rs_tab", PersonalDifficultyMode.NORMAL);
		Capture cap = new Capture();
		Capture console = new Capture();
		CommandSourceStack plain = as(p, PermissionSet.NO_PERMISSIONS, cap);
		CommandSourceStack op = as(p, LevelBasedPermissionSet.GAMEMASTER, cap);
		try {
			run(server, "personaldifficulty admin disable elite", console(server, console), console);
			run(server, "personaldifficulty admin disable peaceful", console(server, console), console);
			for (CommandSourceStack src : List.of(plain, op)) {
				List<String> offered = suggestions(server, "personaldifficulty ", src);
				for (String m : List.of("easy", "hard", "normal")) {
					check(offered.contains(m), m + " offered: " + offered);
				}
				check(!offered.contains("elite") && !offered.contains("peaceful"), "disabled modes are not offered: " + offered);
				check(suggestions(server, "personaldifficulty e", src).stream().noneMatch(s -> s.equals("elite")), "prefix completion also excludes it");
			}
			// operators can still manage disabled modes: admin enable offers exactly the disabled ones (+ all), disable offers the enabled ones
			List<String> enableOffers = suggestions(server, "personaldifficulty admin enable ", op);
			check(enableOffers.equals(List.of("all", "elite", "peaceful")), "enable offers disabled modes: " + enableOffers);
			List<String> disableOffers = suggestions(server, "personaldifficulty admin disable ", op);
			check(disableOffers.equals(List.of("easy", "hard", "normal")), "disable offers enabled modes: " + disableOffers);
			check(run(server, "personaldifficulty admin enable elite", op, cap).error() == null, "admin still recognises a disabled mode");
			check(suggestions(server, "personaldifficulty ", plain).contains("elite"), "re-enabled mode is suggested again at once");
		} finally {
			restoreAll();
		}
		h.succeed();
	}

	@GameTest
	public void restrictions_permissionMatrix_consoleCommandBlockOpExecuteAndOrdinaryPlayers(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer actor = player(h, "pd_rs_actor", PersonalDifficultyMode.NORMAL);
		ServerPlayer target = player(h, "pd_rs_target", PersonalDifficultyMode.EASY);
		Capture cap = new Capture();
		List<String> adminCommands = List.of(
			"personaldifficulty admin difficulties",
			"personaldifficulty admin config",
			"personaldifficulty admin reload",
			"personaldifficulty admin enable all",
			"personaldifficulty admin disable elite",
			"personaldifficulty admin status pd_rs_target",
			"personaldifficulty admin reset pd_rs_target"
		);
		try {
			// denied: ordinary player (level 0), no-permission, moderator (level 1)
			for (PermissionSet denied : List.of(PermissionSet.NO_PERMISSIONS, LevelBasedPermissionSet.ALL, LevelBasedPermissionSet.MODERATOR)) {
				for (String cmd : adminCommands) {
					Outcome o = run(server, cmd, as(actor, denied, cap), cap);
					check(o.error() != null || (o.result() == 0 && !o.output().contains("ENABLED") && !o.output().contains("reset to")), "denied for " + denied + ": " + cmd + " -> " + o);
					check(policy().isEnabled(PersonalDifficultyMode.ELITE), "policy untouched by a denied attempt (" + cmd + ")");
					check(rt().modeOf(target.getUUID()) == PersonalDifficultyMode.EASY, "other player's mode untouched by a denied attempt (" + cmd + ")");
				}
				// a vanilla /execute chain cannot launder permissions: it needs level 2 itself
				Outcome exec = run(server, "execute as pd_rs_target run personaldifficulty admin disable elite", as(actor, denied, cap), cap);
				check(exec.error() != null, "execute is not available without level 2: " + exec);
				check(policy().isEnabled(PersonalDifficultyMode.ELITE), "still enabled");
				// the argument node swallows 'admin' as an (unknown) difficulty for a denied source: no leak, no change
				Outcome bare = run(server, "personaldifficulty admin", as(actor, denied, cap), cap);
				check(bare.result() == 0 && rt().modeOf(actor.getUUID()) == PersonalDifficultyMode.NORMAL, "bare 'admin' is just an unknown difficulty for them: " + bare);
			}

			// allowed: console, command block (no entity, level 2), op player (level 2), op player via /execute
			Capture console = new Capture();
			Capture block = new Capture();
			java.util.Map<CommandSourceStack, Capture> allowed = new java.util.LinkedHashMap<>();
			allowed.put(console(server, console), console);
			allowed.put(commandBlock(h.getLevel(), block), block);
			allowed.put(as(actor, LevelBasedPermissionSet.GAMEMASTER, cap), cap);
			allowed.put(as(actor, LevelBasedPermissionSet.ADMIN, cap), cap);
			for (var entry : allowed.entrySet()) {
				CommandSourceStack src = entry.getKey();
				Capture c = entry.getValue();
				Outcome off = run(server, "personaldifficulty admin disable elite", src, c);
				check(off.error() == null && !policy().isEnabled(PersonalDifficultyMode.ELITE), "allowed source disables elite: " + off);
				Outcome on = run(server, "personaldifficulty admin enable elite", src, c);
				check(on.error() == null && policy().isEnabled(PersonalDifficultyMode.ELITE), "allowed source enables elite: " + on);
			}
			Outcome exec = run(server, "execute as pd_rs_target run personaldifficulty admin disable elite", as(actor, LevelBasedPermissionSet.GAMEMASTER, cap), cap);
			check(exec.error() == null && !policy().isEnabled(PersonalDifficultyMode.ELITE), "an op's /execute chain keeps the op's own permission: " + exec);
			restoreAll();
			// ...and the executed-as player's lack of permission is irrelevant: permission comes from the original source

			// self-service from a command block / console is refused (not a player) rather than silently picking for someone
			Outcome cb = run(server, "personaldifficulty hard", commandBlock(h.getLevel(), block), block);
			check(cb.result() == 0 && block.all().contains("Only a player"), "a command block has no player to change: " + block.all());
			// but an op can deliberately act as a player with /execute (vanilla semantics) - that changes only that player
			Outcome act = run(server, "execute as pd_rs_target run personaldifficulty hard", as(actor, LevelBasedPermissionSet.GAMEMASTER, cap), cap);
			check(act.error() == null && rt().modeOf(target.getUUID()) == PersonalDifficultyMode.HARD && rt().modeOf(actor.getUUID()) == PersonalDifficultyMode.NORMAL,
				"/execute as <player> run personaldifficulty <mode> changes only that player: " + act);
		} finally {
			restoreAll();
		}
		h.succeed();
	}

	@GameTest
	public void restrictions_cannotDisableTheLastDifficulty_andOperatorsCanRecover(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer p = player(h, "pd_rs_last", PersonalDifficultyMode.NORMAL);
		Capture console = new Capture();
		CommandSourceStack src = console(server, console);
		Capture cap = new Capture();
		try {
			for (String m : List.of("normal", "easy", "hard", "elite")) {
				check(run(server, "personaldifficulty admin disable " + m, src, console).error() == null, "disable " + m);
			}
			Outcome refused = run(server, "personaldifficulty admin disable peaceful", src, console);
			check(refused.result() == 0 && refused.output().contains("Refused") && refused.output().contains("last enabled"), "last one refused: " + refused);
			check(policy().isEnabled(PersonalDifficultyMode.PEACEFUL) && policy().enabledModes().size() == 1, "exactly one remains");
			Outcome usable = run(server, "personaldifficulty peaceful", as(p, PermissionSet.NO_PERMISSIONS, cap), cap);
			check(usable.result() == 1, "the command system is still usable: " + usable);
			Outcome recover = run(server, "personaldifficulty admin enable all", src, console);
			check(recover.error() == null && policy().enabledModes().size() == 5, "enable all recovers: " + recover);
			check(run(server, "personaldifficulty admin enable all", src, console).output().contains("already enabled"), "idempotent and honest");
		} finally {
			restoreAll();
		}
		h.succeed();
	}

	@GameTest
	public void restrictions_persistAcrossRestart_andReloadIsSafe(GameTestHelper h) throws Exception {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer op = player(h, "pd_rs_op", PersonalDifficultyMode.NORMAL);
		Capture console = new Capture();
		CommandSourceStack src = console(server, console);
		Capture cap = new Capture();
		CommandSourceStack asPlayer = as(op, PermissionSet.NO_PERMISSIONS, cap);
		try {
			run(server, "personaldifficulty admin disable hard", src, console);
			run(server, "personaldifficulty admin disable elite", src, console);
			String json = Files.readString(policyFile());
			check(json.equals("{\"version\":1,\"disabled\":[\"hard\",\"elite\"]}"), "policy written to the config dir: " + json);
			AvailabilityPolicy restarted = new AvailabilityPolicy(policyFile());
			restarted.load();
			check(!restarted.isEnabled(PersonalDifficultyMode.HARD) && !restarted.isEnabled(PersonalDifficultyMode.ELITE) && restarted.isEnabled(PersonalDifficultyMode.EASY),
				"a fresh instance (= server restart) reads the same policy");

			// operator edits the file by hand, then reloads: applies live
			Files.writeString(policyFile(), "{\"version\":1,\"disabled\":[\"peaceful\"]}");
			Outcome reload = run(server, "personaldifficulty admin reload", src, console);
			check(reload.error() == null && reload.result() == 1, "reload ok: " + reload);
			check(policy().isEnabled(PersonalDifficultyMode.HARD) && !policy().isEnabled(PersonalDifficultyMode.PEACEFUL), "edited policy live without restart");
			check(run(server, "personaldifficulty peaceful", asPlayer, cap).result() == 0, "newly disabled mode is blocked at once");
			check(run(server, "personaldifficulty hard", asPlayer, cap).result() == 1, "newly enabled mode is allowed at once");

			// broken edits must not change the live policy or disable everything
			Files.writeString(policyFile(), "{ not json");
			Outcome bad = run(server, "personaldifficulty admin reload", src, console);
			check(bad.result() == 0 && bad.output().contains("Reload incomplete") && bad.output().contains("previous values stay in force"), "bad file reported: " + bad);
			check(!policy().isEnabled(PersonalDifficultyMode.PEACEFUL) && policy().isEnabled(PersonalDifficultyMode.EASY), "live policy unchanged by a broken file");
			Files.writeString(policyFile(), "{\"version\":1,\"disabled\":[\"normal\",\"easy\",\"hard\",\"elite\",\"peaceful\"]}");
			Outcome all = run(server, "personaldifficulty admin reload", src, console);
			check(all.result() == 0 && policy().enabledModes().size() == 4, "a file that disables everything is rejected: " + all);
			check(run(server, "personaldifficulty admin enable all", src, console).error() == null && policy().enabledModes().size() == 5, "recovery");
		} finally {
			restoreAll();
		}
		h.succeed();
	}

	@GameTest
	public void restrictions_unknownNamesGetClearErrors_andInheritIsGone(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer p = player(h, "pd_rs_names", PersonalDifficultyMode.HARD);
		Capture cap = new Capture();
		Capture console = new Capture();
		CommandSourceStack asPlayer = as(p, PermissionSet.NO_PERMISSIONS, cap);
		Outcome inherit = run(server, "personaldifficulty inherit", asPlayer, cap);
		check(inherit.result() == 0 && inherit.output().contains("removed in 1.1.0") && inherit.output().contains("Available:"), "inherit: " + inherit);
		Outcome bogus = run(server, "personaldifficulty nightmare", asPlayer, cap);
		check(bogus.result() == 0 && bogus.output().contains("Unknown difficulty 'nightmare'"), "unknown: " + bogus);
		check(rt().modeOf(p.getUUID()) == PersonalDifficultyMode.HARD, "failed attempts change nothing");
		Outcome adminBogus = run(server, "personaldifficulty admin enable nightmare", console(server, console), console);
		check(adminBogus.result() == 0 && adminBogus.output().contains("Known:"), "admin unknown: " + adminBogus);
		Outcome adminInherit = run(server, "personaldifficulty admin disable inherit", console(server, console), console);
		check(adminInherit.result() == 0 && policy().enabledModes().size() == 5, "no 'inherit' in the admin commands either: " + adminInherit);
		check(run(server, "personaldifficulty admin enable normal", console(server, console), console).output().contains("already enabled"), "already enabled is reported, not faked");
		h.succeed();
	}

	@GameTest
	public void restrictions_operatorsAreSubjectToTheSameSelectionRule(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer op = player(h, "pd_rs_opself", PersonalDifficultyMode.NORMAL);
		Capture cap = new Capture();
		Capture console = new Capture();
		try {
			run(server, "personaldifficulty admin disable peaceful", console(server, console), console);
			Outcome o = run(server, "personaldifficulty peaceful", as(op, LevelBasedPermissionSet.GAMEMASTER, cap), cap);
			check(o.result() == 0 && rt().modeOf(op.getUUID()) == PersonalDifficultyMode.NORMAL, "an op's own selection obeys the policy too (they can simply re-enable it): " + o);
		} finally {
			restoreAll();
		}
		h.succeed();
	}
}
