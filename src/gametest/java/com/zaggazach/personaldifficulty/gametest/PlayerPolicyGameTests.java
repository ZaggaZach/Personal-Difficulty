package com.zaggazach.personaldifficulty.gametest;

import static com.zaggazach.personaldifficulty.PersonalDifficultyMode.*;
import static com.zaggazach.personaldifficulty.gametest.PdTestSupport.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.gametest.PdTestSupport.Capture;
import com.zaggazach.personaldifficulty.gametest.PdTestSupport.Outcome;
import com.zaggazach.personaldifficulty.store.AvailabilityPolicy;
import com.zaggazach.personaldifficulty.store.PlayerRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Phase 1: per-player lock, allow-list and administrator assignment, driven through the real Brigadier dispatcher with
 * real permission sets and real (mock-connected) players. Rules R1 to R8 over the whole decision space are enumerated in
 * the unit test {@code SelectionPolicyTest}; these tests prove the same rules hold through the command layer, the stored
 * records, the online-player hook and the player lifecycle.
 */
public class PlayerPolicyGameTests {
	private static void restoreAvailability() {
		try {
			rt().availability().enableAll();
			Files.deleteIfExists(rt().configFile().resolveSibling(AvailabilityPolicy.FILE_NAME));
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static PlayerRecord rec(ServerPlayer p) {
		return rt().settings().record(p.getUUID());
	}

	private static Outcome self(MinecraftServer server, ServerPlayer p, PermissionSet perms, String mode) {
		Capture cap = new Capture();
		return run(server, "personaldifficulty " + mode, as(p, perms, cap), cap);
	}

	private static Outcome admin(MinecraftServer server, String command) {
		Capture cap = new Capture();
		return run(server, "personaldifficulty admin " + command, console(server, cap), cap);
	}

	private static Path playersDir(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("personaldifficulty");
	}

	// ------------------------------------------------------------------ R2, R3, R8

	@GameTest
	public void policy_assignWithLock_blocksEverySelfServicePath_operatorsIncluded_untilUnlocked(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer p = player(h, "pd_pp_lock", NORMAL);
		ServerPlayer op = player(h, "pd_pp_lockop", NORMAL);
		try {
			Outcome a = admin(server, "assign pd_pp_lock elite lock");
			check(a.error() == null && a.output().contains("is now ELITE") && a.output().contains("Locked"), "assign + lock: " + a);
			check(rec(p).mode() == ELITE && rec(p).locked(), "stored: " + rec(p));

			for (PermissionSet perms : List.of(PermissionSet.NO_PERMISSIONS, LevelBasedPermissionSet.MODERATOR, LevelBasedPermissionSet.GAMEMASTER, LevelBasedPermissionSet.ADMIN)) {
				for (PersonalDifficultyMode m : PersonalDifficultyMode.values()) {
					Outcome o = self(server, p, perms, m.commandName());
					check(o.result() == 0 && o.output().contains("locked"), "locked player (as " + perms + ") cannot choose " + m + ": " + o);
					check(rec(p).mode() == ELITE, "unchanged");
				}
			}
			// R3 'by any path': an operator acting AS the locked player through /execute is still the player's own self-service
			Capture cap = new Capture();
			run(server, "execute as pd_pp_lock run personaldifficulty normal", as(op, LevelBasedPermissionSet.GAMEMASTER, cap), cap);
			check(rec(p).mode() == ELITE, "/execute as the locked player cannot bypass the lock");

			// assign keeps an existing lock unless unlocked; it is not itself self-service
			Outcome back = admin(server, "assign pd_pp_lock normal");
			check(back.output().contains("is now NORMAL") && rec(p).mode() == NORMAL && rec(p).locked(), "assign overrides the lock for the mode, lock stays: " + back + " " + rec(p));
			check(self(server, p, PermissionSet.NO_PERMISSIONS, "hard").result() == 0, "still locked on NORMAL");

			Outcome unlock = admin(server, "unlock pd_pp_lock");
			check(unlock.output().contains("unlocked") && !rec(p).locked(), "unlock: " + unlock);
			check(self(server, p, PermissionSet.NO_PERMISSIONS, "hard").result() == 1 && rec(p).mode() == HARD, "free to choose again");
			check(admin(server, "unlock pd_pp_lock").output().contains("already unlocked"), "idempotent and honest");

			// R2: an administrator may assign themselves; R8: with the ordinary command they are an ordinary player
			Capture oc = new Capture();
			Outcome own = run(server, "personaldifficulty admin assign pd_pp_lockop peaceful lock", as(op, LevelBasedPermissionSet.GAMEMASTER, oc), oc);
			check(own.error() == null && rec(op).mode() == PEACEFUL && rec(op).locked(), "an operator can assign and lock themselves: " + own);
			check(rec(op).by() != null && rec(op).by().equals(op.getUUID()) && rec(op).at() > 0, "the acting administrator and time are recorded: " + rec(op));
			check(self(server, op, LevelBasedPermissionSet.ADMIN, "easy").result() == 0 && rec(op).mode() == PEACEFUL, "R8: an operator's own selection obeys the lock");
		} finally {
			restoreAvailability();
		}
		h.succeed();
	}

	@GameTest
	public void policy_assignOverridesGlobalPolicyAllowListAndLock_R2(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer p = player(h, "pd_pp_over", EASY);
		try {
			admin(server, "disable elite");
			admin(server, "restrict pd_pp_over easy,hard");
			admin(server, "lock pd_pp_over");
			check(rec(p).locked() && rec(p).allowed().equals(EnumSet.of(EASY, HARD)), "setup: " + rec(p));
			check(self(server, p, PermissionSet.NO_PERMISSIONS, "elite").result() == 0, "the player cannot");
			Outcome o = admin(server, "assign pd_pp_over elite");
			check(o.error() == null && rec(p).mode() == ELITE, "the administrator can, despite disabled+allow-list+lock: " + o);
			check(o.output().contains("override"), "and is told it is an override: " + o);
			check(rec(p).locked() && rec(p).allowed().equals(EnumSet.of(EASY, HARD)), "lock and allow-list untouched by assign");
			check(!rt().availability().isEnabled(ELITE), "global policy untouched");
		} finally {
			restoreAvailability();
		}
		h.succeed();
	}

	// ------------------------------------------------------------------ R1, R5, R6: allow-lists

	@GameTest
	public void policy_allowList_limitsSelfServiceForEveryone_includingOperators(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer p = player(h, "pd_pp_allow", NORMAL);
		try {
			Outcome r = admin(server, "restrict pd_pp_allow normal,easy");
			check(r.error() == null && rec(p).allowed().equals(EnumSet.of(NORMAL, EASY)), "restrict: " + r);
			check(rec(p).mode() == NORMAL, "current mode is inside the list: unchanged");
			for (PermissionSet perms : List.of(PermissionSet.NO_PERMISSIONS, LevelBasedPermissionSet.GAMEMASTER)) {
				for (PersonalDifficultyMode m : List.of(HARD, ELITE, PEACEFUL)) {
					Outcome o = self(server, p, perms, m.commandName());
					check(o.result() == 0 && o.output().contains("not in the list") && o.output().contains("Available to you: normal, easy"), "refused with a specific reason (" + perms + ", " + m + "): " + o);
				}
			}
			check(self(server, p, PermissionSet.NO_PERMISSIONS, "easy").result() == 1 && rec(p).mode() == EASY, "listed modes work");
			check(self(server, p, PermissionSet.NO_PERMISSIONS, "normal").result() == 1 && rec(p).mode() == NORMAL, "and back");

			// both a globally disabled mode and a not-listed mode are refused, each with its own reason
			admin(server, "disable easy");
			Outcome disabled = self(server, p, PermissionSet.NO_PERMISSIONS, "easy");
			check(disabled.output().contains("disabled by the server"), "global reason: " + disabled);
			Outcome notListed = self(server, p, PermissionSet.NO_PERMISSIONS, "hard");
			check(notListed.output().contains("not in the list"), "allow-list reason: " + notListed);
			restoreAvailability();

			Outcome status = self(server, p, PermissionSet.NO_PERMISSIONS, "status");
			check(status.output().contains("An administrator limits you to: normal, easy") && status.output().contains("You can select: normal, easy"), "status: " + status);

			// R6: unrestrict removes only the allow-list
			admin(server, "lock pd_pp_allow");
			Outcome un = admin(server, "unrestrict pd_pp_allow");
			check(un.output().contains("no longer restricted") && un.output().contains("still locked") && rec(p).allowed() == null && rec(p).locked(), "unrestrict leaves the lock: " + un + " " + rec(p));
			check(admin(server, "unrestrict pd_pp_allow").output().contains("no allow-list"), "idempotent");
		} finally {
			restoreAvailability();
		}
		h.succeed();
	}

	@GameTest
	public void policy_restrictAwayFromTheCurrentMode_cancelSwitchKeep_R5(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer cancel = player(h, "pd_pp_rcancel", HARD);
		ServerPlayer keep = player(h, "pd_pp_rkeep", HARD);
		ServerPlayer sw = player(h, "pd_pp_rswitch", ELITE);
		ServerPlayer sw2 = player(h, "pd_pp_rswitch2", HARD);
		try {
			// default: refuse, show the options, change nothing (R5 "cancel")
			PlayerRecord before = rec(cancel);
			Outcome o = admin(server, "restrict pd_pp_rcancel easy,elite");
			check(o.result() == 0 && o.output().contains("Refused") && o.output().contains("switch") && o.output().contains("keep") && o.output().contains("Nothing changed"), "default refuses with options: " + o);
			check(rec(cancel).equals(before), "nothing stored on a refusal");

			// keep: allow-list as typed, current mode retained outside it (a recorded exception)
			Outcome k = admin(server, "restrict pd_pp_rkeep easy,elite keep");
			check(k.error() == null && rec(keep).mode() == HARD && rec(keep).allowed().equals(EnumSet.of(EASY, ELITE)) && rec(keep).isException(), "keep: " + k + " " + rec(keep));
			check(k.output().contains("exception"), "feedback says so: " + k);
			check(self(server, keep, PermissionSet.NO_PERMISSIONS, "hard").output().contains("not in the list"), "the kept mode cannot be newly selected once left or re-chosen");
			check(self(server, keep, PermissionSet.NO_PERMISSIONS, "elite").result() == 1, "they can move to a listed mode");
			check(self(server, keep, PermissionSet.NO_PERMISSIONS, "hard").result() == 0 && rec(keep).mode() == ELITE, "and cannot go back to the exception");
			Outcome st = admin(server, "status pd_pp_rkeep");
			check(st.output().contains("allow-list: easy, elite"), "status shows the list: " + st);

			// switch: NORMAL if listed, else first listed in canonical order, independent of typing order
			Outcome s1 = admin(server, "restrict pd_pp_rswitch peaceful,hard,normal switch");
			check(s1.error() == null && rec(sw).mode() == NORMAL && s1.output().contains("moved to NORMAL"), "NORMAL when listed: " + s1);
			Outcome s2 = admin(server, "restrict pd_pp_rswitch2 peaceful,elite switch");
			check(rec(sw2).mode() == ELITE && s2.output().contains("moved to ELITE"), "first in canonical order (ELITE before PEACEFUL) regardless of typed order: " + s2);

			// flag is irrelevant when the current mode is listed
			check(admin(server, "restrict pd_pp_rswitch2 peaceful,elite switch").output().contains("already restricted") && rec(sw2).mode() == ELITE, "same list again: nothing to do");
			Outcome narrower = admin(server, "restrict pd_pp_rswitch2 elite switch");
			check(narrower.error() == null && rec(sw2).mode() == ELITE && rec(sw2).allowed().equals(EnumSet.of(ELITE)) && !narrower.output().contains("moved"), "the current mode is listed: the flag is irrelevant, no spurious switch: " + narrower);

			// malformed input never changes anything
			ServerPlayer m = player(h, "pd_pp_rbad", EASY);
			PlayerRecord same = rec(m);
			for (String bad : List.of("restrict pd_pp_rbad ,", "restrict pd_pp_rbad nightmare", "restrict pd_pp_rbad easy,hard maybe", "restrict pd_pp_rbad easy hard keep", "restrict pd_pp_rbad easy, hard")) {
				Outcome b = admin(server, bad);
				check(b.error() != null || b.result() == 0, bad + " -> " + b);
				check(rec(m).equals(same), "unchanged after: " + bad);
			}
			check(admin(server, "restrict pd_pp_rbad ,").output().contains("cannot be empty"), "empty list is reported");
		} finally {
			restoreAvailability();
		}
		h.succeed();
	}

	// ------------------------------------------------------------------ R4: lock survives the world around it

	@GameTest
	public void policy_lockAndAllowListSurviveGlobalPolicyChanges_R4(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer p = player(h, "pd_pp_policy", NORMAL);
		try {
			admin(server, "assign pd_pp_policy elite lock");
			admin(server, "restrict pd_pp_policy elite,hard");
			PlayerRecord before = rec(p);
			for (PersonalDifficultyMode m : PersonalDifficultyMode.values()) {
				if (m != NORMAL) {
					admin(server, "disable " + m.commandName());
				}
			}
			check(rec(p).equals(before), "disabling modes never changes a saved mode, lock or allow-list: " + rec(p));
			admin(server, "enable all");
			check(rec(p).equals(before), "re-enabling does not either");
			check(self(server, p, PermissionSet.NO_PERMISSIONS, "normal").result() == 0 && rec(p).mode() == ELITE, "still locked");
		} finally {
			restoreAvailability();
		}
		h.succeed();
	}

	@GameTest
	public void policy_lockAllowListAndModeSurviveDeathRespawnAndReconnect_R4(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerLevel level = h.getLevel();
		ServerPlayer p = player(h, "pd_pp_life", NORMAL);
		UUID id = p.getUUID();
		try {
			admin(server, "assign pd_pp_life elite lock");
			admin(server, "restrict pd_pp_life elite,hard");
			PlayerRecord before = rec(p);
			check(before.mode() == ELITE && before.locked() && before.allowed().equals(EnumSet.of(ELITE, HARD)), "setup: " + before);
			near(8.0F, lost(p, level.damageSources().fall(), 4.0F), "Elite is live (x2)");

			p.kill(level);
			ServerPlayer respawned = server.getPlayerList().respawn(p, false, Entity.RemovalReason.KILLED);
			check(respawned.getUUID().equals(id) && rt().settings().record(id).equals(before), "record survives death + respawn");
			respawned.setGameMode(GameType.SURVIVAL);
			respawned.getAbilities().invulnerable = false;
			check(self(server, respawned, PermissionSet.NO_PERMISSIONS, "hard").result() == 0, "still locked after respawn");
			check(rt().modeOf(respawned.getUUID()) == ELITE, "still Elite after respawn");

			server.getPlayerList().remove(respawned);
			ServerPlayer rejoined = placeNewPlayer(h, id, "pd_pp_life");
			check(rt().settings().record(id).equals(before), "record survives disconnect + reconnect");
			check(self(server, rejoined, PermissionSet.NO_PERMISSIONS, "hard").result() == 0, "still locked after reconnect");
			near(8.0F, lost(rejoined, level.damageSources().fall(), 4.0F), "still Elite after reconnect");

			// an offline player can be managed and the result is in force when they return
			server.getPlayerList().remove(rejoined);
			Outcome off = admin(server, "assign pd_pp_life peaceful");
			check(off.error() == null && off.output().contains("is now PEACEFUL"), "assign to an offline player: " + off);
			check(rt().settings().record(id).mode() == PEACEFUL && rt().settings().record(id).locked(), "stored: " + rt().settings().record(id));
			ServerPlayer back = placeNewPlayer(h, id, "pd_pp_life");
			near(0.0F, lost(back, level.damageSources().fall(), 4.0F), "Peaceful applies on return");
			server.getPlayerList().remove(back);
		} finally {
			restoreAvailability();
		}
		h.succeed();
	}

	// ------------------------------------------------------------------ the mode-change hook

	@GameTest
	public void policy_administrativeModeChangesRunTheModeChangeHookForOnlinePlayers(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer viaAssign = player(h, "pd_pp_hook1", NORMAL);
		ServerPlayer viaRestrict = player(h, "pd_pp_hook2", HARD);
		ServerPlayer viaSelf = player(h, "pd_pp_hook3", NORMAL);
		ServerPlayer viaKeep = player(h, "pd_pp_hook4", NORMAL);
		try {
			for (ServerPlayer p : List.of(viaAssign, viaRestrict, viaSelf, viaKeep)) {
				check(p.addEffect(new MobEffectInstance(MobEffects.POISON, 600, 0)) && p.addEffect(new MobEffectInstance(MobEffects.SPEED, 600, 0)), "setup effects");
				p.setRemainingFireTicks(200);
				p.setTicksFrozen(50);
				check(p.hasEffect(MobEffects.POISON) && p.isOnFire(), "setup: harmful effect and fire present");
			}
			check(admin(server, "assign pd_pp_hook1 peaceful").error() == null, "assign");
			check(admin(server, "restrict pd_pp_hook2 peaceful switch").error() == null, "restrict switch");
			check(self(server, viaSelf, PermissionSet.NO_PERMISSIONS, "peaceful").result() == 1, "self (existing behaviour)");
			for (ServerPlayer p : List.of(viaAssign, viaRestrict, viaSelf)) {
				check(rec(p).mode() == PEACEFUL, p.getName().getString() + " is Peaceful");
				check(!p.hasEffect(MobEffects.POISON), p.getName().getString() + ": the harmful effect was cleared by the hook");
				check(p.hasEffect(MobEffects.SPEED), p.getName().getString() + ": beneficial effects stay");
				check(!p.isOnFire() && p.getTicksFrozen() == 0, p.getName().getString() + ": fire and freezing cleared");
			}
			// a restrict that does NOT change the mode must not run the hook (keep: HARD stays outside the list)
			ServerPlayer stays = viaKeep;
			check(admin(server, "restrict pd_pp_hook4 easy keep").error() == null && rec(stays).mode() == NORMAL, "keep does not switch");
			check(stays.hasEffect(MobEffects.POISON) && stays.isOnFire(), "no mode change, so no cleanup");
			// the hook runs only when entering Peaceful; leaving it does nothing special
			check(admin(server, "assign pd_pp_hook1 hard").error() == null && rec(viaAssign).mode() == HARD, "leaving Peaceful by assignment");
		} finally {
			restoreAvailability();
		}
		h.succeed();
	}

	// ------------------------------------------------------------------ R7

	@GameTest
	public void policy_reset_clearsModeLockAndAllowList_R7_whileUnlockAndUnrestrictAreNarrower(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer p = player(h, "pd_pp_reset", NORMAL);
		ServerPlayer untouched = player(h, "pd_pp_resetnone", NORMAL);
		try {
			admin(server, "assign pd_pp_reset peaceful lock");
			admin(server, "restrict pd_pp_reset peaceful,hard");
			check(rec(p).mode() == PEACEFUL && rec(p).locked() && rec(p).allowed() != null, "setup: " + rec(p));

			admin(server, "unlock pd_pp_reset");
			check(rec(p).mode() == PEACEFUL && !rec(p).locked() && rec(p).allowed() != null, "unlock is narrow: " + rec(p));
			admin(server, "lock pd_pp_reset");
			admin(server, "unrestrict pd_pp_reset");
			check(rec(p).mode() == PEACEFUL && rec(p).locked() && rec(p).allowed() == null, "unrestrict is narrow: " + rec(p));
			admin(server, "restrict pd_pp_reset peaceful,hard");

			Outcome r = admin(server, "reset pd_pp_reset");
			check(r.error() == null && r.output().contains("reset to NORMAL (was PEACEFUL") && r.output().contains("lock cleared") && r.output().contains("allow-list cleared"), "reset: " + r);
			check(rec(p).equals(PlayerRecord.DEFAULT) && !rt().settings().hasExplicit(p.getUUID()), "everything cleared and nothing stored: " + rec(p));
			check(self(server, p, PermissionSet.NO_PERMISSIONS, "elite").result() == 1, "the player is an ordinary player again");

			Outcome none = admin(server, "reset pd_pp_resetnone");
			check(none.output().contains("Nothing changed"), "a player with no record: " + none);
		} finally {
			restoreAvailability();
		}
		h.succeed();
	}

	@GameTest
	public void policy_adminStatusShowsLockAllowListExceptionAndWhoChangedIt(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer p = player(h, "pd_pp_status", HARD);
		ServerPlayer op = player(h, "pd_pp_statusop", NORMAL);
		try {
			Capture cap = new Capture();
			CommandSourceStack src = as(op, LevelBasedPermissionSet.GAMEMASTER, cap);
			run(server, "personaldifficulty admin restrict pd_pp_status easy keep", src, cap);
			run(server, "personaldifficulty admin lock pd_pp_status", src, cap);
			Outcome o = run(server, "personaldifficulty admin status pd_pp_status", src, cap);
			check(o.output().contains("HARD") && o.output().contains("locked: YES") && o.output().contains("allow-list: easy") && o.output().contains("exception")
				&& o.output().contains(op.getUUID().toString()), "status: " + o);
			Outcome plain = admin(server, "status pd_pp_statusop");
			check(plain.output().contains("locked: no") && plain.output().contains("none (follows the server policy)"), "default player: " + plain);
			Outcome overview = admin(server, "config");
			check(overview.output().contains("Players with a stored record") && overview.output().contains("locked:"), "overview counts: " + overview);
		} finally {
			restoreAvailability();
		}
		h.succeed();
	}

	// ------------------------------------------------------------------ suggestions

	@GameTest
	public void policy_tabCompletionOffersExactlyWhatThePlayerMayChoose(GameTestHelper h) throws Exception {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer p = player(h, "pd_pp_tab", NORMAL);
		var d = server.getCommands().getDispatcher();
		try {
			Capture cap = new Capture();
			CommandSourceStack src = as(p, PermissionSet.NO_PERMISSIONS, cap);
			CommandSourceStack op = as(p, LevelBasedPermissionSet.GAMEMASTER, cap);
			admin(server, "restrict pd_pp_tab normal,hard");
			admin(server, "disable hard");
			List<String> modeNames = java.util.Arrays.stream(PersonalDifficultyMode.values()).map(PersonalDifficultyMode::commandName).toList();
			// (server-side completion lists literals such as 'status' regardless of requirements; only the mode words matter here)
			List<String> offered = d.getCompletionSuggestions(d.parse("personaldifficulty ", src)).get().getList().stream().map(s -> s.getText()).filter(modeNames::contains).sorted().toList();
			check(offered.equals(List.of("normal")), "allow-list and global policy both applied: " + offered);
			admin(server, "lock pd_pp_tab");
			List<String> locked = d.getCompletionSuggestions(d.parse("personaldifficulty ", src)).get().getList().stream().map(s -> s.getText()).filter(modeNames::contains).sorted().toList();
			check(locked.isEmpty(), "a locked player is offered no mode: " + locked);

			// admin argument completion
			List<String> assign = d.getCompletionSuggestions(d.parse("personaldifficulty admin assign pd_pp_tab ", op)).get().getList().stream().map(s -> s.getText()).sorted().toList();
			check(assign.equals(List.of("easy", "elite", "hard", "normal", "peaceful")), "assign offers every mode, even disabled ones: " + assign);
			List<String> afterList = d.getCompletionSuggestions(d.parse("personaldifficulty admin restrict pd_pp_tab easy,", op)).get().getList().stream().map(s -> s.getText()).sorted().toList();
			check(afterList.equals(List.of("easy", "elite", "hard", "normal", "peaceful")), "restrict completes the mode after the comma: " + afterList);
			List<String> flags = d.getCompletionSuggestions(d.parse("personaldifficulty admin restrict pd_pp_tab easy ", op)).get().getList().stream().map(s -> s.getText()).sorted().toList();
			check(flags.equals(List.of("keep", "switch")), "flags: " + flags);
			// the admin subtree is part of the tree sent to the client only for operators
			var admin = d.getRoot().getChild("personaldifficulty").getChild("admin");
			check(admin.canUse(op) && !admin.canUse(src), "admin subtree usable (and sent to clients) only for operators");
		} finally {
			restoreAvailability();
		}
		h.succeed();
	}

	// ------------------------------------------------------------------ permissions

	private static final List<String> NEW_ADMIN_COMMANDS = List.of(
		"personaldifficulty admin assign pd_pp_ptarget elite",
		"personaldifficulty admin assign pd_pp_ptarget elite lock",
		"personaldifficulty admin lock pd_pp_ptarget",
		"personaldifficulty admin unlock pd_pp_ptarget",
		"personaldifficulty admin restrict pd_pp_ptarget easy,elite keep",
		"personaldifficulty admin restrict pd_pp_ptarget easy",
		"personaldifficulty admin unrestrict pd_pp_ptarget",
		"personaldifficulty admin reset pd_pp_ptarget"
	);

	@GameTest
	public void policy_newAdminCommands_permissionMatrix_nonOperatorLevel1Level2ConsoleCommandBlock(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer actor = player(h, "pd_pp_pactor", NORMAL);
		ServerPlayer target = player(h, "pd_pp_ptarget", EASY);
		Capture cap = new Capture();
		try {
			PlayerRecord untouched = rec(target);
			// denied: no permission, level 0, level 1 (moderator)
			for (PermissionSet denied : List.of(PermissionSet.NO_PERMISSIONS, LevelBasedPermissionSet.ALL, LevelBasedPermissionSet.MODERATOR)) {
				for (String cmd : NEW_ADMIN_COMMANDS) {
					Outcome o = run(server, cmd, as(actor, denied, cap), cap);
					check(o.error() != null || (o.result() == 0 && !o.output().contains("is now") && !o.output().contains("reset to")), "denied for " + denied + ": " + cmd + " -> " + o);
					check(rec(target).equals(untouched), "target untouched by a denied attempt: " + cmd + " " + rec(target));
					check(rec(actor).equals(PlayerRecord.DEFAULT) || rec(actor).mode() == NORMAL && !rec(actor).locked(), "actor untouched: " + rec(actor));
				}
				Outcome exec = run(server, "execute as pd_pp_ptarget run personaldifficulty admin lock pd_pp_ptarget", as(actor, denied, cap), cap);
				check(exec.error() != null && rec(target).equals(untouched), "/execute cannot launder permissions: " + exec);
				// a denied source cannot even use the admin tree for itself
				Outcome selfAssign = run(server, "personaldifficulty admin assign pd_pp_pactor peaceful lock", as(actor, denied, cap), cap);
				check((selfAssign.error() != null || selfAssign.result() == 0) && rec(actor).mode() == NORMAL && !rec(actor).locked(), "no self-promotion: " + selfAssign);
			}

			// allowed: console, command block (level 2, no entity), level 2 and level 3 players
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
				String who = src.getTextName();
				check(run(server, "personaldifficulty admin assign pd_pp_ptarget hard lock", src, c).error() == null && rec(target).mode() == HARD && rec(target).locked(), who + ": assign+lock " + c.all());
				check(run(server, "personaldifficulty admin unlock pd_pp_ptarget", src, c).error() == null && !rec(target).locked(), who + ": unlock " + c.all());
				check(run(server, "personaldifficulty admin lock pd_pp_ptarget", src, c).error() == null && rec(target).locked(), who + ": lock " + c.all());
				check(run(server, "personaldifficulty admin restrict pd_pp_ptarget easy,hard", src, c).error() == null && rec(target).allowed().equals(EnumSet.of(EASY, HARD)), who + ": restrict " + c.all());
				check(run(server, "personaldifficulty admin unrestrict pd_pp_ptarget", src, c).error() == null && rec(target).allowed() == null, who + ": unrestrict " + c.all());
				check(run(server, "personaldifficulty admin status pd_pp_ptarget", src, c).error() == null && c.all().contains("locked: YES"), who + ": status " + c.all());
				check(run(server, "personaldifficulty admin reset pd_pp_ptarget", src, c).error() == null && rec(target).equals(PlayerRecord.DEFAULT), who + ": reset " + c.all());
				admin(server, "assign pd_pp_ptarget easy"); // back to the starting mode for the next source
			}
			Outcome exec = run(server, "execute as pd_pp_ptarget run personaldifficulty admin lock pd_pp_ptarget", as(actor, LevelBasedPermissionSet.GAMEMASTER, cap), cap);
			check(exec.error() == null && rec(target).locked(), "an operator's /execute chain keeps the operator's own permission: " + exec);
		} finally {
			restoreAvailability();
		}
		h.succeed();
	}

	@GameTest
	public void policy_realOpAndDeopTakeEffectImmediately_forTheAdminCommands(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer p = player(h, "pd_pp_realop", NORMAL);
		ServerPlayer target = player(h, "pd_pp_realop_t", NORMAL);
		try {
			Capture cap = new Capture();
			check(run(server, "personaldifficulty admin lock pd_pp_realop_t", p.createCommandSourceStack().withSource(cap), cap).error() != null, "not an operator yet");
			server.getPlayerList().op(p.nameAndId(), Optional.of(LevelBasedPermissionSet.MODERATOR), Optional.empty());
			check(run(server, "personaldifficulty admin lock pd_pp_realop_t", p.createCommandSourceStack().withSource(cap), cap).error() != null, "level 1 is not enough");
			server.getPlayerList().op(p.nameAndId(), Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
			check(run(server, "personaldifficulty admin lock pd_pp_realop_t", p.createCommandSourceStack().withSource(cap), cap).error() == null && rec(target).locked(), "level 2 works at once");
			server.getPlayerList().deop(p.nameAndId());
			check(run(server, "personaldifficulty admin unlock pd_pp_realop_t", p.createCommandSourceStack().withSource(cap), cap).error() != null && rec(target).locked(), "after /deop the same player is refused at once");
		} finally {
			server.getPlayerList().deop(p.nameAndId());
			restoreAvailability();
		}
		h.succeed();
	}

	// ------------------------------------------------------------------ failed writes through the commands

	@GameTest
	public void policy_aFailedSaveRefusesTheChange_andTheRecordIsRolledBack(GameTestHelper h) throws IOException {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer p = player(h, "pd_pp_fail", NORMAL);
		Path tmp = playersDir(server).resolve("players.json.tmp");
		Files.createDirectories(tmp.resolve("blocker")); // a directory where the temp file must go: every write fails
		try {
			PlayerRecord before = rec(p);
			Outcome a = admin(server, "assign pd_pp_fail elite lock");
			check(a.result() == 0 && a.output().contains("Could not assign") && a.output().contains("nothing was changed"), "assign reports the failure: " + a);
			check(rec(p).equals(before), "record rolled back: " + rec(p));
			Outcome s = self(server, p, PermissionSet.NO_PERMISSIONS, "hard");
			check(s.result() == 0 && s.output().contains("Could not save your setting") && rec(p).mode() == NORMAL, "self-service reports it too: " + s);
			Outcome l = admin(server, "lock pd_pp_fail");
			check(l.result() == 0 && !rec(p).locked(), "lock not claimed: " + l);
		} finally {
			Files.deleteIfExists(tmp.resolve("blocker"));
			Files.deleteIfExists(tmp);
			restoreAvailability();
		}
		check(admin(server, "assign pd_pp_fail elite lock").error() == null && rec(p).mode() == ELITE && rec(p).locked(), "once the problem is gone, writes work again");
		h.succeed();
	}

	// ------------------------------------------------------------------ 1.4.0 audit fixes: invalid records, corrupt and changed files

	private static Path playersFile(MinecraftServer server) {
		return playersDir(server).resolve("players.json");
	}

	private static String read(Path file) {
		try {
			return Files.readString(file);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static void write(Path file, String content) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, content);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static List<Path> backups(MinecraftServer server) {
		try (var files = Files.list(playersDir(server))) {
			return files.filter(x -> x.getFileName().toString().contains(".backup-")).toList();
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	/**
	 * Runs {@code body} with the live settings reloaded (as at server start) from a players file that also holds {@code rawEntry} for
	 * {@code id}; restores the previous file and state afterwards. Safe because every test body is synchronous.
	 */
	private static void withStoredEntry(MinecraftServer server, UUID id, String rawEntry, Runnable body) {
		Path file = playersFile(server);
		String original = Files.exists(file) ? read(file) : null;
		JsonObject root = original == null ? JsonParser.parseString("{\"version\":2,\"players\":{}}").getAsJsonObject() : JsonParser.parseString(original).getAsJsonObject();
		root.getAsJsonObject("players").add(id.toString(), JsonParser.parseString(rawEntry));
		write(file, root.toString());
		rt().settings().load();
		try {
			body.run();
		} finally {
			try {
				if (original == null) {
					Files.deleteIfExists(file);
				} else {
					write(file, original);
				}
			} catch (IOException e) {
				throw new AssertionError(e);
			}
			rt().settings().load();
		}
	}

	@GameTest
	public void policy_invalidStoredRecord_isQuarantinedEndToEnd_andOnlyAnExplicitRepairReplacesIt(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerLevel level = h.getLevel();
		ServerPlayer p = player(h, "pd_pp_inv", HARD);
		ServerPlayer other = player(h, "pd_pp_inv2", NORMAL);
		ServerPlayer op = player(h, "pd_pp_invop", NORMAL);
		Path file = playersFile(server);
		withStoredEntry(server, p.getUUID(), "{\"mode\":\"ELITE\",\"locked\":\"yes\",\"nickname\":\"zed\"}", () -> {
			check(rt().settings().isQuarantined(p.getUUID()), "quarantined, not silently a default player");
			check(rt().modeOf(p.getUUID()) == ELITE, "the entry's own valid mode field is what plays");
			near(8.0F, lost(p, level.damageSources().fall(), 4.0F), "the player keeps playing on Elite (x2), not on Normal");

			// self-service: refused for everyone, operators included, whatever they ask for
			for (PermissionSet perms : List.of(PermissionSet.NO_PERMISSIONS, LevelBasedPermissionSet.GAMEMASTER, LevelBasedPermissionSet.ADMIN)) {
				for (PersonalDifficultyMode m : PersonalDifficultyMode.values()) {
					Outcome o = self(server, p, perms, m.commandName());
					check(o.result() == 0 && o.output().contains("could not be read"), "refused (" + perms + ", " + m + "): " + o);
				}
			}
			check(rt().modeOf(p.getUUID()) == ELITE && rt().settings().isQuarantined(p.getUUID()), "nothing changed");

			// every administrative mutation is refused too, with the way out named; the file never changes
			String before = read(file);
			for (String cmd : List.of("assign pd_pp_inv hard", "assign pd_pp_inv normal lock", "lock pd_pp_inv", "unlock pd_pp_inv", "restrict pd_pp_inv hard,elite", "restrict pd_pp_inv easy switch", "restrict pd_pp_inv easy keep", "unrestrict pd_pp_inv")) {
				Outcome o = admin(server, cmd);
				check(o.result() == 0 && o.output().contains("invalid stored record") && o.output().contains("admin reset pd_pp_inv"), cmd + ": " + o);
			}
			Capture oc = new Capture();
			Outcome byOp = run(server, "personaldifficulty admin assign pd_pp_inv normal", as(op, LevelBasedPermissionSet.GAMEMASTER, oc), oc);
			check(byOp.output().contains("invalid stored record"), "an operator's assign does not override unreadable data: " + byOp);
			check(read(file).equals(before), "players.json byte-identical after every refused attempt");
			check(rt().modeOf(p.getUUID()) == ELITE && rt().settings().isQuarantined(p.getUUID()), "still quarantined");

			// visible to the player and to administrators
			check(self(server, p, PermissionSet.NO_PERMISSIONS, "status").output().contains("could not be read"), "player status explains");
			Outcome st = admin(server, "status pd_pp_inv");
			check(st.output().contains("INVALID STORED RECORD") && st.output().contains("\"locked\":\"yes\"") && st.output().contains("treated as LOCKED on ELITE") && st.output().contains("admin reset pd_pp_inv"), "admin status: " + st);
			check(admin(server, "config").output().contains("INVALID stored record"), "overview warns");
			check(self(server, other, PermissionSet.NO_PERMISSIONS, "hard").result() == 1, "other players are unaffected");

			// multi-target: the quarantined player is refused, the others are processed
			p.addTag("pd_mt_inv");
			other.addTag("pd_mt_inv");
			Outcome multi = admin(server, "lock @a[tag=pd_mt_inv]");
			check(multi.result() == 1 && rec(other).locked() && multi.output().contains("Refused: pd_pp_inv"), "partial success per player: " + multi);
			admin(server, "unlock pd_pp_inv2");

			// the explicit repair: verified backup first, unknown fields kept, then ordinary changes work
			String preRepair = read(file);
			Outcome fix = admin(server, "reset pd_pp_inv");
			check(fix.result() == 1 && fix.output().contains("backup: players.json.backup-") && fix.output().contains("nickname"), "repair: " + fix);
			check(backups(server).stream().anyMatch(b -> read(b).equals(preRepair)), "a backup identical to the file before the repair exists");
			check(!rt().settings().isQuarantined(p.getUUID()) && rt().modeOf(p.getUUID()) == NORMAL && !rec(p).locked(), "replaced by a default record");
			check(rec(p).extras().has("nickname") && read(file).contains("\"nickname\":\"zed\""), "unknown fields preserved");
			check(self(server, p, PermissionSet.NO_PERMISSIONS, "hard").result() == 1 && rec(p).mode() == HARD, "ordinary changes work after the repair");
		});
		h.succeed();
	}

	@GameTest
	public void policy_corruptPlayersFile_blocksEveryChange_isVisibleToAdmins_andReloadRecovers(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer p = player(h, "pd_pp_cor", HARD);
		Path file = playersFile(server);
		String original = read(file);
		try {
			write(file, "{ not json");
			rt().settings().load(); // what a restart does
			check(rt().settings().writeBlockedReason() != null, "writes are refused");
			Outcome s = self(server, p, PermissionSet.NO_PERMISSIONS, "elite");
			check(s.result() == 0 && s.output().contains("Could not save") && s.output().contains("left untouched"), "player is told why: " + s);
			Outcome a = admin(server, "assign pd_pp_cor elite");
			check(a.result() == 0 && a.output().contains("nothing was changed"), "assign refused: " + a);
			check(admin(server, "config").output().contains("WARNING: changes to players cannot be saved"), "administrators see it in the overview");
			check(read(file).equals("{ not json"), "the corrupt file was not overwritten by any attempt");
			check(backups(server).stream().anyMatch(b -> read(b).equals("{ not json")), "and a backup holds it");

			Outcome still = admin(server, "reload");
			check(still.result() == 0 && still.output().contains("still cannot be used"), "reload says it is still unusable: " + still);

			write(file, original);
			Outcome ok = admin(server, "reload");
			check(ok.result() == 1 && ok.output().contains("usable again"), "reload picks up the repaired file without a restart: " + ok);
			check(rt().modeOf(p.getUUID()) == HARD, "the saved state is back");
			check(self(server, p, PermissionSet.NO_PERMISSIONS, "elite").result() == 1, "changes work again");
		} finally {
			write(file, original);
			rt().settings().load();
		}
		h.succeed();
	}

	@GameTest
	public void policy_aFileChangedBehindTheModsBack_isNotOverwritten_untilTheAdministratorReloads(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer p = player(h, "pd_pp_ext", HARD);
		Path file = playersFile(server);
		String original = read(file);
		try {
			write(file, original + "\n");
			Outcome s = self(server, p, PermissionSet.NO_PERMISSIONS, "elite");
			check(s.result() == 0 && s.output().contains("changed outside the mod"), "refused with the reason: " + s);
			check(read(file).equals(original + "\n") && rec(p).mode() == HARD, "file untouched, change not reported active");
			check(admin(server, "config").output().contains("changed outside the mod"), "administrators see it");
			Outcome r = admin(server, "reload");
			check(r.result() == 1 && r.output().contains("usable again"), "reload: " + r);
			check(self(server, p, PermissionSet.NO_PERMISSIONS, "elite").result() == 1, "changes work again after the explicit reload");
		} finally {
			rt().settings().reloadIfBlocked();
		}
		h.succeed();
	}

	@GameTest
	public void policy_refusalReasonsFollowTheDocumentedOrder_inMessages(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer p = player(h, "pd_pp_order", NORMAL);
		try {
			admin(server, "disable elite");
			admin(server, "restrict pd_pp_order easy,hard keep");
			admin(server, "lock pd_pp_order");
			// ELITE is locked-out, globally disabled AND not in the list: the lock is reported
			Outcome locked = self(server, p, PermissionSet.NO_PERMISSIONS, "elite");
			check(locked.output().contains("locked") && !locked.output().contains("disabled") && !locked.output().contains("not in the list"), "lock first: " + locked);
			admin(server, "unlock pd_pp_order");
			Outcome disabled = self(server, p, PermissionSet.NO_PERMISSIONS, "elite");
			check(disabled.output().contains("disabled by the server") && !disabled.output().contains("not in the list"), "then global policy: " + disabled);
			admin(server, "enable elite");
			Outcome notListed = self(server, p, PermissionSet.NO_PERMISSIONS, "elite");
			check(notListed.output().contains("not in the list"), "then the allow-list: " + notListed);
		} finally {
			restoreAvailability();
		}
		h.succeed();
	}

	@GameTest
	public void policy_reset_keepsUnknownFields_andMultiTargetSelectorsActPerPlayer(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer a = player(h, "pd_pp_mt_a", HARD);
		ServerPlayer b = player(h, "pd_pp_mt_b", ELITE);
		ServerPlayer c = player(h, "pd_pp_mt_c", EASY);
		for (ServerPlayer p : List.of(a, b)) {
			p.addTag("pd_mt_set");
		}
		try {
			// reset keeps unknown stored fields
			JsonObject extras = new JsonObject();
			extras.addProperty("nickname", "zed");
			try {
				rt().settings().put(c.getUUID(), new PlayerRecord(HARD, true, EnumSet.of(HARD), null, 0L, extras));
			} catch (IOException e) {
				throw new AssertionError(e);
			}
			Outcome reset = admin(server, "reset pd_pp_mt_c");
			check(reset.output().contains("reset to NORMAL"), "reset: " + reset);
			check(rec(c).mode() == NORMAL && !rec(c).locked() && rec(c).allowed() == null, "mode, lock and allow-list cleared: " + rec(c));
			check(rec(c).extras().has("nickname") && read(playersFile(server)).contains("\"nickname\":\"zed\""), "unknown field survived the reset, in memory and on disk");
			check(rt().settings().hasExplicit(c.getUUID()), "the record stays because it still carries data");

			// selectors: each target is decided on its own
			Outcome lock = admin(server, "lock @a[tag=pd_mt_set]");
			check(lock.result() == 2 && rec(a).locked() && rec(b).locked() && !rec(c).locked(), "both tagged players, nobody else: " + lock);
			check(admin(server, "lock @a[tag=pd_mt_set]").output().contains("already locked"), "idempotent per player");
			admin(server, "unlock @a[tag=pd_mt_set]");
			Outcome restrict = admin(server, "restrict @a[tag=pd_mt_set] easy,elite");
			check(restrict.result() == 1 && rec(b).allowed() != null && rec(a).allowed() == null && restrict.output().contains("Refused: pd_pp_mt_a"),
				"b (ELITE, in the list) is restricted, a (HARD, not in the list) is refused without a flag: " + restrict);
			Outcome sw = admin(server, "restrict @a[tag=pd_mt_set] easy,elite switch");
			check(rec(a).mode() == EASY && rec(a).allowed() != null && rec(b).mode() == ELITE, "with switch a moves to EASY, b stays: " + sw + " " + rec(a) + " " + rec(b));
			Outcome assign = admin(server, "assign @a[tag=pd_mt_set] peaceful lock");
			check(assign.result() == 2 && rec(a).mode() == PEACEFUL && rec(b).mode() == PEACEFUL && rec(a).locked() && rec(b).locked(), "assign to both: " + assign);
			Outcome all = admin(server, "reset @a[tag=pd_mt_set]");
			check(all.result() == 2 && !rt().settings().hasExplicit(a.getUUID()) && !rt().settings().hasExplicit(b.getUUID()), "reset both: " + all);
			check(admin(server, "unrestrict @a[tag=pd_mt_set]").output().contains("no allow-list"), "nothing left to unrestrict");
		} finally {
			restoreAvailability();
		}
		h.succeed();
	}
}
