package com.zaggazach.personaldifficulty.admin;

/**
 * The outcome of one administrative action on one target. The message has already been delivered through the actor's feedback; it is
 * returned only so a caller (for example a menu) can show the same text.
 */
public record AdminResult(Outcome outcome, String message) {
	public enum Outcome {
		/** The change was stored (and the mode-change hook ran for an online player). */
		APPLIED,
		/** Nothing needed to change; this is a normal answer, not a failure. */
		UNCHANGED,
		/** Refused or failed; nothing changed. */
		REFUSED,
		/** A restriction would exclude the player's current mode and the administrator must choose switch or keep. */
		NEEDS_CHOICE,
		/** The actor no longer has the authority to administer; nothing changed. */
		UNAUTHORISED
	}

	public boolean applied() {
		return outcome == Outcome.APPLIED;
	}
}
