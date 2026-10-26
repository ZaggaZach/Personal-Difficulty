package com.zaggazach.personaldifficulty.ui;

import it.unimi.dsi.fastutil.objects.ReferenceSortedSets;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * Builders for the control and decoration items shown in menus. Every one carries a marker so that, as a belt-and-braces
 * measure, any that ever reached a real inventory would be swept out again by {@link #purge}.
 */
public final class UiItems {
	private static final String MARKER = "personaldifficulty_ui";

	private UiItems() {
	}

	private static ItemStack mark(ItemStack stack) {
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(MARKER, true);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return stack;
	}

	public static boolean isUi(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().contains(MARKER);
	}

	public static ItemStack button(Item item, Component name, List<Component> lore) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, name);
		if (!lore.isEmpty()) {
			stack.set(DataComponents.LORE, new ItemLore(lore));
		}
		return mark(stack);
	}

	public static ItemStack filler() {
		ItemStack stack = new ItemStack(Items.STAINED_GLASS_PANE.gray());
		stack.set(DataComponents.CUSTOM_NAME, Component.empty());
		stack.set(DataComponents.TOOLTIP_DISPLAY, new TooltipDisplay(true, ReferenceSortedSets.emptySet()));
		return mark(stack);
	}

	public static Component grey(String s) {
		return Text.item(s, ChatFormatting.GRAY);
	}

	/** Removes any UI item from the player's inventory. Returns how many stacks were removed. */
	public static int purge(Player player) {
		int removed = 0;
		var inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (isUi(inv.getItem(i))) {
				inv.setItem(i, ItemStack.EMPTY);
				removed++;
			}
		}
		return removed;
	}
}
