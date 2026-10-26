package com.zaggazach.personaldifficulty.config;

/** The outcome of an {@code admin set}/{@code unset}: what happened, and the text to show the administrator. */
public record SetResult(Kind kind, String message) {
	public enum Kind {
		/** The overrides file was written and the new configuration is in force. */
		APPLIED,
		/** Nothing needed to change (already that value, or no override to remove). Nothing was written. */
		UNCHANGED,
		/** The request was invalid or could not be saved. Nothing changed. */
		REFUSED
	}

	public boolean ok() {
		return kind != Kind.REFUSED;
	}
}
