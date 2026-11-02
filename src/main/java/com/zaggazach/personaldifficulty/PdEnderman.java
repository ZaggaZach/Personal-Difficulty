package com.zaggazach.personaldifficulty;

import com.zaggazach.personaldifficulty.config.PdConfig;
import com.zaggazach.personaldifficulty.logic.EndermanBlindside;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * 1.4.0 (Phase 6, plan 6.5): Enderman Blindside. Decides whether a teleport goes behind an Elite player, finds a spot with solid
 * footing and headroom, and passes it to vanilla's own {@code Enderman.teleport(x, y, z)} (which runs vanilla's checks again).
 */
public final class PdEnderman {
	/** Endermen are 2.9 blocks tall: three free blocks above the floor. */
	private static final int HEADROOM = 3;

	private PdEnderman() {
	}

	/** The Blindside settings if {@code player} is an Elite player and Blindside is on, else null. */
	public static PdConfig.@Nullable EliteEnderman settingsFor(@Nullable Entity player) {
		PdRuntime rt = PdRuntime.get();
		if (rt == null || !(player instanceof ServerPlayer p) || !p.isAlive() || p.isSpectator()
			|| rt.modeOf(p.getUUID()) != PersonalDifficultyMode.ELITE) {
			return null;
		}
		PdConfig.EliteEnderman en = rt.config().eliteEnderman();
		return en.enabled() ? en : null;
	}

	/** One roll of the Blindside chance for this Enderman against {@code player}: the player if it hits, else null. */
	public static @Nullable ServerPlayer roll(Enderman enderman, @Nullable Entity player) {
		PdConfig.EliteEnderman en = settingsFor(player);
		if (en == null || enderman.level() != player.level() || enderman.getRandom().nextDouble() >= en.chance()) {
			return null;
		}
		return (ServerPlayer) player;
	}

	/** Tries the spots behind {@code player}; true if the Enderman teleported to one of them. */
	public static boolean teleportBehind(Enderman enderman, ServerPlayer player) {
		PdConfig.EliteEnderman en = settingsFor(player);
		if (en == null || !(enderman.level() instanceof ServerLevel level)) {
			return false;
		}
		double jitter = enderman.getRandom().nextDouble() * 2 - 1;
		for (double[] spot : EndermanBlindside.candidates(player.getX(), player.getZ(), player.getYRot(), en.distance(), jitter)) {
			int x = (int) Math.floor(spot[0]);
			int z = (int) Math.floor(spot[1]);
			Integer y = standingHeight(level, x, player.getBlockY(), z);
			if (y != null && enderman.teleport(x + 0.5, y, z + 0.5)) {
				return true;
			}
		}
		return false;
	}

	/** The lowest floor at most two blocks above and three below {@code y} with solid footing, headroom and no fluid; null if none. */
	private static @Nullable Integer standingHeight(ServerLevel level, int x, int y, int z) {
		for (int dy = 2; dy >= -3; dy--) {
			int feet = y + dy;
			BlockPos floor = new BlockPos(x, feet - 1, z);
			BlockState below = level.getBlockState(floor);
			if (below.getCollisionShape(level, floor).isEmpty() || !below.getFluidState().isEmpty()) {
				continue;
			}
			boolean free = true;
			for (int h = 0; h < HEADROOM && free; h++) {
				BlockPos p = new BlockPos(x, feet + h, z);
				BlockState s = level.getBlockState(p);
				free = s.getCollisionShape(level, p).isEmpty() && s.getFluidState().isEmpty();
			}
			if (free) {
				return feet;
			}
		}
		return null;
	}
}
