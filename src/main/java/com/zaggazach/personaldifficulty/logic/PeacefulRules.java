package com.zaggazach.personaldifficulty.logic;

import com.zaggazach.personaldifficulty.PersonalDifficultyMode;

/**
 * Minecraft-free Peaceful decisions. Peaceful behaves like creative-mode invulnerability for damage
 * (everything except the two types tagged {@code bypasses_invulnerability}: the void and /kill) and
 * additionally forbids harming other players.
 */
public final class PeacefulRules {
	private PeacefulRules() {
	}

	private static boolean peaceful(PersonalDifficultyMode mode) {
		return mode == PersonalDifficultyMode.PEACEFUL;
	}

	/**
	 * @param victimIsPlayer the damaged entity is a player
	 * @param victimMode the damaged player's mode (ignored if not a player)
	 * @param attackerMode mode of the player responsible for the damage, or null if none is attributable
	 * @param responsibleIsVictim the responsible player is the victim themself
	 * @param bypassesInvulnerability void / /kill style damage, which is never blocked
	 */
	public static boolean blocksDamage(
		boolean victimIsPlayer,
		PersonalDifficultyMode victimMode,
		PersonalDifficultyMode attackerMode,
		boolean responsibleIsVictim,
		boolean bypassesInvulnerability
	) {
		if (bypassesInvulnerability || !victimIsPlayer) {
			return false;
		}
		if (peaceful(victimMode)) {
			return true;
		}
		return peaceful(attackerMode) && !responsibleIsVictim;
	}

	/** Mobs may not target (or keep targeting) a Peaceful player. */
	public static boolean blocksTargeting(PersonalDifficultyMode targetMode) {
		return peaceful(targetMode);
	}

	/**
	 * Harmful effects are refused for a Peaceful victim from any source, and for any player victim when
	 * the responsible (attributable) player is Peaceful.
	 */
	public static boolean blocksEffect(
		boolean harmful,
		boolean victimIsPlayer,
		PersonalDifficultyMode victimMode,
		PersonalDifficultyMode attackerMode,
		boolean responsibleIsVictim
	) {
		if (!harmful || !victimIsPlayer) {
			return false;
		}
		return peaceful(victimMode) || (peaceful(attackerMode) && !responsibleIsVictim);
	}
}
