package com.zaggazach.personaldifficulty;

import java.util.function.Predicate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;

/**
 * The single authoritative "may administer Personal Difficulty" check: gamemaster (permission level 2) or above.
 * Everything that gates an administrative action (the command tree's {@code requires}, and later the admin GUI)
 * goes through here, so the rule cannot drift between entry points.
 *
 * <p>Permissions are live: {@code ServerPlayer.permissions()} reads the server's ops list at the moment of the call,
 * so /op and /deop take effect at once. Console and command blocks carry their own permission sets.
 */
public final class PdPermissions {
	/**
	 * Permission-node ids RESERVED for a later release (plan section 5, C4). They are constants only: nothing looks them up and the
	 * single rule remains "gamemaster (level 2) or above". Reserving the exact strings now keeps them stable for server owners who
	 * will want to grant them with a permissions mod later.
	 */
	public static final String NODE_ADMIN_GUI = "personaldifficulty.admin.gui";
	public static final String NODE_ADMIN_ASSIGN = "personaldifficulty.admin.assign";
	public static final String NODE_ADMIN_LOCK = "personaldifficulty.admin.lock";
	public static final String NODE_ADMIN_CONFIG = "personaldifficulty.admin.config";

	private PdPermissions() {
	}

	public static boolean canAdminister(PermissionSet permissions) {
		return Commands.LEVEL_GAMEMASTERS.check(permissions);
	}

	public static boolean canAdminister(CommandSourceStack source) {
		return canAdminister(source.permissions());
	}

	public static boolean canAdminister(ServerPlayer player) {
		return canAdminister(player.permissions());
	}

	/** The Brigadier {@code requires} predicate for every administrative node. */
	public static Predicate<CommandSourceStack> adminRequirement() {
		return PdPermissions::canAdminister;
	}
}
