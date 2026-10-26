package com.zaggazach.personaldifficulty.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/** Styling helpers so item names and lore lines are plain-coloured and not italic (italic is the item-name default). */
public final class Text {
	private Text() {
	}

	public static MutableComponent item(String text, ChatFormatting color) {
		return Component.literal(text).withStyle(Style.EMPTY.withColor(color).withItalic(false));
	}

	public static MutableComponent item(String text, ChatFormatting color, boolean bold) {
		return Component.literal(text).withStyle(Style.EMPTY.withColor(color).withItalic(false).withBold(bold));
	}
}
