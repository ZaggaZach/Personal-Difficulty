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
