package com.zaggazach.personaldifficulty.ui;

import net.minecraft.world.inventory.ContainerInput;

/**
 * What a container click packet actually asks for, decided once from the raw (input, button) pair. Menus declare which kinds they
 * support; everything else is rejected before any menu code runs. Phase 3 supports only the two plain presses; shift-click, number
 * keys, drops, drags, double-clicks and creative clones are recognised so they can be rejected explicitly (and so a later phase can
 * opt in to one of them without touching the router).
 */
public enum ClickKind {
	PRESS_LEFT,
	PRESS_RIGHT,
	SHIFT_CLICK,
	NUMBER_KEY_SWAP,
	DROP,
	DRAG,
	DOUBLE_CLICK,
	CREATIVE_CLONE,
	UNKNOWN;

	public static ClickKind of(ContainerInput input, int button) {
		if (input == null) {
			return UNKNOWN;
		}
		return switch (input) {
			case PICKUP -> button == 0 ? PRESS_LEFT : button == 1 ? PRESS_RIGHT : UNKNOWN;
			case QUICK_MOVE -> SHIFT_CLICK;
			case SWAP -> NUMBER_KEY_SWAP;
			case THROW -> DROP;
			case QUICK_CRAFT -> DRAG;
			case PICKUP_ALL -> DOUBLE_CLICK;
			case CLONE -> CREATIVE_CLONE;
		};
	}

	public boolean isPlainPress() {
		return this == PRESS_LEFT || this == PRESS_RIGHT;
	}
}
