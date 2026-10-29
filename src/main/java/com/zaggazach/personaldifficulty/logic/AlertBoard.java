package com.zaggazach.personaldifficulty.logic;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Where and when hostile mobs last SAW each Elite player, and which mobs have heard about it (1.4.0, Phase 5). One board per level;
 * no Minecraft types, so every bound is unit-tested.
 *
 * <p>What it stores, and what it never stores:
 * <ul>
 * <li>a sighting is written only by a mob that has line of sight to the player, and holds the player's position at that moment
 *     and the game tick: it goes stale as soon as the player moves, and nobody is ever told where the player is now;</li>
 * <li>"relay nodes" are the positions of mobs that know of a sighting, each with its hop count (0 = the mob that saw it). A mob
 *     learns of a sighting if it is within {@code radius} of a node whose hop count is below {@code maxHops}; it then becomes a node
 *     itself, one hop further. A sighting is therefore passed on at most {@code maxHops} times, and never across levels;</li>
 * <li>a sighting expires {@code ttlTicks} after it was made; a newer sighting of the same player replaces the point and keeps
 *     the relay chain (those mobs still "know" the player is about).</li>
 * </ul>
 * Work is bounded: at most {@link #MAX_SIGHTINGS} players per level and {@link #MAX_NODES} nodes per sighting (when full, the
 * oldest relay node is dropped, never the direct sighting), and a lookup only does distance checks.
 */
public final class AlertBoard {
	public static final int MAX_SIGHTINGS = 64;
	public static final int MAX_NODES = 32;

	/** A mob that knows of the sighting (by entity id): where it was when it learned, and how many hops from the mob that saw it. */
	public record Node(int mob, double x, double y, double z, int hops) {
	}

	/** What a mob learns: whose sighting, the remembered point, when it was made, and how many hops away the mob heard of it. */
	public record Known(UUID player, double x, double y, double z, long tick, int hops) {
	}

	private static final class Sighting {
		double x;
		double y;
		double z;
		long tick;
		final List<Node> nodes = new ArrayList<>();
	}

	private final Map<UUID, Sighting> sightings = new LinkedHashMap<>();

	public int size() {
		return sightings.size();
	}

	public int nodeCount(UUID player) {
		Sighting s = sightings.get(player);
		return s == null ? 0 : s.nodes.size();
	}

	/** Mob {@code mob} at (mx, my, mz) sees {@code player} at (px, py, pz) at game tick {@code tick}. */
	public void sighted(UUID player, double px, double py, double pz, int mob, double mx, double my, double mz, long tick) {
		Sighting s = sightings.get(player);
		if (s == null) {
			if (sightings.size() >= MAX_SIGHTINGS) {
				evictOldest();
			}
			s = new Sighting();
			sightings.put(player, s);
		}
		if (tick >= s.tick) {
			s.x = px;
			s.y = py;
			s.z = pz;
			s.tick = tick;
		}
		addNode(s, new Node(mob, mx, my, mz, 0));
	}

	/**
	 * What mob {@code mob} at (x, y, z) can learn right now: the freshest live sighting of an eligible player that it already knows
	 * (it is a node: it saw the player, or heard earlier), or that reaches it through a node within {@code radius} whose hop count is
	 * below {@code maxHops}. A mob that learns becomes a node one hop further. Sightings this mob has already searched
	 * ({@code searchedPlayer} at or after the sighting tick) are skipped.
	 *
	 * @param eligible players that may be hunted right now (online in this level, Elite, alive); others are ignored, not removed
	 */
	public @Nullable Known learn(int mob, double x, double y, double z, long now, double radius, int maxHops, long ttlTicks, Set<UUID> eligible,
		@Nullable UUID searchedPlayer, long searchedTick) {
		expire(now, ttlTicks);
		if (sightings.isEmpty() || eligible.isEmpty()) {
			return null;
		}
		double r2 = radius * radius;
		Known best = null;
		Sighting bestSighting = null;
		for (Map.Entry<UUID, Sighting> e : sightings.entrySet()) {
			UUID player = e.getKey();
			Sighting s = e.getValue();
			if (!eligible.contains(player) || (player.equals(searchedPlayer) && searchedTick >= s.tick)) {
				continue;
			}
			int bestHops = Integer.MAX_VALUE;
			for (Node n : s.nodes) {
				double dx = n.x() - x;
				double dy = n.y() - y;
				double dz = n.z() - z;
				if (n.mob() == mob) {
					// this mob is already a node (it saw the player, or heard earlier): it knows, whatever the distance
					if (n.hops() < bestHops) {
						bestHops = n.hops();
					}
				} else if (n.hops() < maxHops && dx * dx + dy * dy + dz * dz <= r2 && n.hops() + 1 < bestHops) {
					bestHops = n.hops() + 1;
				}
			}
			if (bestHops == Integer.MAX_VALUE) {
				continue;
			}
			if (best == null || s.tick > best.tick() || (s.tick == best.tick() && bestHops < best.hops())) {
				best = new Known(player, s.x, s.y, s.z, s.tick, bestHops);
				bestSighting = s;
			}
		}
		if (best != null) {
			addNode(bestSighting, new Node(mob, x, y, z, best.hops()));
		}
		return best;
	}

	/** Drops sightings older than {@code ttlTicks}. */
	public void expire(long now, long ttlTicks) {
		sightings.values().removeIf(s -> now - s.tick > ttlTicks);
	}

	public void clear() {
		sightings.clear();
	}

	private static void addNode(Sighting s, Node node) {
		for (int i = 0; i < s.nodes.size(); i++) {
			Node n = s.nodes.get(i);
			if (n.mob() == node.mob()) {
				// the same mob again: keep its best (lowest) hop count, at its newest position
				s.nodes.set(i, new Node(node.mob(), node.x(), node.y(), node.z(), Math.min(n.hops(), node.hops())));
				return;
			}
		}
		if (s.nodes.size() >= MAX_NODES) {
			// drop the oldest relay node; a direct sighting (hop 0) is dropped only if every node is one
			int victim = 0;
			for (int i = 0; i < s.nodes.size(); i++) {
				if (s.nodes.get(i).hops() > 0) {
					victim = i;
					break;
				}
			}
			s.nodes.remove(victim);
		}
		s.nodes.add(node);
	}

	private void evictOldest() {
		Iterator<Map.Entry<UUID, Sighting>> it = sightings.entrySet().iterator();
		UUID oldest = null;
		long oldestTick = Long.MAX_VALUE;
		while (it.hasNext()) {
			Map.Entry<UUID, Sighting> e = it.next();
			if (e.getValue().tick < oldestTick) {
				oldestTick = e.getValue().tick;
				oldest = e.getKey();
			}
		}
		if (oldest != null) {
			sightings.remove(oldest);
		}
	}
}
