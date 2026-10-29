package com.zaggazach.personaldifficulty.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Phase 3: the admin menus against a REAL server process with a headless protocol client. Proves what mock players cannot: the menu
 * is an ordinary chest screen on the wire, a non-operator cannot open it, a demoted operator's screen is closed by the server and
 * its stale clicks are ignored, thousands of hostile and malformed packets change nothing and do not flood the log, and what an
 * administrator changes through the menu survives a restart and a SIGKILL. Protocol-level only: no graphical client is involved.
 */
@Tag("e2e")
class AdminGuiE2eTest {
	private static final String TITLE = "Personal Difficulty: Admin";
	// the 1.3.1-rc.2 layout (see AdminMenu)
	private static final int HUB_PLAYERS = 20;
	private static final int HUB_POLICY = 24;
	private static final int LIST_PAGE_INFO = 47;
	private static final int SAVE = 35;
	private static final int LOCK = 38;
	private static final int RESET = 42;
	private static final int BACK = 45;
	private static final int ENABLE_ALL = 31;
	// Set difficulty row 20..24 and Allowed row 29..33, both PEACEFUL, EASY, NORMAL, HARD, ELITE
	private static final int SET_HARD = 23;
	private static final int SET_ELITE = 24;
	private static final int[] ALLOW_ALL_BUT_HARD = {29, 30, 31, 33};

	private static Path playersFile(ServerRunner server) {
		return server.world().resolve("personaldifficulty").resolve("players.json");
	}

	private static String players(ServerRunner server) throws IOException {
		Path f = playersFile(server);
		return Files.exists(f) ? Files.readString(f) : "";
	}

	private static String availability(ServerRunner server) throws IOException {
		Path f = server.world().resolve("personaldifficulty").resolve("personaldifficulty-availability.json");
		Path g = server.dir().resolve("config").resolve("personaldifficulty-availability.json");
		return (Files.exists(f) ? Files.readString(f) : "") + (Files.exists(g) ? Files.readString(g) : "");
	}

	private static void op(ServerRunner server, String name) throws Exception {
		int mark = server.logSize();
		server.console("op " + name);
		assertTrue(server.awaitLogAfter("Made " + name + " a server operator", mark, 3_000) || server.awaitLogAfter("already an operator", mark, 7_000), "op " + name + "\n" + server.tail(10));
	}

	private static void deop(ServerRunner server, String name) throws Exception {
		int mark = server.logSize();
		server.console("deop " + name);
		assertTrue(server.awaitLogAfter("no longer a server operator", mark, 10_000), "deop " + name + "\n" + server.tail(10));
	}

	private static void awaitSlot(Bot bot, int slot, String contains) {
		assertTrue(bot.await(() -> {
			String n = bot.slotName(slot);
			return n != null && n.contains(contains);
		}, 10_000), "slot " + slot + " should contain '" + contains + "' but is '" + bot.slotName(slot) + "'");
	}

	private static void press(Bot bot, int slot) {
		bot.click(slot);
	}

	private static void openGui(Bot bot) {
		int before = bot.openCount();
		bot.command("personaldifficulty admin gui");
		assertTrue(bot.await(() -> bot.openCount() > before && bot.menuTitle().contains(TITLE) && !bot.menuSlots().isEmpty(), 10_000),
			"the admin menu did not open; title='" + bot.menuTitle() + "' chat=" + bot.chat);
	}

	/** Hub, then the player list, until the named player is listed; selects them and waits for the player screen. */
	private static void selectPlayer(Bot bot, String name) throws Exception {
		awaitSlot(bot, HUB_PLAYERS, "Players");
		press(bot, HUB_PLAYERS);
		awaitSlot(bot, LIST_PAGE_INFO, "Page");
		for (int page = 0; page < 20; page++) {
			int slot = bot.findSlotByName(name);
			if (slot >= 0 && slot < 45) {
				press(bot, slot);
				awaitSlot(bot, 4, name);
				awaitSlot(bot, SAVE, "Save allowed list");
				return;
			}
			press(bot, 53);
			Thread.sleep(150);
		}
		throw new AssertionError(name + " was not listed");
	}

	private static JsonObject record(ServerRunner server, UUID id) throws IOException {
		JsonObject root = JsonParser.parseString(players(server)).getAsJsonObject();
		return root.getAsJsonObject("players").has(id.toString()) ? root.getAsJsonObject("players").getAsJsonObject(id.toString()) : null;
	}

