package net.runelite.client.plugins.microbot.petekayerprayer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Decides when to change weapon style. Pete's overhead prayer follows the style of the weapon you
 * wield a few ticks after you swap, so the aim is to swap off a style once he is protecting it.
 */
final class PeteSwapPlanner {

    /** The icon index is the value in Pete's overhead sprite id: 0 melee, 1 ranged, 2 magic. */
    enum Style {
        MELEE(0), RANGED(1), MAGIC(2);

        private final int icon;

        Style(int icon) {
            this.icon = icon;
        }

        int getIcon() {
            return icon;
        }

        static Style fromIcon(int icon) {
            for (Style style : values()) {
                if (style.icon == icon) {
                    return style;
                }
            }
            return null;
        }
    }

    private PeteSwapPlanner() {
    }

    /**
     * @param current   the style of the weapon wielded now, or null if it isn't one of the configured weapons
     * @param protectedIcon the style Pete is protecting, or -1 if he has no overhead prayer up
     * @param order     styles to prefer, first choice first
     * @param available whether a style's weapon can be equipped right now
     * @return the style to swap to, or null to stay as you are
     */
    static Style choose(Style current, int protectedIcon, List<Style> order, Predicate<Style> available,
                        long now, long lastSwapMs, long cooldownMs) {
        Style blocked = Style.fromIcon(protectedIcon);
        if (blocked == null) {
            // He has no prayer up, so any weapon is fine - but off an unrecognised weapon (a staff that
            // isn't casting) still go back to one of the sets rather than sit on it.
            if (current != null || now - lastSwapMs < cooldownMs) {
                return null;
            }
            for (Style style : order) {
                if (available.test(style)) {
                    return style;
                }
            }
            return null;
        }
        if (current != null && current != blocked) {
            return null; // already hitting a style he isn't protecting
        }
        if (now - lastSwapMs < cooldownMs) {
            return null;
        }
        for (Style style : order) {
            if (style != blocked && style != current && available.test(style)) {
                return style;
            }
        }
        return null;
    }

    /** Parses "RANGED, magic, melee"; unknown words are skipped and missing styles are appended last. */
    static List<Style> parseOrder(String text) {
        List<Style> order = new ArrayList<>();
        if (text != null) {
            for (String part : text.split(",")) {
                try {
                    Style style = Style.valueOf(part.trim().toUpperCase());
                    if (!order.contains(style)) {
                        order.add(style);
                    }
                } catch (IllegalArgumentException ignored) {
                    // not a style name
                }
            }
        }
        for (Style style : Style.values()) {
            if (!order.contains(style)) {
                order.add(style);
            }
        }
        return order;
    }

    /** Splits a comma-separated list of item names. */
    static List<String> parseItems(String text) {
        List<String> items = new ArrayList<>();
        if (text != null) {
            for (String part : text.split(",")) {
                String name = part.trim();
                if (!name.isEmpty()) {
                    items.add(name);
                }
            }
        }
        return items;
    }
}
