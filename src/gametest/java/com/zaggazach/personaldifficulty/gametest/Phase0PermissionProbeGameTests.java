package com.zaggazach.personaldifficulty.gametest;

import static com.zaggazach.personaldifficulty.gametest.PdTestSupport.*;

import com.zaggazach.personaldifficulty.gametest.PdTestSupport.Outcome;

import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.gametest.PdTestSupport.Capture;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.Commands;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.Permissions;

/**
 * Phase 0 probe (v1.4.0 plan): demonstrates, on real {@code ServerPlayer}s and the real player list / ops list, the permission check
 * that the GUI and every administrative mutation will use, and that it reflects /op and /deop immediately.
 * The check: {@code Commands.LEVEL_GAMEMASTERS.check(player.permissions())}.
 */
public class Phase0PermissionProbeGameTests {
	private static boolean isAdmin(ServerPlayer p) {
		return Commands.LEVEL_GAMEMASTERS.check(p.permissions());
	}

	@GameTest
	public void probe_playerPermissions_followTheOpsListLive_andMatchTheCommandGate(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ServerPlayer p = player(h, "pd_p0_perm", PersonalDifficultyMode.NORMAL);
		try {
			Capture cap = new Capture();
			check(!isAdmin(p), "a fresh player is not an administrator");
			check(!p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER), "hasPermission(COMMANDS_GAMEMASTER) agrees");
			Outcome denied = run(server, "personaldifficulty admin config", p.createCommandSourceStack().withSource(cap), cap);
			check(denied.error() != null, "the admin subtree is refused at parse time for a non-operator");

			server.getPlayerList().op(p.nameAndId(), Optional.of(LevelBasedPermissionSet.MODERATOR), Optional.empty());
			check(!isAdmin(p), "level 1 (moderator) is not enough");
			server.getPlayerList().op(p.nameAndId(), Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
			check(isAdmin(p), "level 2 (gamemaster) passes, immediately after /op");
			Outcome allowed = run(server, "personaldifficulty admin config", p.createCommandSourceStack().withSource(cap), cap);
			check(allowed.error() == null && allowed.result() > 0, "the admin command runs for a level-2 operator: " + allowed);
			server.getPlayerList().op(p.nameAndId(), Optional.of(LevelBasedPermissionSet.ADMIN), Optional.empty());
			check(isAdmin(p), "level 3 also passes (>= 2)");

			server.getPlayerList().deop(p.nameAndId());
			check(!isAdmin(p), "after /deop the same ServerPlayer instance fails the check immediately (no stale permission)");
			Outcome afterDeop = run(server, "personaldifficulty admin config", p.createCommandSourceStack().withSource(cap), cap);
			check(afterDeop.error() != null, "and the command is refused again");
		} finally {
			server.getPlayerList().deop(p.nameAndId());
		}
		h.succeed();
	}
}
