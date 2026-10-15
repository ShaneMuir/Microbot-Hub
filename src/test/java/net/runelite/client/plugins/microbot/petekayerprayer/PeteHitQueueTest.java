package net.runelite.client.plugins.microbot.petekayerprayer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public final class PeteHitQueueTest {
    @Test
    void idleQueueHasNothingPending() {
        assertNull(new PeteHitQueue().next(0));
    }

    @Test
    void magicLandDelayFollowsDistance() {
        assertEquals(600, PeteAttackStyle.MAGIC.landDelayMs(1));
        assertEquals(1_200, PeteAttackStyle.MAGIC.landDelayMs(2));
        assertEquals(1_200, PeteAttackStyle.MAGIC.landDelayMs(4));
        assertEquals(1_800, PeteAttackStyle.MAGIC.landDelayMs(5));
        assertEquals(1_200, PeteAttackStyle.RANGED.landDelayMs(1));
        assertEquals(1_200, PeteAttackStyle.RANGED.landDelayMs(6));
    }

    @Test
    void rangedThenCloseMagicCoversBothInOrder() {
        PeteHitQueue q = new PeteHitQueue();
        q.onAttack(PeteAttackStyle.RANGED, 1, 0);   // lands 1200
        q.onAttack(PeteAttackStyle.MAGIC, 1, 1_500); // lands 2100
        assertEquals(PeteAttackStyle.RANGED, q.next(1_000));
        assertEquals(PeteAttackStyle.MAGIC, q.next(1_600)); // ranged landed, magic still due
        assertNull(q.next(2_500));
    }

    @Test
    void sameTickHitsGoToMagic() {
        PeteHitQueue q = new PeteHitQueue();
        q.onAttack(PeteAttackStyle.RANGED, 1, 0);   // lands 1200
        q.onAttack(PeteAttackStyle.MAGIC, 1, 600);  // lands 1200
        assertEquals(PeteAttackStyle.MAGIC, q.next(700));
    }

    @Test
    void closeMagicFirstThenRangedLandingLater() {
        PeteHitQueue q = new PeteHitQueue();
        q.onAttack(PeteAttackStyle.MAGIC, 1, 0);    // lands 600
        q.onAttack(PeteAttackStyle.RANGED, 1, 600); // lands 1800
        assertEquals(PeteAttackStyle.MAGIC, q.next(300));
        assertEquals(PeteAttackStyle.RANGED, q.next(1_000));
    }

    @Test
    void meleeAnimationsAreNotAReactableStyle() {
        assertNull(PeteAttackStyle.fromAnimation(440));
        assertNull(PeteAttackStyle.fromAnimation(390));
        assertEquals(PeteAttackStyle.RANGED, PeteAttackStyle.fromAnimation(9166));
        assertEquals(PeteAttackStyle.MAGIC, PeteAttackStyle.fromAnimation(1979));
    }
}
