package com.zaggazach.personaldifficulty.ui;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuConstructor;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * Base of all Personal Difficulty menus. They are server-side {@link AbstractContainerMenu}s that reuse the vanilla generic
 * chest {@link MenuType}s, so an unmodified client renders them as ordinary chest screens and nothing needs to be installed
 * on the client.
 */
public abstract class PdMenu extends AbstractContainerMenu {
	protected final ServerPlayer player;
	protected final int rows;
	protected boolean closed;

	protected PdMenu(int containerId, ServerPlayer player, int rows) {
		super(typeFor(rows), containerId);
		this.player = player;
		this.rows = rows;
	}

	private static MenuType<?> typeFor(int rows) {
		return switch (rows) {
			case 1 -> MenuType.GENERIC_9x1;
			case 2 -> MenuType.GENERIC_9x2;
			case 3 -> MenuType.GENERIC_9x3;
			case 4 -> MenuType.GENERIC_9x4;
			case 5 -> MenuType.GENERIC_9x5;
			case 6 -> MenuType.GENERIC_9x6;
			default -> throw new IllegalArgumentException("rows " + rows);
		};
	}

	protected final int containerSlots() {
		return rows * 9;
	}

	protected final int inventoryTop() {
		return 18 + rows * 18 + 13;
	}

	public static void open(ServerPlayer player, Component title, MenuConstructor constructor) {
		player.openMenu(new SimpleMenuProvider(constructor, title));
	}

	@Override
	public boolean stillValid(Player p) {
		return !closed && p == player && !player.hasDisconnected();
	}

	@Override
	public ItemStack quickMoveStack(Player p, int slotIndex) {
		return ItemStack.EMPTY;
	}

	@Override
	public void removed(Player p) {
		closed = true;
		super.removed(p);
		UiItems.purge(p);
	}
}
