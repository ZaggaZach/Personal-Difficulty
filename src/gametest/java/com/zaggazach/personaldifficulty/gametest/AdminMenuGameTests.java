package com.zaggazach.personaldifficulty.gametest;

import static com.zaggazach.personaldifficulty.PersonalDifficultyMode.*;
import static com.zaggazach.personaldifficulty.gametest.PdTestSupport.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.admin.AdminActor;
import com.zaggazach.personaldifficulty.gametest.PdTestSupport.Capture;
import com.zaggazach.personaldifficulty.gametest.PdTestSupport.Outcome;
import com.zaggazach.personaldifficulty.store.PlayerRecord;
import com.zaggazach.personaldifficulty.ui.ClickKind;
import com.zaggazach.personaldifficulty.ui.PdAdminMenu;
import com.zaggazach.personaldifficulty.ui.PdMenu;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The admin menus on the real menu lifecycle (Phase 3): real {@code PlayerList} ops, real {@code openMenu}, real server tick that
 * closes the screen of a demoted operator. Packet-level behaviour (stale container ids, malformed packets, floods) cannot be reached
 * with mock players and is covered by the headless protocol tests in {@code AdminGuiE2eTest}.
 */
public class AdminMenuGameTests {
	// ------------------------------------------------------------------ helpers

	private static final class Recorder implements AdminActor.Feedback {
		final List<String> messages = new ArrayList<>();
		int broadcasts;

		@Override
		public void success(String message, boolean broadcast) {
			messages.add(message);
			if (broadcast) {
				broadcasts++;
			}
		}

		@Override
		public void failure(String message) {
			messages.add(message);
		}
	}

	/** Every mock player a test creates; all of them leave the server again at the end of the test (see {@link #finish}). */
	private static final List<ServerPlayer> MADE = new ArrayList<>();

	private static ServerPlayer mock(GameTestHelper h, String name, PersonalDifficultyMode mode) {
		ServerPlayer p = player(h, name, mode);
		MADE.add(p);
		return p;
	}

	private static void finish() {
		List<ServerPlayer> copy = new ArrayList<>(MADE);
		MADE.clear();
		for (ServerPlayer p : copy) {
			deop(p);
			p.closeContainer();
			putId(p.getUUID(), PlayerRecord.DEFAULT);
			leave(p);
		}
	}

