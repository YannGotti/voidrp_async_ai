package ru.voidrp.asyncai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-entity path result cache with TTL.
 *
 * When a mob's async path computation finishes, the result is stored here keyed
 * by the mob's UUID. If the same mob requests a path to the same target within
 * the TTL window, the cached node list is returned as a fresh Path instance.
 *
 * Nodes are shared (they're effectively immutable once a path is finalized).
 * A new Path wrapper is always constructed so each mob has its own
 * nextNodeIndex state.
 */
public final class PathCache {

    /** Maximum number of cached entries. Oldest evicted when exceeded. */
    private static final int MAX_ENTRIES = 512;

    /** How long a cached path stays valid (milliseconds, ~20 ticks). */
    private static final long TTL_MS = 1000L;

    private record Entry(List<Node> nodes, BlockPos target, boolean reached, long expiresAt) {}

    private static final ConcurrentHashMap<UUID, Entry> cache = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Store a computed path for this entity. Thread-safe — intended to be called
     * from the async worker thread immediately after path computation.
     */
    public static void store(UUID mobId, @Nullable Path path) {
        if (!AiConfig.PATH_CACHE_ENABLED.get()) return;
        if (path == null || path.getNodeCount() == 0) return;

        List<Node> nodes = new ArrayList<>(path.getNodeCount());
        for (int i = 0; i < path.getNodeCount(); i++) {
            nodes.add(path.getNode(i));
        }

        cache.put(mobId, new Entry(
            List.copyOf(nodes),
            path.getTarget(),
            path.canReach(),
            System.currentTimeMillis() + TTL_MS
        ));

        if (cache.size() > MAX_ENTRIES) evict();
    }

    /**
     * Attempt to retrieve a valid cached path for this entity targeting any of the
     * given positions. Returns null on cache miss or expiry.
     *
     * Always returns a fresh Path instance with independent mutable state.
     * Call from main thread only.
     */
    @Nullable
    public static Path retrieve(UUID mobId, Set<BlockPos> targets) {
        if (!AiConfig.PATH_CACHE_ENABLED.get()) return null;

        Entry entry = cache.get(mobId);
        if (entry == null) return null;

        if (System.currentTimeMillis() > entry.expiresAt()) {
            cache.remove(mobId);
            return null;
        }

        if (!targets.contains(entry.target())) return null;

        // New Path wrapper so each mob has independent nextNodeIndex
        return new Path(new ArrayList<>(entry.nodes()), entry.target(), entry.reached());
    }

    /** Remove entries for entities that no longer exist. */
    public static void cleanup(Iterable<? extends Mob> liveMobs) {
        var liveIds = new HashSet<UUID>();
        for (Mob m : liveMobs) liveIds.add(m.getUUID());
        cache.keySet().retainAll(liveIds);
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private static void evict() {
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
        // If still over limit after TTL eviction, just clear the whole map
        if (cache.size() > MAX_ENTRIES) cache.clear();
    }
}
