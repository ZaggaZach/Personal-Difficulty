package com.zaggazach.personaldifficulty.config;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** Close-match suggestions ("did you mean ...") for mistyped keys and ids. */
public final class Closest {
	private Closest() {
	}

	/** Up to {@code limit} candidates that contain the input, or are within a small edit distance of it, best first. */
	public static List<String> of(String input, Collection<String> candidates, int limit) {
		String in = input.toLowerCase(java.util.Locale.ROOT);
		return candidates.stream()
			.map(c -> new Object[] {c, score(in, c.toLowerCase(java.util.Locale.ROOT))})
			.filter(p -> (int) p[1] <= Math.max(3, in.length() / 3))
			.sorted(Comparator.comparingInt((Object[] p) -> (int) p[1]).thenComparing(p -> (String) p[0]))
			.limit(limit)
			.map(p -> (String) p[0])
			.toList();
	}

	private static int score(String in, String candidate) {
		if (!in.isEmpty() && candidate.contains(in)) {
			return 0; // a substring match is as good as it gets
		}
		return distance(in, candidate);
	}

	static int distance(String a, String b) {
		int[] prev = new int[b.length() + 1];
		int[] cur = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) {
			prev[j] = j;
		}
		for (int i = 1; i <= a.length(); i++) {
			cur[0] = i;
			for (int j = 1; j <= b.length(); j++) {
				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
			}
			int[] t = prev;
			prev = cur;
			cur = t;
		}
		return prev[b.length()];
	}
}
