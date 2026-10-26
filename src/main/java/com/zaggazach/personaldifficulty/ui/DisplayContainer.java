package com.zaggazach.personaldifficulty.ui;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * A read-only container used for buttons. {@link #getItem} hands out copies and
 * every mutator is a no-op, so no code path - however it reaches the slot - can alter or extract what is shown.
 */
final class DisplayContainer implements Container {
	private final ItemStack[] items;

	DisplayContainer(int size) {
		items = new ItemStack[size];
		java.util.Arrays.fill(items, ItemStack.EMPTY);
	}

	/** Server-side setter used by the menu itself (not reachable from clicks). */
	void put(int slot, ItemStack stack) {
		items[slot] = stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
	}

	void clearAll() {
		java.util.Arrays.fill(items, ItemStack.EMPTY);
	}

	@Override
	public int getContainerSize() {
		return items.length;
	}

	@Override
	public boolean isEmpty() {
		for (ItemStack s : items) {
			if (!s.isEmpty()) {
				return false;
			}
		}
		return true;
	}

	@Override
	public ItemStack getItem(int slot) {
		return slot >= 0 && slot < items.length ? items[slot].copy() : ItemStack.EMPTY;
	}

	@Override
	public ItemStack removeItem(int slot, int count) {
		return ItemStack.EMPTY;
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		return ItemStack.EMPTY;
	}

	@Override
	public void setItem(int slot, ItemStack itemStack) {
		// read-only
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack itemStack) {
		return false;
	}

	@Override
	public boolean canTakeItem(Container into, int slot, ItemStack itemStack) {
		return false;
	}

	@Override
	public void setChanged() {
	}

	@Override
	public boolean stillValid(Player player) {
		return true;
	}

	@Override
	public void clearContent() {
		// read-only
	}
}