	private static ServerPlayer op(GameTestHelper h, String name) {
		ServerPlayer p = mock(h, name, NORMAL);
		h.getLevel().getServer().getPlayerList().op(p.nameAndId(), Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
		return p;
	}

	private static void deop(ServerPlayer p) {
		p.level().getServer().getPlayerList().deop(p.nameAndId());
	}

	private static Recorder open(ServerPlayer admin) {
		Recorder rec = new Recorder();
		check(com.zaggazach.personaldifficulty.ui.AdminMenu.open(admin, rec), "the menu opens for an operator");
		return rec;
	}

	// ---- the 1.3.1-rc.2 layout (see AdminMenu): one player page, a fixed navigation bar
	private static final int HUB_PLAYERS = 20;
	private static final int HUB_DIFFICULTIES = 24;
	private static final int SAVE = 35;
	private static final int LOCK = 38;
	private static final int ALLOW_ALL = 40;
	private static final int RESET = 42;
	private static final int REPAIR = 22;
	private static final int ENABLE_ALL = 31;
	private static final int CONFIRM_LEFT = 20;
	private static final int CONFIRM_MIDDLE = 22;
	private static final int CONFIRM_RIGHT = 24;
	private static final int BACK = 45;
	private static final int CLOSE = 49;
	private static final int NEXT = 53;

	/** Player page, "Set difficulty" row. */
	private static int set(PersonalDifficultyMode m) {
		return 20 + m.ordinal();
	}

	/** Player page, "Allowed difficulties" row. */
	private static int allow(PersonalDifficultyMode m) {
		return 29 + m.ordinal();
	}

	/** Server Difficulties page. */
	private static int policy(PersonalDifficultyMode m) {
		return 20 + m.ordinal();
	}

	/** On the player page of someone WITHOUT a stored allow-list (every box starts ticked): the clicks that untick all but {@code keep}. */
	private static int[] untickAllBut(PersonalDifficultyMode... keep) {
		EnumSet<PersonalDifficultyMode> k = keep.length == 0 ? EnumSet.noneOf(PersonalDifficultyMode.class) : EnumSet.copyOf(List.of(keep));
		return EnumSet.complementOf(k).stream().mapToInt(AdminMenuGameTests::allow).toArray();
	}

	private static void clickAll(ServerPlayer p, int... slots) {
		for (int slot : slots) {
			click(p, slot);
		}
	}

	private static void click(ServerPlayer p, int slot) {
		p.containerMenu.clicked(slot, 0, ContainerInput.PICKUP, p);
	}

	private static String label(ServerPlayer p, int slot) {
		ItemStack s = p.containerMenu.getSlot(slot).getItem();
		return s.isEmpty() ? "" : s.getHoverName().getString();
	}

	private static boolean isMenu(ServerPlayer p) {
		return p.containerMenu instanceof PdMenu;
	}

	private static PlayerRecord rec(ServerPlayer p) {
		return rt().settings().record(p.getUUID());
	}

	/** From the hub: opens Player Management and pages until the player is listed, then selects them. */
	private static void selectPlayer(ServerPlayer admin, String name) {
		click(admin, HUB_PLAYERS);
		for (int pageNo = 0; pageNo < 60; pageNo++) {
			for (int slot = 0; slot < 45; slot++) {
				if (label(admin, slot).equals(name)) {
					click(admin, slot);
					check(label(admin, 4).equals(name) && label(admin, SAVE).equals("Save allowed list"), "the player screen is shown for " + name);
					return;
				}
			}
			click(admin, NEXT);
		}
		throw new AssertionError(name + " was not found in the player list");
	}

	private static void put(ServerPlayer p, PlayerRecord r) {
		putId(p.getUUID(), r);
	}

	private static void putId(UUID id, PlayerRecord r) {
		try {
			rt().settings().put(id, r);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static Path playersFile(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("personaldifficulty").resolve("players.json");
	}

	private static String snapshot(MinecraftServer server) {
		try {
			Path f = playersFile(server);
			return (Files.exists(f) ? Files.readString(f) : "") + "|" + rt().availability().enabledModes();
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static String norm(String s, String... names) {
		for (String n : names) {
			s = s.replace(n, "X");
		}
		return s.replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<uuid>").replaceAll("\\d+ saved selection", "<n> saved selection");
	}

	// ------------------------------------------------------------------ opening and permissions

	@GameTest
	public void admin_open_isRefusedForNonOperators_allowedForOperators_andNotForTheConsole(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer plain = mock(h, "0am_plain", NORMAL);
		ServerPlayer moderator = mock(h, "0am_mod", NORMAL);
		ServerPlayer admin = op(h, "0am_op");
		try {
			check(!com.zaggazach.personaldifficulty.ui.AdminMenu.open(plain), "a non-operator cannot open the menu");
			check(!isMenu(plain), "and nothing was opened");
			Capture cap = new Capture();
			Outcome viaCommand = run(server, "personaldifficulty admin gui", as(plain, PermissionSet.NO_PERMISSIONS, cap), cap);
			check(viaCommand.error() != null, "the command is not even available to a non-operator: " + viaCommand);
			Capture capMod = new Capture();
			Outcome mod = run(server, "personaldifficulty admin gui", as(moderator, LevelBasedPermissionSet.MODERATOR, capMod), capMod);
			check(mod.error() != null && !isMenu(moderator), "level 1 is not enough: " + mod);
			Capture capOp = new Capture();
			Outcome ok = run(server, "personaldifficulty admin gui", as(admin, LevelBasedPermissionSet.GAMEMASTER, capOp), capOp);
			check(ok.result() == 1 && isMenu(admin), "an operator gets the menu: " + ok);
			check(label(admin, HUB_PLAYERS).equals("Players") && label(admin, HUB_DIFFICULTIES).equals("Server Difficulties") && label(admin, CLOSE).equals("Close"), "the hub is shown: " + label(admin, 20));
			check(admin.containerMenu.getSlot(HUB_PLAYERS).getItem().getItem() == net.minecraft.world.item.Items.PLAYER_HEAD, "Players is shown as a player head");
			Capture capConsole = new Capture();
			Outcome console = run(server, "personaldifficulty admin gui", console(server, capConsole), capConsole);
			check(console.result() == 0 && console.output().contains("only be opened by a player"), "the console is told to use the commands: " + console);
		} finally {
			finish();
		}
		h.succeed();
	}

	@GameTest
	public void admin_demotion_closesTheScreenOnTheNextTick_refusesClicksBeforeThat_andChangesNothing(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer admin = op(h, "0am_demote");
		ServerPlayer target = mock(h, "0am_demote_t", NORMAL);
		try {
			open(admin);
			selectPlayer(admin, "0am_demote_t");
			var menu = admin.containerMenu;
			click(admin, set(HARD));
			check(rec(target).mode() == HARD, "while an operator the assignment works");
			String before = snapshot(server);

			deop(admin);
			// the very first thing that must happen: the menu itself reports it is no longer valid (before ANY click can matter)
			check(!menu.stillValid(admin), "stillValid is false immediately after /deop");
			// between /deop and the next tick the screen is still open, but every click is refused
			for (int slot = 0; slot < 54; slot++) {
				menu.clicked(slot, 0, ContainerInput.PICKUP, admin);
				menu.clicked(slot, 1, ContainerInput.PICKUP, admin);
			}
			check(snapshot(server).equals(before), "no click of a demoted operator changed anything");
			check(admin.containerMenu == menu, "the clicks themselves did not close or navigate the screen (it is closed by the server tick, below)");
			check(label(admin, 4).equals("0am_demote_t") && label(admin, BACK).equals("Back"), "and the screen content is unchanged: " + label(admin, BACK));
			admin.doTick();
			check(admin.containerMenu == admin.inventoryMenu, "the server closes the demoted operator's screen on its next tick");
			// the stale menu object, even if a client kept sending clicks for it
			menu.clicked(set(ELITE), 0, ContainerInput.PICKUP, admin);
			check(snapshot(server).equals(before) && rec(target).mode() == HARD, "still nothing changed");
		} finally {
			finish();
		}
		h.succeed();
	}

	@GameTest
	public void admin_demotionWhileAConfirmationIsOpen_cannotBeConfirmed(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer admin = op(h, "0am_demconf");
		ServerPlayer target = mock(h, "0am_demconf_t", NORMAL);
		try {
			open(admin);
			selectPlayer(admin, "0am_demconf_t");
			clickAll(admin, untickAllBut(HARD)); // the allowed list starts with every box ticked; leave HARD only
			click(admin, SAVE); // NORMAL is not in the list, so a confirmation is shown
			check(label(admin, CONFIRM_LEFT).startsWith("Switch") && label(admin, CONFIRM_MIDDLE).startsWith("Keep") && label(admin, CONFIRM_RIGHT).equals("Cancel"), "Switch / Keep / Cancel are offered: " + label(admin, 20));
			String before = snapshot(server);
			deop(admin);
			var menu = admin.containerMenu;
			check(!menu.stillValid(admin), "stillValid is false immediately after /deop");
			menu.clicked(20, 0, ContainerInput.PICKUP, admin);
			menu.clicked(22, 0, ContainerInput.PICKUP, admin);
			check(snapshot(server).equals(before), "a confirmation by a demoted operator changes nothing");
			check(admin.containerMenu == menu && label(admin, CONFIRM_LEFT).startsWith("Switch"), "and the clicks did not navigate the screen");
			admin.doTick();
			check(admin.containerMenu == admin.inventoryMenu, "the next tick closes it");
			check(rec(target).allowed() == null && rec(target).mode() == NORMAL, "no allow-list, no switch");
		} finally {
			finish();
		}
		h.succeed();
	}

	// ------------------------------------------------------------------ command / GUI parity

	private record Scenario(String name, PlayerRecord start, int[] slots, String command) {
	}

	@GameTest
	public void admin_everyPlayerManagementAction_producesTheSameStateAndMessagesAsItsCommand(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer admin = op(h, "0am_par_op");
		long now = 1L;
		PlayerRecord hard = PlayerRecord.DEFAULT.withMode(HARD);
		PlayerRecord lockedElite = PlayerRecord.DEFAULT.withMode(ELITE).withLocked(true, null, now);
		PlayerRecord restricted = PlayerRecord.DEFAULT.withMode(HARD).withAllowed(EnumSet.of(HARD, ELITE), null, now);
		List<Scenario> scenarios = new ArrayList<>();
		for (PersonalDifficultyMode m : PersonalDifficultyMode.values()) {
			scenarios.add(new Scenario("assign " + m, hard, new int[] {set(m)}, "assign %s " + m.commandName()));
			// the menu no longer has a combined "assign and lock" button; assigning a locked player keeps the lock, like the command
			scenarios.add(new Scenario("assign while locked " + m, lockedElite, new int[] {set(m)}, "assign %s " + m.commandName()));
		}
		scenarios.add(new Scenario("assign same mode", hard, new int[] {set(HARD)}, "assign %s hard"));
		scenarios.add(new Scenario("lock", hard, new int[] {LOCK}, "lock %s"));
		scenarios.add(new Scenario("unlock", lockedElite, new int[] {LOCK}, "unlock %s"));
		scenarios.add(new Scenario("unrestrict", restricted, new int[] {ALLOW_ALL}, "unrestrict %s"));
		scenarios.add(new Scenario("unrestrict none", hard, new int[] {ALLOW_ALL}, "unrestrict %s"));
		scenarios.add(new Scenario("reset", lockedElite, new int[] {RESET, CONFIRM_LEFT}, "reset %s"));
		scenarios.add(new Scenario("reset nothing stored", PlayerRecord.DEFAULT, new int[] {RESET, CONFIRM_LEFT}, "reset %s"));
		int[] withinCurrent = java.util.stream.IntStream.concat(java.util.Arrays.stream(untickAllBut(HARD, ELITE)), java.util.stream.IntStream.of(SAVE)).toArray();
		scenarios.add(new Scenario("restrict within current", hard, withinCurrent, "restrict %s hard,elite"));
		scenarios.add(new Scenario("restrict same list", restricted, new int[] {SAVE}, "restrict %s hard,elite"));
		try {
			int index = 0;
			for (Scenario s : scenarios) {
				String cmdName = "0am_pc" + index;
				String menuName = "0am_pg" + index++;
				ServerPlayer viaCommand = mock(h, cmdName, NORMAL);
				ServerPlayer viaMenu = mock(h, menuName, NORMAL);
				put(viaCommand, s.start());
				put(viaMenu, s.start());
				Capture cap = new Capture();
				Outcome out = run(server, "personaldifficulty admin " + s.command().formatted(cmdName), console(server, cap), cap);

				admin.closeContainer();
				Recorder feedback = open(admin);
				selectPlayer(admin, menuName);
				for (int slot : s.slots()) {
					click(admin, slot);
				}
				check(rec(viaCommand).sameState(rec(viaMenu)), s.name() + ": same resulting state (command " + rec(viaCommand) + " vs menu " + rec(viaMenu) + ")");
				List<String> expected = out.output().isEmpty() ? List.of() : List.of(norm(out.output(), cmdName, menuName));
				List<String> actual = feedback.messages.stream().map(m -> norm(m, cmdName, menuName)).toList();
				check(expected.equals(actual), s.name() + ": same message. command=" + expected + " menu=" + actual);
				check(feedback.messages.size() <= 1, s.name() + ": at most one message per action (no duplicate audit line): " + feedback.messages);
			}
		} finally {
			finish();
		}
		h.succeed();
	}

	@GameTest
	public void admin_eachChangeIsReportedExactlyOnce_andBroadcastLikeTheCommand(GameTestHelper h) {
		ServerPlayer admin = op(h, "0am_once_op");
		ServerPlayer target = mock(h, "0am_once_t", NORMAL);
		try {
			Recorder feedback = open(admin);
			selectPlayer(admin, "0am_once_t");
			click(admin, set(HARD));
			check(feedback.messages.size() == 1 && feedback.broadcasts == 1, "one message, broadcast to operators: " + feedback.messages + " " + feedback.broadcasts);
			click(admin, set(HARD));
			check(feedback.messages.size() == 2 && feedback.broadcasts == 1, "'nothing changed' is reported but not broadcast: " + feedback.messages);
			click(admin, LOCK);
			check(feedback.messages.size() == 3 && feedback.broadcasts == 2, "lock: " + feedback.messages);
			check(rec(target).locked(), "locked");
			check(label(admin, LOCK).equals("Lock: ON"), "the lock button shows its state: " + label(admin, LOCK));
			// an empty allow-list is refused once
			clickAll(admin, untickAllBut());
			click(admin, SAVE);
			check(feedback.messages.size() == 4 && feedback.messages.get(3).contains("allow-list cannot be empty"), "empty list refused once: " + feedback.messages);
			check(rec(target).allowed() == null, "nothing stored");
		} finally {
			finish();
		}
		h.succeed();
	}

	// ------------------------------------------------------------------ restriction confirmation (D5)

	@GameTest
	public void admin_restrictionThatExcludesTheCurrentMode_asksSwitchKeepCancel_andEachDoesExactlyThat(GameTestHelper h) {
		ServerPlayer admin = op(h, "0am_conf_op");
		ServerPlayer a = mock(h, "0am_conf_a", NORMAL);
		ServerPlayer b = mock(h, "0am_conf_b", NORMAL);
		ServerPlayer c = mock(h, "0am_conf_c", NORMAL);
		try {
			for (int round = 0; round < 3; round++) {
				ServerPlayer t = round == 0 ? a : round == 1 ? b : c;
				admin.closeContainer();
				open(admin);
				selectPlayer(admin, t.getGameProfile().name());
				clickAll(admin, untickAllBut(HARD, ELITE));
				click(admin, SAVE);
				check(label(admin, CONFIRM_LEFT).startsWith("Switch"), "asked: " + label(admin, 20));
				check(rec(t).allowed() == null && rec(t).mode() == NORMAL, "nothing stored while asking");
				click(admin, 4); // not a button
				click(admin, 21); // not a button
				check(rec(t).allowed() == null, "stray clicks on the confirmation do nothing");
				switch (round) {
					case 0 -> {
						click(admin, 20);
						check(rec(t).mode() == HARD && rec(t).allowed().equals(EnumSet.of(HARD, ELITE)), "Switch: moved to the lowest listed mode and restricted: " + rec(t));
					}
					case 1 -> {
						click(admin, 22);
						check(rec(t).mode() == NORMAL && rec(t).allowed().equals(EnumSet.of(HARD, ELITE)) && rec(t).isException(), "Keep: stays on NORMAL as an exception: " + rec(t));
					}
					default -> {
						click(admin, CONFIRM_RIGHT);
						check(rec(t).mode() == NORMAL && rec(t).allowed() == null && !rec(t).locked(), "Cancel: nothing changed: " + rec(t));
						check(label(admin, SAVE).equals("Save allowed list"), "back on the player page");
					}
				}
			}
		} finally {
			finish();
		}
		h.succeed();
	}

	// ------------------------------------------------------------------ quarantined records (D6)

	@GameTest
	public void admin_quarantinedRecord_isShownAsInvalid_refusesEverythingButTheExplicitRepair(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer admin = op(h, "0am_q_op");
		ServerPlayer target = mock(h, "0am_q_t", HARD);
		Path file = playersFile(server);
		String original;
		try {
			original = Files.exists(file) ? Files.readString(file) : null;
			JsonObject root = original == null ? JsonParser.parseString("{\"version\":2,\"players\":{}}").getAsJsonObject() : JsonParser.parseString(original).getAsJsonObject();
			root.getAsJsonObject("players").add(target.getUUID().toString(), JsonParser.parseString("{\"mode\":\"ELITE\",\"locked\":\"yes\",\"nickname\":\"zed\"}"));
			Files.createDirectories(file.getParent());
			Files.writeString(file, root.toString());
			rt().settings().load();
			try {
				check(rt().settings().isQuarantined(target.getUUID()), "quarantined");
				open(admin);
				click(admin, HUB_PLAYERS);
				boolean listedAsInvalid = false;
				for (int pageNo = 0; pageNo < 60 && !listedAsInvalid; pageNo++) {
					for (int slot = 0; slot < 45; slot++) {
						if (label(admin, slot).equals("0am_q_t")) {
							check(admin.containerMenu.getSlot(slot).getItem().getItem() == net.minecraft.world.item.Items.BARRIER, "the list marks it invalid");
							click(admin, slot);
							listedAsInvalid = true;
							break;
						}
					}
					if (!listedAsInvalid) {
						click(admin, NEXT);
					}
				}
				check(listedAsInvalid, "found in the list");
				check(label(admin, 22).equals("Repair invalid record"), "only the repair is offered: " + label(admin, 22));
				for (int slot : new int[] {18, 20, 21, 23, 24, 27, 29, 30, 31, 32, 33, 35, 38, 40, 42}) {
					check(label(admin, slot).isEmpty(), "no ordinary action is offered in slot " + slot + ": " + label(admin, slot));
				}
				String before = Files.readString(file);
				for (int slot = 0; slot < 54; slot++) {
					if (slot != REPAIR && slot != BACK && slot != CLOSE) {
						click(admin, slot);
						admin.containerMenu.clicked(slot, 1, ContainerInput.PICKUP, admin);
					}
				}
				check(Files.readString(file).equals(before) && rt().settings().isQuarantined(target.getUUID()), "no other button touches the record");

				click(admin, 22);
				check(!rt().settings().isQuarantined(target.getUUID()) && rec(target).mode() == NORMAL, "the explicit repair replaced it with a default record");
				check(rec(target).extras().has("nickname"), "unknown fields kept");
				try (var files = Files.list(file.getParent())) {
					check(files.anyMatch(p -> p.getFileName().toString().startsWith("players.json.backup-") && read(p).equals(before)), "a verified backup of the invalid file exists");
				}
			} finally {
				if (original == null) {
					Files.deleteIfExists(file);
				} else {
					Files.writeString(file, original);
				}
				rt().settings().load();
			}
		} catch (IOException e) {
			throw new AssertionError(e);
		} finally {
			finish();
		}
		h.succeed();
	}

	private static String read(Path p) {
		try {
			return Files.readString(p);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	// ------------------------------------------------------------------ difficulty permissions

	@GameTest
	public void admin_permissionsPage_togglesTheServerPolicy_neverTheLastDifficulty_andEnableAllRestores(GameTestHelper h) {
		ServerPlayer admin = op(h, "0am_pol_op");
		try {
			Recorder feedback = open(admin);
			click(admin, HUB_DIFFICULTIES);
			for (PersonalDifficultyMode m : PersonalDifficultyMode.values()) {
				check(label(admin, policy(m)).startsWith(m.name().charAt(0) + m.name().substring(1).toLowerCase(java.util.Locale.ROOT) + ":"), "difficulties in order: " + label(admin, policy(m)));
			}
			check(label(admin, policy(HARD)).equals("Hard: ON"), "shown: " + label(admin, policy(HARD)));
			click(admin, policy(HARD));
			check(!rt().availability().isEnabled(HARD) && label(admin, policy(HARD)).equals("Hard: OFF"), "disabled and re-rendered");
			click(admin, policy(HARD));
			check(rt().availability().isEnabled(HARD), "toggles back");
			for (PersonalDifficultyMode m : List.of(EASY, HARD, ELITE, PEACEFUL)) {
				click(admin, policy(m));
			}
			check(rt().availability().enabledModes().equals(List.of(NORMAL)), "only NORMAL left: " + rt().availability().enabledModes());
			int messages = feedback.messages.size();
			click(admin, policy(NORMAL));
			check(rt().availability().isEnabled(NORMAL) && feedback.messages.size() == messages + 1 && feedback.messages.get(messages).contains("last enabled difficulty"),
				"the last difficulty cannot be disabled: " + feedback.messages.get(feedback.messages.size() - 1));
			click(admin, ENABLE_ALL);
			check(rt().availability().enabledModes().size() == 5, "enable all");
		} finally {
			try {
				rt().availability().enableAll();
			} catch (IOException ignored) {
				// best effort
			}
			finish();
		}
		h.succeed();
	}

	@GameTest
	public void admin_allowedList_onThePlayerPage_isSavedOnlyBySave_andAllowAllRemovesIt(GameTestHelper h) {
		ServerPlayer admin = op(h, "0am_ale_op");
		ServerPlayer target = mock(h, "0am_ale_t", HARD);
		try {
			open(admin);
			selectPlayer(admin, "0am_ale_t");
			for (PersonalDifficultyMode m : PersonalDifficultyMode.values()) {
				check(label(admin, allow(m)).endsWith(": allowed"), "with no stored list every box starts ticked: " + label(admin, allow(m)));
			}
			clickAll(admin, untickAllBut(HARD, PEACEFUL));
			check(label(admin, allow(EASY)).endsWith(": not allowed") && label(admin, allow(HARD)).endsWith(": allowed"), "ticks are shown");
			check(rec(target).allowed() == null, "nothing is stored until Save");
			click(admin, SAVE);
			check(rec(target).allowed().equals(EnumSet.of(HARD, PEACEFUL)) && rec(target).mode() == HARD, "saved without a question (the current mode is listed): " + rec(target));
			click(admin, ALLOW_ALL);
			check(rec(target).allowed() == null, "removed");
			check(label(admin, allow(EASY)).endsWith(": allowed"), "and the boxes are all ticked again");
			click(admin, SAVE);
			check(rec(target).allowed() == null, "saving an untouched all-ticked list does not create a list");
		} finally {
			finish();
		}
		h.succeed();
	}

	// ------------------------------------------------------------------ hostile clicks (D4)

	@GameTest
	public void admin_hostileClicks_ofEveryKindOnEverySlotOfEveryScreen_changeNothing(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer admin = op(h, "0am_host_op");
		ServerPlayer target = mock(h, "0am_host_t", HARD);
		try {
			open(admin);
			// walk through every screen; on each, throw everything that is NOT a plain press at it, and plain presses at invalid slots
			List<Runnable> screens = List.of(
				() -> {
				},
				() -> click(admin, HUB_PLAYERS),
				() -> pickFromCurrentList(admin, "0am_host_t"),
				() -> {
					clickAll(admin, untickAllBut(EASY));
					click(admin, SAVE); // HARD is not in {EASY}: the Switch / Keep / Cancel screen
				},
				() -> {
					click(admin, CONFIRM_RIGHT); // back to the player page
					click(admin, RESET); // the reset confirmation
				},
				() -> {
					click(admin, CONFIRM_RIGHT);
					click(admin, BACK);
					click(admin, BACK);
					click(admin, HUB_DIFFICULTIES);
				});
			int[] extraSlots = {-1000, -1, 54, 55, 80, 1000, Integer.MAX_VALUE, Integer.MIN_VALUE};
			int[] buttons = {-1, 0, 1, 2, 3, 8, 9, 40, 41, 127, 255, Integer.MAX_VALUE, Integer.MIN_VALUE};
			for (Runnable goTo : screens) {
				goTo.run();
				String before = snapshot(server);
				PlayerRecord recBefore = rec(target);
				var menu = admin.containerMenu;
				for (ContainerInput input : ContainerInput.values()) {
					for (int slot = -3; slot < 60; slot++) {
						for (int button : buttons) {
							boolean plainPress = input == ContainerInput.PICKUP && (button == 0 || button == 1);
							if (plainPress && slot >= 0 && slot < 54) {
								continue; // a legitimate click; its effect is covered by the other tests
							}
							menu.clicked(slot, button, input, admin);
						}
					}
					for (int slot : extraSlots) {
						for (int button : buttons) {
							menu.clicked(slot, button, input, admin);
						}
					}
				}
				check(snapshot(server).equals(before) && rec(target).sameState(recBefore), "a hostile storm changed state");
				check(admin.containerMenu == menu, "and did not even close the screen");
			}
		} finally {
			finish();
		}
		h.succeed();
	}

	/** On the player list already: pages until the player is listed and selects them. */
	private static void pickFromCurrentList(ServerPlayer admin, String name) {
		for (int pageNo = 0; pageNo < 60; pageNo++) {
			for (int slot = 0; slot < 45; slot++) {
				if (label(admin, slot).equals(name)) {
					click(admin, slot);
					return;
				}
			}
			click(admin, NEXT);
		}
		throw new AssertionError(name + " not listed");
	}

	@GameTest
	public void admin_plainPressesOnFillersAndPlayerInventorySlotsDoNothing_andNoItemEverLeavesTheMenu(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer admin = op(h, "0am_fill_op");
		try {
			open(admin);
			String before = snapshot(server);
			for (int slot = 0; slot < admin.containerMenu.slots.size(); slot++) {
				if (slot == 20 || slot == 24 || slot == 49) {
					continue; // real buttons
				}
				admin.containerMenu.clicked(slot, 0, ContainerInput.PICKUP, admin);
				admin.containerMenu.clicked(slot, 1, ContainerInput.PICKUP, admin);
				admin.containerMenu.clicked(slot, 0, ContainerInput.QUICK_MOVE, admin);
			}
			check(snapshot(server).equals(before), "nothing changed");
			check(admin.containerMenu.getCarried().isEmpty(), "the cursor holds nothing");
			for (int i = 0; i < admin.getInventory().getContainerSize(); i++) {
				check(!com.zaggazach.personaldifficulty.ui.UiItems.isUi(admin.getInventory().getItem(i)), "no menu item reached the real inventory (slot " + i + ")");
			}
		} finally {
			finish();
		}
		h.succeed();
	}

	// ------------------------------------------------------------------ offline players

	@GameTest
	public void admin_theListShowsOfflinePlayersWithStoredRecords_andViewingCreatesNoRecord(GameTestHelper h) {
		ServerPlayer admin = op(h, "0am_off_op");
		UUID ghost = UUID.randomUUID();
		try {
			putId(ghost, PlayerRecord.DEFAULT.withMode(ELITE));
			int explicit = rt().settings().explicitCount();
			open(admin);
			click(admin, HUB_PLAYERS);
			String ghostLabel = ghost.toString();
			boolean found = false;
			for (int pageNo = 0; pageNo < 80 && !found; pageNo++) {
				for (int slot = 0; slot < 45; slot++) {
					if (label(admin, slot).equals(ghostLabel)) {
						found = true;
						ItemStack head = admin.containerMenu.getSlot(slot).getItem();
						check(head.getItem() == net.minecraft.world.item.Items.PLAYER_HEAD, "an offline player is shown as a player head");
						var lore = head.get(net.minecraft.core.component.DataComponents.LORE);
						check(lore != null && lore.lines().stream().anyMatch(c -> c.getString().equals("Difficulty: Elite")), "with their stored difficulty in the tooltip: " + lore);
						click(admin, slot);
						break;
					}
				}
				if (!found) {
					click(admin, NEXT);
				}
			}
			check(found, "an offline player known only from the settings file (name unknown) is listed by UUID");
			check(rt().settings().explicitCount() == explicit, "viewing created no record");
		} finally {
			putId(ghost, PlayerRecord.DEFAULT);
			finish();
		}
		h.succeed();
	}

	// ------------------------------------------------------------------ the explicit onPress authorisation (kept as its own layer)

	/** A minimal administrator menu that records whether the administrative action was ever reached, and lets the test call the protected onPress. */
	public static final class ProbeMenu extends PdAdminMenu {
		int actions;

		ProbeMenu(int containerId, ServerPlayer player) {
			super(containerId, player, 3, com.zaggazach.personaldifficulty.admin.AdminActor.fromPlayer(player));
		}

		@Override
		protected void onAdminPress(int slot, ClickKind kind) {
			actions++;
		}

		void press(int slot) {
			onPress(slot, ClickKind.PRESS_LEFT);
		}
	}

	@GameTest
	public void admin_onPress_calledDirectlyByANonOperator_isRefusedAndClosesTheMenu_anOperatorReachesTheAction(GameTestHelper h) {
		ServerPlayer plain = mock(h, "0am_probe_plain", NORMAL);
		ServerPlayer admin = op(h, "0am_probe_op");
		try {
			// bypass clicked()/stillValid entirely: call the protected onPress path itself with a player who is not an operator
			PdMenu.open(plain, Component.literal("probe"), (id, inv, p) -> new ProbeMenu(id, plain));
			check(plain.containerMenu instanceof ProbeMenu, "the probe menu is open (raw PdMenu.open does not check permissions; that is the point of this layer)");
			ProbeMenu probe = (ProbeMenu) plain.containerMenu;
			probe.press(3);
			check(probe.actions == 0, "the administrative action was NOT invoked for a non-operator");
			check(plain.containerMenu == plain.inventoryMenu, "and the menu was closed");

			PdMenu.open(admin, Component.literal("probe"), (id, inv, p) -> new ProbeMenu(id, admin));
			ProbeMenu ok = (ProbeMenu) admin.containerMenu;
			ok.press(3);
			check(ok.actions == 1 && admin.containerMenu == ok, "an operator reaches the action and keeps the menu");
			deop(admin);
			ok.press(3);
			check(ok.actions == 1, "after /deop the same menu object refuses a direct onPress too");
			check(admin.containerMenu == admin.inventoryMenu, "and closes");
		} finally {
			finish();
		}
		h.succeed();
	}

	// ------------------------------------------------------------------ parity: server-wide policy and restriction confirmation

	private static void setPolicy(List<PersonalDifficultyMode> disabled) {
		try {
			rt().availability().enableAll();
			for (PersonalDifficultyMode m : disabled) {
				rt().availability().setEnabled(m, false);
			}
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private record PolicyCase(String name, List<PersonalDifficultyMode> disabledAtStart, String command, int menuSlot) {
	}

	@GameTest
	public void admin_enableDisableAndEnableAll_matchTheirCommands_stateAndMessage(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer admin = op(h, "0am_pcmp_op");
		List<PolicyCase> cases = List.of(
			new PolicyCase("disable HARD", List.of(), "disable hard", policy(HARD)),
			new PolicyCase("disable ELITE", List.of(), "disable elite", policy(ELITE)),
			new PolicyCase("enable HARD", List.of(HARD), "enable hard", policy(HARD)),
			new PolicyCase("enable PEACEFUL", List.of(PEACEFUL, EASY), "enable peaceful", policy(PEACEFUL)),
			new PolicyCase("disable the last enabled difficulty is refused", List.of(EASY, HARD, ELITE, PEACEFUL), "disable normal", policy(NORMAL)),
			new PolicyCase("enable all (changes something)", List.of(EASY), "enable all", ENABLE_ALL),
			new PolicyCase("enable all (nothing to change)", List.of(), "enable all", ENABLE_ALL));
		try {
			for (PolicyCase c : cases) {
				setPolicy(c.disabledAtStart());
				Capture cap = new Capture();
				Outcome out = run(server, "personaldifficulty admin " + c.command(), console(server, cap), cap);
				List<PersonalDifficultyMode> viaCommand = List.copyOf(rt().availability().enabledModes());

				setPolicy(c.disabledAtStart());
				admin.closeContainer();
				Recorder feedback = open(admin);
				click(admin, HUB_DIFFICULTIES);
				click(admin, c.menuSlot());
				List<PersonalDifficultyMode> viaMenu = List.copyOf(rt().availability().enabledModes());

				check(viaCommand.equals(viaMenu), c.name() + ": same resulting policy (command " + viaCommand + " vs menu " + viaMenu + ")");
				List<String> expected = List.of(norm(out.output()));
				List<String> actual = feedback.messages.stream().map(m -> norm(m)).toList();
				check(expected.equals(actual), c.name() + ": same message. command=" + expected + " menu=" + actual);
				boolean changed = !viaCommand.equals(List.copyOf(startPolicy(c.disabledAtStart())));
				check(feedback.broadcasts == (changed ? 1 : 0), c.name() + ": operators are told exactly when something changed (broadcasts=" + feedback.broadcasts + ", changed=" + changed + ")");
			}
		} finally {
			setPolicy(List.of());
			finish();
		}
		h.succeed();
	}

	private static List<PersonalDifficultyMode> startPolicy(List<PersonalDifficultyMode> disabled) {
		List<PersonalDifficultyMode> enabled = new ArrayList<>(List.of(PersonalDifficultyMode.values()));
		enabled.removeAll(disabled);
		return enabled;
	}

	@GameTest
	public void admin_restrictionSwitchAndKeep_matchTheirCommands_andCancelChangesNothing(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer admin = op(h, "0am_rcmp_op");
		try {
			String[][] flows = {{"switch", String.valueOf(CONFIRM_LEFT)}, {"keep", String.valueOf(CONFIRM_MIDDLE)}};
			int i = 0;
			for (String[] flow : flows) {
				String cmdName = "0am_rcmp_c" + i;
				String menuName = "0am_rcmp_m" + i++;
				ServerPlayer viaCommand = mock(h, cmdName, NORMAL);
				ServerPlayer viaMenu = mock(h, menuName, NORMAL);
				PlayerRecord start = rec(viaMenu);
				Capture cap = new Capture();
				Outcome out = run(server, "personaldifficulty admin restrict " + cmdName + " hard,elite " + flow[0], console(server, cap), cap);

				admin.closeContainer();
				Recorder feedback = open(admin);
				selectPlayer(admin, menuName);
				clickAll(admin, untickAllBut(HARD, ELITE));
				click(admin, SAVE); // NORMAL is not in the list, so the administrator is asked
				check(label(admin, CONFIRM_LEFT).startsWith("Switch"), flow[0] + ": asked");
				check(feedback.messages.isEmpty() && rec(viaMenu).sameState(start), flow[0] + ": nothing reported and nothing stored while asking: " + feedback.messages);
				click(admin, Integer.parseInt(flow[1]));

				check(rec(viaCommand).sameState(rec(viaMenu)), flow[0] + ": same resulting record (command " + rec(viaCommand) + " vs menu " + rec(viaMenu) + ")");
				check(feedback.messages.size() == 1 && feedback.broadcasts == 1, flow[0] + ": one audited, broadcast message: " + feedback.messages);
				check(norm(out.output(), cmdName, menuName).equals(norm(feedback.messages.get(0), cmdName, menuName)),
					flow[0] + ": same message. command=" + norm(out.output(), cmdName, menuName) + " menu=" + norm(feedback.messages.get(0), cmdName, menuName));
			}

			// Cancel: nothing is stored, nothing is reported; the command without a choice is refused with the same record untouched
			ServerPlayer cancelled = mock(h, "0am_rcmp_cancel", NORMAL);
			PlayerRecord start = rec(cancelled);
			admin.closeContainer();
			Recorder feedback = open(admin);
			selectPlayer(admin, "0am_rcmp_cancel");
			clickAll(admin, untickAllBut(HARD, ELITE));
			click(admin, SAVE);
			click(admin, CONFIRM_RIGHT); // Cancel
			check(rec(cancelled).sameState(start) && rec(cancelled).allowed() == null, "Cancel: the record is untouched");
			check(feedback.messages.isEmpty(), "Cancel: no change is reported: " + feedback.messages);
			ServerPlayer refused = mock(h, "0am_rcmp_nochoice", NORMAL);
			Capture cap = new Capture();
			Outcome out = run(server, "personaldifficulty admin restrict 0am_rcmp_nochoice hard,elite", console(server, cap), cap);
			check(out.result() == 0 && out.output().contains("Re-run with 'switch'") && rec(refused).allowed() == null, "the command without a choice refuses and stores nothing: " + out);
		} finally {
			finish();
		}
		h.succeed();
	}
}
