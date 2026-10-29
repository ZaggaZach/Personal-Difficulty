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
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.component.TooltipDisplay;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

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
		// swords and tools would otherwise list their attack damage/speed under the button's text
		stack.remove(DataComponents.ATTRIBUTE_MODIFIERS);
		return mark(stack);
	}

	/** As {@link #button} but glowing (enchantment glint) when {@code on}: how menus show "selected" or "enabled". */
	public static ItemStack button(Item item, Component name, List<Component> lore, boolean on) {
		ItemStack stack = button(item, name, lore);
		if (on) {
			stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		}
		return stack;
	}

	/**
	 * A player head showing {@code id}'s skin. An online player's full profile (with skin textures) is used when given; otherwise the
	 * profile is left for the client to resolve from the UUID. Either way only the visual is affected: clicks are routed by slot.
	 */
	public static ItemStack head(UUID id, @Nullable GameProfile onlineProfile, Component name, List<Component> lore) {
		ItemStack stack = button(Items.PLAYER_HEAD, name, lore);
		stack.set(DataComponents.PROFILE, onlineProfile != null ? ResolvableProfile.createResolved(onlineProfile) : ResolvableProfile.createUnresolved(id));
		return stack;
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
