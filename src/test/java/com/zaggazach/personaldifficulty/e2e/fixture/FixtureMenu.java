package com.zaggazach.personaldifficulty.e2e.fixture;

import com.zaggazach.personaldifficulty.ui.PdDisplayMenu;
import com.zaggazach.personaldifficulty.ui.Text;
import com.zaggazach.personaldifficulty.ui.UiItems;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

/** One-row-of-buttons test menu: slot 0 reports presses in chat, slot 8 closes. All other container slots are filler. */
final class FixtureMenu extends PdDisplayMenu {
	static final String TITLE = "PD Test Menu";

	FixtureMenu(int containerId, ServerPlayer player) {
		super(containerId, player, 3);
		for (int i = 0; i < 27; i++) {
			show(i, UiItems.filler());
		}
		show(0, UiItems.button(Items.EMERALD, Text.item("Press me", ChatFormatting.GREEN), List.of(UiItems.grey("Reports the press in chat."))));
		show(8, UiItems.button(Items.BARRIER, Text.item("Close", ChatFormatting.RED), List.of()));
	}

	static void open(ServerPlayer player) {
		openMenu(player);
	}

	private static void openMenu(ServerPlayer player) {
		com.zaggazach.personaldifficulty.ui.PdMenu.open(player, Component.literal(TITLE), (id, inv, p) -> new FixtureMenu(id, player));
	}

	@Override
	protected void onPress(int slot, com.zaggazach.personaldifficulty.ui.ClickKind kind) {
		if (slot == 0) {
			player.sendSystemMessage(Component.literal("fixture: pressed slot 0"));
		} else if (slot == 8) {
			player.closeContainer();
		}
	}
}
