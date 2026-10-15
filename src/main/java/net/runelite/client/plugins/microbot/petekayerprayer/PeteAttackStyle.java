package net.runelite.client.plugins.microbot.petekayerprayer;

import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;

/**
 * The attack styles that can be reacted to, keyed by Pete Kayer's attack animation (built from live
 * logging of the PvP Challenges). Melee is not here on purpose: it lands on the animation tick, so by
 * the time the animation is seen the hit is over and the prayer is better spent on what comes next.
 */
enum PeteAttackStyle {
    // Crossbow shots land 2 ticks after the animation, whatever the distance.
    RANGED(Rs2PrayerEnum.PROTECT_RANGE, 1, new int[]{426, 425, 4230, 9166}),
    // Spells land 1 + floor((1 + distance) / 3) ticks after the animation. 11275 is the Voidwaker
    // special: magic-typed but it lands on the animation tick, so it can only be pre-empted by resting on magic.
    MAGIC(Rs2PrayerEnum.PROTECT_MAGIC, 2, new int[]{1979, 420, 11275});

    private static final long TICK_MS = 600;

    private final Rs2PrayerEnum prayer;
    private final int priority;
    private final int[] animations;

    PeteAttackStyle(Rs2PrayerEnum prayer, int priority, int[] animations) {
        this.prayer = prayer;
        this.priority = priority;
        this.animations = animations;
    }

    /** Which style wins when two hits land on the same tick: magic hits harder than ranged. */
    int getPriority() {
        return priority;
    }

    Rs2PrayerEnum getPrayer() {
        return prayer;
    }

    /** How long after the animation the hit lands, for an attacker {@code distance} tiles away. */
    long landDelayMs(int distance) {
        if (this == MAGIC) {
            return (1 + (1 + Math.max(distance, 0)) / 3) * TICK_MS;
        }
        return 2 * TICK_MS;
    }

    /** Returns null for animations that aren't a reactable attack (melee, eating, idle, weapon swaps). */
    static PeteAttackStyle fromAnimation(int animation) {
        for (PeteAttackStyle style : values()) {
            for (int id : style.animations) {
                if (id == animation) {
                    return style;
                }
            }
        }
        return null;
    }
}
