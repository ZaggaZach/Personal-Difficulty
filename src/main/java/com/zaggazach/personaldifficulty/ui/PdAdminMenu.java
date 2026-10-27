package com.zaggazach.personaldifficulty.ui;

import com.zaggazach.personaldifficulty.PdPermissions;
import com.zaggazach.personaldifficulty.admin.AdminActor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Base of every administrator menu. The permission check lives here so that no page can forget it:
 * <ul>
 * <li>{@link #stillValid} includes the live permission, so the server closes the screen of an operator who is demoted while it is
 * open (it asks every tick);</li>
 * <li>{@link #onPress} checks it again at the start of every actionable click, closing the screen and doing nothing if it fails;</li>
 * <li>every mutation goes through {@link com.zaggazach.personaldifficulty.admin.AdminService}, which checks it a third time using
 * {@link #actor}, which is built from this menu's own server-side player and never from client data.</li>
 * </ul>
 * The client only ever sends a slot index and a click type; page, selection and pending choices live on the server.
 */
public abstract class PdAdminMenu extends PdDisplayMenu {
	protected final AdminActor actor;

	protected PdAdminMenu(int containerId, ServerPlayer player, int rows, AdminActor actor) {
		super(containerId, player, rows);
		this.actor = actor;
	}

	@Override
	public final boolean stillValid(Player p) {
		return super.stillValid(p) && PdPermissions.canAdminister(player);
	}

	@Override
	protected final void onPress(int slot, ClickKind kind) {
		if (!PdPermissions.canAdminister(player)) {
			closed = true;
			player.closeContainer();
			return;
		}
		onAdminPress(slot, kind);
	}

	/** Called only for a supported click by a player who is, right now, allowed to administer. */
	protected abstract void onAdminPress(int slot, ClickKind kind);
}
