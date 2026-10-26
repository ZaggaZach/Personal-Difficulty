package com.zaggazach.personaldifficulty.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Phase 0 smoke test: a REAL dedicated server process runs Personal Difficulty's own built JAR; a headless protocol client logs in,
 * runs a real mod command, opens the fixture menu (built on the ported UI base classes), presses a button, and sends hostile clicks.
 * Proves protocol-level behaviour only: it says nothing about how a graphical client renders the menu.
 */
@Tag("e2e")
class SmokeE2eTest {
	@Test
	void botJoinsRunsTheModCommandAndOpensAndUsesTheFixtureMenu() throws Exception {
		Path work = E2e.newServerDir("smoke");
		try (ServerRunner server = new ServerRunner(work)) {
			server.prepare(false);
			server.start();
			try (Bot alice = Bot.join("127.0.0.1", server.port(), "Alice")) {
				alice.command("personaldifficulty");
				assertTrue(alice.awaitChat("NORMAL", 10_000), "the mod's own command did not answer: " + String.join("\n", alice.chat));

				int before = alice.openCount();
				alice.command("pdfixture menu");
				assertTrue(alice.await(() -> alice.openCount() > before && alice.menuTitle().contains("PD Test Menu") && !alice.menuSlots().isEmpty(), 10_000),
					"fixture menu did not open; title=" + alice.menuTitle());
				assertEquals("minecraft:generic_9x3", alice.menuType(), "an unmodified client sees an ordinary vanilla chest menu type");
				assertEquals(27, alice.menuTopSize(), "27 container slots");

				alice.click(0);
				assertTrue(alice.awaitChat("fixture: pressed slot 0", 10_000), "button press was not handled: " + String.join("\n", alice.chat));

				// hostile clicks must not do anything (the button slot would announce a press if the engine let them through)
				int pressesBefore = (int) alice.chat.stream().filter(l -> l.contains("fixture: pressed")).count();
				alice.click(0, 0, net.minecraft.world.inventory.ContainerInput.THROW);
				alice.click(0, 0, net.minecraft.world.inventory.ContainerInput.SWAP);
				alice.click(0, 0, net.minecraft.world.inventory.ContainerInput.CLONE);
				alice.click(0, 0, net.minecraft.world.inventory.ContainerInput.PICKUP_ALL);
				alice.click(500);
				alice.click(-1);
				Thread.sleep(500);
				assertEquals(pressesBefore, alice.chat.stream().filter(l -> l.contains("fixture: pressed")).count(), "hostile click types must not reach onPress");
				assertTrue(alice.isConnected(), "the server must not drop the client over hostile clicks: " + alice.disconnectReason());

				alice.click(8);
				assertTrue(alice.await(() -> alice.menuId() == 0 && !alice.menuTitle().contains("PD Test Menu"), 10_000), "the Close button did not close the menu");
			}
			server.stop();
			assertEquals(java.util.List.of(), E2e.problemLines(server), "the server log must be clean");
		}
	}
}
