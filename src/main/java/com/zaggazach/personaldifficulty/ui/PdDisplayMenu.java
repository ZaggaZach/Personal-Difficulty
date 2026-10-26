package com.zaggazach.personaldifficulty.ui;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

/**
 * A menu whose slots are pure buttons and displays. The vanilla click engine is bypassed completely: a click is either a plain
 * left or right press on a container slot (forwarded to {@link #onPress}) or it is ignored. Shift-click, drag, number keys,
 * offhand swap, double-click, drop, creative clone and out-of-range slots therefore have no effect at all, and the vanilla packet
 * handler re-syncs whatever the client predicted.
 *
 * <p>Subclasses must re-validate permissions inside {@link #onPress} (hiding a button is not a security measure).
 */
public abstract class PdDisplayMenu extends PdMenu {
	private static final Logger LOGGER = LogUtils.getLogger();

	private final DisplayContainer display;

	protected PdDisplayMenu(int containerId, ServerPlayer player, int rows) {
		super(containerId, player, rows);
		this.display = new DisplayContainer(rows * 9);
		for (int i = 0; i < rows * 9; i++) {
			addSlot(new LockedSlot(display, i, 8 + (i % 9) * 18, 18 + (i / 9) * 18));
		}
		// the player's own inventory is shown (as in a chest screen) but locked
		for (int y = 0; y < 3; y++) {
			for (int x = 0; x < 9; x++) {
				addSlot(new LockedSlot(player.getInventory(), x + (y + 1) * 9, 8 + x * 18, inventoryTop() + y * 18));
			}
		}
		for (int x = 0; x < 9; x++) {
			addSlot(new LockedSlot(player.getInventory(), x, 8 + x * 18, inventoryTop() + 58));
		}
	}

	/** Shows {@code stack} (copied) in a container slot. Server-side only; not reachable from clicks. */
	protected final void show(int slot, ItemStack stack) {
		display.put(slot, stack);
	}

	protected final void clearDisplay() {
		display.clearAll();
	}

	protected abstract void onPress(int slot);

	@Override
	public void clicked(int slotIndex, int buttonNum, ContainerInput containerInput, Player p) {
		try {
			boolean press = (containerInput == ContainerInput.PICKUP || containerInput == ContainerInput.QUICK_MOVE)
				&& (buttonNum == 0 || buttonNum == 1)
				&& slotIndex >= 0 && slotIndex < containerSlots();
			if (press && stillValid(p)) {
				onPress(slotIndex);
			}
		} catch (RuntimeException e) {
			LOGGER.error("[PersonalDifficulty] Error handling a click in {} for {}", getClass().getSimpleName(), player.getGameProfile().name(), e);
		}
	}
}