	// ------------------------------------------------------------------------------------------- permissions, demotion, persistence

	@Test
	void aNonOperatorCannotOpenIt_anOperatorCan_aDemotedOperatorLosesItAtOnce_andMenuChangesSurviveRestartAndKill() throws Exception {
		Path work = E2e.newServerDir("gui-main");
		try (ServerRunner server = new ServerRunner(work)) {
			server.prepare(false);
			server.start();
			UUID aliceId;
			try (Bot alice = Bot.join("127.0.0.1", server.port(), "Alice")) {
				aliceId = alice.uuid();
				// a non-operator: the admin subtree is refused when the command is parsed, so no screen can open
				int opened = alice.openCount();
				alice.command("personaldifficulty admin gui");
				Thread.sleep(800);
				assertEquals(opened, alice.openCount(), "no menu for a non-operator");
				assertEquals(0, alice.menuId(), "no container is open");

				op(server, "Alice");
				openGui(alice);
				assertEquals("minecraft:generic_9x6", alice.menuType(), "an unmodified client sees an ordinary chest screen");
				assertEquals(54, alice.menuTopSize());
				awaitSlot(alice, HUB_PLAYERS, "Players");
				awaitSlot(alice, HUB_POLICY, "Server Difficulties");
				awaitSlot(alice, 49, "Close");

				// Player Management: assign, lock; exactly one audit line per change
				selectPlayer(alice, "Alice");
				int mark = server.logSize();
				press(alice, SET_HARD);
				assertTrue(alice.awaitChat("Alice is now HARD", 10_000), "feedback: " + alice.chat);
				assertTrue(server.awaitLogAfter("Alice is now HARD", mark, 10_000), "the operator broadcast / console audit line\n" + server.tail(10));
				Thread.sleep(300);
				long auditLines = server.log.stream().skip(mark).filter(l -> l.contains("Alice is now HARD")).count();
				assertEquals(1, auditLines, "one audit line, not two: " + server.log.stream().skip(mark).filter(l -> l.contains("Alice is now HARD")).toList());
				press(alice, LOCK);
				assertTrue(alice.awaitChat("Alice is now locked on HARD", 10_000), "lock feedback: " + alice.chat);
				awaitSlot(alice, LOCK, "Lock: ON");
				JsonObject rec = record(server, aliceId);
				assertNotNull(rec, players(server));
				assertEquals("HARD", rec.get("mode").getAsString());
				assertTrue(rec.get("locked").getAsBoolean());
				assertEquals(aliceId.toString(), rec.get("by").getAsString(), "the administrator is the menu's own player");

				// demotion while the screen is open: the server closes it, and a stale click for the old container id is ignored
				int staleId = alice.menuId();
				String before = players(server);
				deop(server, "Alice");
				assertTrue(alice.await(() -> alice.menuId() == 0, 10_000), "the server closed the demoted operator's screen");
				alice.clickIn(staleId, SET_ELITE, 0, ContainerInput.PICKUP); // assign ELITE for the closed menu
				alice.clickIn(staleId, LOCK, 0, ContainerInput.PICKUP);
				Thread.sleep(500);
				assertEquals(before, players(server), "stale clicks changed nothing");
				alice.command("personaldifficulty admin gui");
				Thread.sleep(500);
				assertEquals(0, alice.menuId(), "and the menu cannot be reopened");
				assertTrue(alice.isConnected(), "the connection stays up: " + alice.disconnectReason());
			}
			server.stop();
			assertEquals(List.of(), E2e.problemLines(server), "the server log is clean");

			// graceful restart: the change made through the menu is there
			server.start();
			admin(server, "status Alice", "Alice (" + aliceId + "): HARD");
			admin(server, "unlock Alice", "Alice is unlocked");
			// a second change through the menu, then SIGKILL right after the answer
			try (Bot alice = Bot.join("127.0.0.1", server.port(), "Alice")) {
				op(server, "Alice");
				openGui(alice);
				selectPlayer(alice, "Alice");
				press(alice, SET_ELITE);
				assertTrue(alice.awaitChat("Alice is now ELITE", 10_000), alice.chat.toString());
				press(alice, LOCK);
				assertTrue(alice.awaitChat("Alice is now locked on ELITE", 10_000), alice.chat.toString());
				// abrupt disconnect with the menu open, then kill -9
				alice.drop();
			}
			server.kill();
			server.start();
			admin(server, "status Alice", "Alice (" + aliceId + "): ELITE");
			admin(server, "status Alice", "locked: YES");
			server.stop();
			assertEquals(List.of(), E2e.problemLines(server), "the server log is clean after a dropped client and a kill");
		}
	}

