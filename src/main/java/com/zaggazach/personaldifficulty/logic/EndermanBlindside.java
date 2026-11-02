package com.zaggazach.personaldifficulty.logic;

/**
 * Phase 6 (plan 6.5): where an Enderman that is already angry at an Elite player tries to land: behind the player, opposite the
 * direction they are looking. Pure arithmetic (positions on the ground plane only); the caller checks each spot for solid footing
 * and headroom and hands the first good one to vanilla's own {@code Enderman.teleport(x, y, z)}, which validates it again.
 */
public final class EndermanBlindside {
	private EndermanBlindside() {
	}

	/**
	 * Candidate spots, best first: straight behind at {@code distance + jitter}, then 35 degrees to either side, then straight
	 * behind one block closer and one block further.
	 *
	 * @param yawDegrees the player's horizontal look direction in Minecraft's convention (0 = +z, 90 = -x)
	 * @param jitter a random offset in [-1, 1] blocks applied to the distance, so the spot is not exactly predictable
	 * @return {x, z} pairs
	 */
	public static double[][] candidates(double px, double pz, float yawDegrees, double distance, double jitter) {
		double yaw = Math.toRadians(yawDegrees);
		// Minecraft's look vector on the ground plane is (-sin yaw, cos yaw); behind is the opposite
		double bx = Math.sin(yaw);
		double bz = -Math.cos(yaw);
		double d = Math.max(1.0, distance + Math.max(-1.0, Math.min(1.0, jitter)));
		double side = Math.toRadians(35);
		return new double[][] {
			{px + bx * d, pz + bz * d},
			rotated(px, pz, bx, bz, d, side),
			rotated(px, pz, bx, bz, d, -side),
			{px + bx * Math.max(1.0, d - 1), pz + bz * Math.max(1.0, d - 1)},
			{px + bx * (d + 1), pz + bz * (d + 1)}
		};
	}

	private static double[] rotated(double px, double pz, double bx, double bz, double d, double angle) {
		double c = Math.cos(angle);
		double s = Math.sin(angle);
		return new double[] {px + (bx * c - bz * s) * d, pz + (bx * s + bz * c) * d};
	}

	/** True if a spot {@code (x, z)} is behind a player at {@code (px, pz)} looking along {@code yawDegrees} (more than 90 degrees off their view). */
	public static boolean isBehind(double px, double pz, float yawDegrees, double x, double z) {
		double yaw = Math.toRadians(yawDegrees);
		double lx = -Math.sin(yaw);
		double lz = Math.cos(yaw);
		return (x - px) * lx + (z - pz) * lz < 0;
	}
}
