package com.zaggazach.personaldifficulty;

import static org.junit.jupiter.api.Assertions.*;

import com.zaggazach.personaldifficulty.ui.ClickKind;
import net.minecraft.world.inventory.ContainerInput;
import org.junit.jupiter.api.Test;

/** Every raw (input, button) pair the protocol can carry maps to exactly one kind, and only the two plain presses are plain. */
class ClickKindTest {
	@Test
	void onlyPlainLeftAndRightPressesArePlainPresses() {
		assertEquals(ClickKind.PRESS_LEFT, ClickKind.of(ContainerInput.PICKUP, 0));
		assertEquals(ClickKind.PRESS_RIGHT, ClickKind.of(ContainerInput.PICKUP, 1));
		for (int button : new int[] {-1, 2, 3, 8, 40, 127, 255, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
			assertEquals(ClickKind.UNKNOWN, ClickKind.of(ContainerInput.PICKUP, button), "pickup with button " + button);
		}
		for (ContainerInput input : ContainerInput.values()) {
			for (int button = -2; button < 300; button++) {
				ClickKind kind = ClickKind.of(input, button);
				assertNotNull(kind);
				assertEquals(input == ContainerInput.PICKUP && (button == 0 || button == 1), kind.isPlainPress(), input + "/" + button);
			}
		}
	}

	@Test
	void everyOtherInputKindIsRecognisedSoItCanBeRejectedExplicitly() {
		assertEquals(ClickKind.SHIFT_CLICK, ClickKind.of(ContainerInput.QUICK_MOVE, 0));
		assertEquals(ClickKind.SHIFT_CLICK, ClickKind.of(ContainerInput.QUICK_MOVE, 1));
		assertEquals(ClickKind.NUMBER_KEY_SWAP, ClickKind.of(ContainerInput.SWAP, 0));
		assertEquals(ClickKind.NUMBER_KEY_SWAP, ClickKind.of(ContainerInput.SWAP, 40));
		assertEquals(ClickKind.DROP, ClickKind.of(ContainerInput.THROW, 0));
		assertEquals(ClickKind.DRAG, ClickKind.of(ContainerInput.QUICK_CRAFT, 0));
		assertEquals(ClickKind.DOUBLE_CLICK, ClickKind.of(ContainerInput.PICKUP_ALL, 0));
		assertEquals(ClickKind.CREATIVE_CLONE, ClickKind.of(ContainerInput.CLONE, 2));
		assertEquals(ClickKind.UNKNOWN, ClickKind.of(null, 0));
	}
}
