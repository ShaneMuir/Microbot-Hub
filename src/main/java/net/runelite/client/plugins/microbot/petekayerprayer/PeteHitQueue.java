package net.runelite.client.plugins.microbot.petekayerprayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks the hits Pete has wound up and says which protection prayer covers the next one to land,
 * so overlapping attacks are covered one after another instead of the first one hiding the second.
 */
final class PeteHitQueue {
    // A hit is checked on the server tick it lands, so keep the prayer a little past the estimate.
    private static final long GRACE_MS = 250;
    // Hits landing this close together are on the same tick and can't both be covered.
    private static final long SAME_TICK_MS = 300;

    private static final class Entry {
        final PeteAttackStyle style;
        final long landMs;

        Entry(PeteAttackStyle style, long landMs) {
            this.style = style;
            this.landMs = landMs;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    /** He started an attack in {@code style} from {@code distance} tiles away. */
    synchronized void onAttack(PeteAttackStyle style, int distance, long now) {
        entries.add(new Entry(style, now + style.landDelayMs(distance)));
    }

    /** The style to be praying at {@code now}, or null if nothing is pending. */
    synchronized PeteAttackStyle next(long now) {
        entries.removeIf(e -> now > e.landMs + GRACE_MS);
        Entry first = null;
        for (Entry e : entries) {
            if (first == null || e.landMs < first.landMs) {
                first = e;
            }
        }
        if (first == null) {
            return null;
        }
        Entry best = first;
        for (Entry e : entries) {
            if (e.landMs - first.landMs <= SAME_TICK_MS && e.style.getPriority() > best.style.getPriority()) {
                best = e;
            }
        }
        return best.style;
    }

    synchronized void clear() {
        entries.clear();
    }
}
