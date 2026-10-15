package net.runelite.client.plugins.microbot.petekayerprayer;

/**
 * Says whether the fight with Pete has actually started. Everything that changes gear or prayers
 * waits for this, so nothing happens while the player is just standing near him or talking to him
 * (talking to an NPC targets it just like attacking does). It needs both the player targeting Pete and
 * an attack - the player's own attack animation, or Pete attacking - and it ends once the attacks stop.
 */
final class PeteFightGate {
    // The player targeting Pete can drop for a tick or two (eating, swapping).
    static final int ENGAGED_GRACE_TICKS = 10;
    // Attacks come every 4-5 ticks, so this is a few missed ones.
    static final int ATTACK_GRACE_TICKS = 15;

    private static final int NEVER = Integer.MIN_VALUE / 2;

    private int lastEngagedTick = NEVER;
    private int lastAttackTick = NEVER;

    /** Call every game tick with whether the player is targeting Pete right now. */
    void update(boolean engaged, int tick) {
        if (engaged) {
            lastEngagedTick = tick;
        }
    }

    /** The player attacked, or Pete attacked the player. */
    void onAttack(int tick) {
        lastAttackTick = tick;
    }

    boolean isActive(int tick) {
        return tick - lastEngagedTick <= ENGAGED_GRACE_TICKS && tick - lastAttackTick <= ATTACK_GRACE_TICKS;
    }

    void reset() {
        lastEngagedTick = NEVER;
        lastAttackTick = NEVER;
    }
}