	private static void admin(ServerRunner server, String command, String expected) throws Exception {
		int mark = server.logSize();
		server.console("personaldifficulty admin " + command);
		assertTrue(server.awaitLogAfter(expected, mark, 15_000), "no answer containing '" + expected + "' to: " + command + "\n" + server.tail(15));
	}

	// ------------------------------------------------------------------------------------------- hostile traffic

	@Test
	void thousandsOfHostileAndMalformedPacketsPerScreenChangeNothing_andDoNotFloodTheLog() throws Exception {
		Path work = E2e.newServerDir("gui-hostile");
		try (ServerRunner server = new ServerRunner(work)) {
			server.prepare(false);
			server.start();
			try (Bot alice = Bot.join("127.0.0.1", server.port(), "Alice"); Bot bob = Bot.join("127.0.0.1", server.port(), "Bob")) {
				op(server, "Alice");
				openGui(alice);
				int[] buttons = {-1, 0, 1, 2, 3, 8, 40, 127};
				ContainerInput[] inputs = ContainerInput.values();
				long sent = 0;
				for (int screen = 0; screen < 6; screen++) {
					switch (screen) {
						case 1 -> {
							press(alice, HUB_PLAYERS);
							awaitSlot(alice, LIST_PAGE_INFO, "Page");
						}
						case 2 -> {
							int slot = alice.findSlotByName("Bob");
							assertTrue(slot >= 0 && slot < 45, "Bob is listed");
							press(alice, slot);
							awaitSlot(alice, SAVE, "Save allowed list");
						}
						case 3 -> {
							for (int s : ALLOW_ALL_BUT_HARD) {
								press(alice, s); // untick, leaving HARD only
							}
							awaitSlot(alice, SAVE, "Save allowed list");
							press(alice, SAVE); // NORMAL is not listed: the Switch / Keep / Cancel confirmation
							awaitSlot(alice, 20, "Switch");
						}
						case 4 -> {
							press(alice, 24); // Cancel, back to the player page
							awaitSlot(alice, SAVE, "Save allowed list");
							press(alice, RESET); // the reset confirmation
							awaitSlot(alice, 20, "Yes, reset");
						}
						case 5 -> {
							press(alice, 24); // Cancel, back to the player page
							awaitSlot(alice, SAVE, "Save allowed list");
							press(alice, BACK); // list
							awaitSlot(alice, LIST_PAGE_INFO, "Page");
							press(alice, BACK); // hub
							awaitSlot(alice, HUB_POLICY, "Server Difficulties");
							press(alice, HUB_POLICY);
							awaitSlot(alice, ENABLE_ALL, "Turn all on");
						}
						default -> {
						}
					}
					Thread.sleep(300);
					if (screen == 1) {
						// plain presses on EMPTY list slots (only Alice and Bob are listed): a bounds error here would be caught and logged, never visible as a state change
						for (int slot = 2; slot < 45; slot++) {
							alice.click(slot, 0, ContainerInput.PICKUP);
							alice.click(slot, 1, ContainerInput.PICKUP);
						}
						Thread.sleep(500);
						awaitSlot(alice, LIST_PAGE_INFO, "Page");
					}
					String playersBefore = players(server);
					String availBefore = availability(server);
					int logBefore = server.logSize();
					int container = alice.menuId();
					// 1) every non-plain input on every slot (and the in-menu inventory slots) with odd buttons: well over 2,000 packets per screen
					for (ContainerInput input : inputs) {
						for (int slot = -3; slot < 64; slot++) {
							for (int button : buttons) {
								if (input == ContainerInput.PICKUP && (button == 0 || button == 1) && slot >= 0 && slot < 54) {
									continue; // a legitimate press, tested elsewhere
								}
								alice.click(slot, button, input);
								sent++;
							}
						}
					}
					// 2) stale / foreign / nonsense container ids with plain presses
					for (int id : new int[] {0, -1, container + 1, container + 2, container + 100, 127, 255, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
						for (int slot = 0; slot < 54; slot++) {
							alice.clickIn(id, slot, 0, ContainerInput.PICKUP);
							alice.clickIn(id, slot, 1, ContainerInput.PICKUP);
							sent += 2;
						}
					}
					// 3) malformed click packets: bogus changed-slot maps, impossible hashes, absurd slot numbers
					Int2ObjectOpenHashMap<HashedStack> junk = new Int2ObjectOpenHashMap<>();
					for (int i = -10; i < 100; i++) {
						junk.put(i * 37, HashedStack.EMPTY);
					}
					for (ContainerInput input : inputs) {
						for (short slot : new short[] {-1, 0, 20, 53, 54, 100, Short.MAX_VALUE, Short.MIN_VALUE}) {
							if (input == ContainerInput.PICKUP && slot >= 0 && slot < 54) {
								continue; // a plain left press on a real slot is a legitimate press, whatever junk rides along
							}
							alice.sendRaw(new ServerboundContainerClickPacket(container, alice.stateId(), slot, (byte) 0, input, junk, HashedStack.EMPTY));
							alice.sendRaw(new ServerboundContainerClickPacket(container, Integer.MAX_VALUE, slot, (byte) -128, input, new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY));
							sent += 2;
						}
					}
					// 4) menu-button and creative-slot packets (the enchanting-table and creative-inventory channels)
					for (int id = -2; id < 120; id++) {
						alice.sendRaw(new ServerboundContainerButtonClickPacket(container, id));
						sent++;
					}
					for (int slot : new int[] {-1, 0, 5, 9, 36, 44, 45, 100, 1000}) {
						alice.sendRaw(new ServerboundSetCreativeModeSlotPacket(slot, new ItemStack(Items.DIAMOND, 64)));
						sent++;
					}
					Thread.sleep(1500);
					assertTrue(alice.isConnected(), "screen " + screen + ": the server did not drop the client: " + alice.disconnectReason());
					assertEquals(playersBefore, players(server), "screen " + screen + ": players.json unchanged");
					assertEquals(availBefore, availability(server), "screen " + screen + ": the availability policy unchanged");
					assertTrue(server.logSize() - logBefore < 40, "screen " + screen + ": no log flood (" + (server.logSize() - logBefore) + " new lines): " + server.log.subList(logBefore, Math.min(server.logSize(), logBefore + 8)));
					assertEquals(0, alice.count(Items.DIAMOND), "no creative item was accepted from a survival player");
					assertTrue(alice.menuId() == container, "screen " + screen + ": the menu is still the same open container");
				}
				assertTrue(sent > 12_000, "at least two thousand hostile packets per screen were sent: " + sent);
				// the menu still works normally after all of that
				press(alice, ENABLE_ALL);
				assertTrue(alice.awaitChat("All difficulties are enabled again", 10_000) || alice.awaitChat("already enabled", 10_000), "the menu is still functional: " + alice.chat);
				// and Bob, an unrelated non-operator, was never affected
				bob.command("personaldifficulty");
				assertTrue(bob.awaitChat("NORMAL", 10_000), bob.chat.toString());
			}
			server.stop();
			assertEquals(List.of(), E2e.problemLines(server), "no ERROR or mod warning after the whole storm");
		}
	}

	// ------------------------------------------------------------------------------------------- offline players and the user cache

	@Test
	void offlinePlayersFromTheSettingsAndTheUserCacheAreListed_aBadUserCacheIsHarmless_andViewingCreatesNoRecord() throws Exception {
		Path work = E2e.newServerDir("gui-offline");
		try (ServerRunner server = new ServerRunner(work)) {
			server.prepare(false);
			UUID stored = UUID.fromString("11111111-1111-1111-1111-111111111111");
			UUID cached = UUID.fromString("22222222-2222-2222-2222-222222222222");
			Files.createDirectories(playersFile(server).getParent());
			Files.writeString(playersFile(server), "{\"version\":2,\"players\":{\"" + stored + "\":{\"mode\":\"ELITE\"}}}");
			Files.writeString(server.dir().resolve("usercache.json"), "[{\"name\":\"Zed\",\"uuid\":\"" + cached + "\",\"expiresOn\":\"2099-01-01 00:00:00 +0000\"},{\"name\":\"bad\",\"uuid\":\"nope\"},7]");
			server.start();
			try (Bot alice = Bot.join("127.0.0.1", server.port(), "Alice")) {
				op(server, "Alice");
				openGui(alice);
				awaitSlot(alice, HUB_PLAYERS, "Players");
				press(alice, HUB_PLAYERS);
				awaitSlot(alice, LIST_PAGE_INFO, "Page");
				assertTrue(alice.await(() -> alice.findSlotByName("Zed") >= 0 && alice.findSlotByName(stored.toString()) >= 0 && alice.findSlotByName("Alice") >= 0, 10_000),
					"cache-only, settings-only and online players are all listed");
				String before = players(server);
				press(alice, alice.findSlotByName("Zed"));
				awaitSlot(alice, SAVE, "Save allowed list");
				press(alice, BACK);
				awaitSlot(alice, LIST_PAGE_INFO, "Page");
				press(alice, alice.findSlotByName(stored.toString()));
				awaitSlot(alice, SAVE, "Save allowed list");
				assertEquals(before, players(server), "browsing players changes nothing and creates no record");
				assertNull(record(server, cached), "no record for a player who was only viewed");
			}
			server.stop();
			assertEquals(List.of(), E2e.problemLines(server));

			// now with a user cache that is not JSON at all, and then none: the list still works
			for (String garbage : new String[] {"{ not json", null}) {
				Path cache = server.dir().resolve("usercache.json");
				if (garbage == null) {
					Files.deleteIfExists(cache);
				} else {
					Files.writeString(cache, garbage);
				}
				server.start();
				try (Bot alice = Bot.join("127.0.0.1", server.port(), "Alice")) {
					op(server, "Alice");
					openGui(alice);
					press(alice, HUB_PLAYERS);
					awaitSlot(alice, LIST_PAGE_INFO, "Page");
					assertTrue(alice.await(() -> alice.findSlotByName(stored.toString()) >= 0, 10_000), "settings-only players are still listed");
				}
				server.stop();
				assertEquals(List.of(), E2e.problemLines(server), "garbage=" + garbage);
			}
		}
	}

	@Test
	void anInvalidStoredRecordIsShownAsInvalid_andOnlyTheExplicitRepairChangesItAfterABackup() throws Exception {
		Path work = E2e.newServerDir("gui-quarantine");
		try (ServerRunner server = new ServerRunner(work)) {
			server.prepare(false);
			UUID victim = UUID.fromString("33333333-3333-3333-3333-333333333333");
			Files.createDirectories(playersFile(server).getParent());
			String raw = "{\"version\":2,\"players\":{\"" + victim + "\":{\"mode\":\"ELITE\",\"locked\":\"yes\",\"nickname\":\"zed\"}}}";
			Files.writeString(playersFile(server), raw);
			server.start();
			try (Bot alice = Bot.join("127.0.0.1", server.port(), "Alice")) {
				op(server, "Alice");
				openGui(alice);
				press(alice, HUB_PLAYERS);
				awaitSlot(alice, LIST_PAGE_INFO, "Page");
				assertTrue(alice.await(() -> alice.findSlotByName(victim.toString()) >= 0, 10_000));
				press(alice, alice.findSlotByName(victim.toString()));
				awaitSlot(alice, 22, "Repair invalid record");
				assertTrue(alice.slotName(SAVE) == null || alice.slotName(SAVE).isEmpty(), "no ordinary action is offered: " + alice.slotName(SAVE));
				for (int slot : new int[] {18, 20, 21, 23, 24, 27, 29, 30, 31, 32, 33, 35, 38, 40, 42}) {
					press(alice, slot);
				}
				Thread.sleep(500);
				assertEquals(raw, players(server), "nothing but the explicit repair can touch it");
				press(alice, 22);
				assertTrue(alice.awaitChat("the invalid stored record was replaced", 10_000), alice.chat.toString());
				try (var files = Files.list(playersFile(server).getParent())) {
					assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("players.json.backup-")), "a backup was made");
				}
				assertNotEquals(raw, players(server));
				assertTrue(players(server).contains("\"nickname\":\"zed\""), "unknown fields kept: " + players(server));
			}
			server.stop();
			List<String> problems = E2e.problemLines(server);
			assertEquals(1, problems.size(), "only the expected startup report of the invalid record (Phase 1 behaviour): " + problems);
			assertTrue(problems.get(0).contains("have an invalid stored record"), problems.get(0));
		}
	}
}
