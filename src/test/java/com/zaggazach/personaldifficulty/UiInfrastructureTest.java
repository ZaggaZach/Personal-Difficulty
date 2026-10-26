package com.zaggazach.personaldifficulty;

import static org.junit.jupiter.api.Assertions.*;

import com.zaggazach.personaldifficulty.ui.Text;
import com.zaggazach.personaldifficulty.ui.UiItems;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The ported UI helpers that can be tested without a server (the menus themselves are exercised by the e2e smoke test). */
class UiInfrastructureTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		// the step the server performs after loading data: binds each item's default components, so real ItemStacks work
		HolderLookup.Provider lookup = VanillaRegistries.createWorldLookup();
		BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(lookup).forEach(p -> p.apply());
	}

	@Test
	void uiItemsAreMarkedAndOrdinaryItemsAreNot() {
		ItemStack button = UiItems.button(Items.EMERALD, Text.item("Press", ChatFormatting.GREEN), List.of(UiItems.grey("lore")));
		assertTrue(UiItems.isUi(button));
		assertTrue(UiItems.isUi(UiItems.filler()));
		assertFalse(UiItems.isUi(new ItemStack(Items.DIAMOND)));
		assertFalse(UiItems.isUi(ItemStack.EMPTY));
	}

	@Test
	void markerIsSpecificToPersonalDifficulty() {
		assertTrue(UiItems.button(Items.PAPER, Text.item("x", ChatFormatting.WHITE), List.of()).get(net.minecraft.core.component.DataComponents.CUSTOM_DATA)
			.copyTag().contains("personaldifficulty_ui"));
	}

	@Test
	void itemTextIsNeverItalic() {
		assertFalse(Text.item("x", ChatFormatting.RED).getStyle().isItalic());
		assertTrue(Text.item("x", ChatFormatting.RED, true).getStyle().isBold());
	}
}
