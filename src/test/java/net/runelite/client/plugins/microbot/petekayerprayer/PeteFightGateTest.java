package net.runelite.client.plugins.microbot.petekayerprayer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class PeteFightGateTest {
    @Test
    void inactiveBeforeAnyAttack() {
        PeteFightGate gate = new PeteFightGate();
        gate.update(false, 100);
        assertFalse(gate.isActive(100));
        assertFalse(gate.isActive(5_000));
    }

    @Test
    void talkingToPeteIsNotTheFight() {
        // Talking to an NPC targets it like attacking does, but no attack has happened.
        PeteFightGate gate = new PeteFightGate();
        for (int tick = 100; tick < 140; tick++) {
            gate.update(true, tick);
            assertFalse(gate.isActive(tick));
        }
    }

    @Test
    void activeOnceTargetedAndAttacking() {
        PeteFightGate gate = new PeteFightGate();
        gate.update(true, 100);
        gate.onAttack(100);
        assertTrue(gate.isActive(100));
        gate.update(false, 105);
        assertTrue(gate.isActive(105)); // target dropped briefly
        assertTrue(gate.isActive(100 + PeteFightGate.ENGAGED_GRACE_TICKS));
    }

    @Test
    void attackingSomethingElseDoesNotStartIt() {
        PeteFightGate gate = new PeteFightGate();
        gate.onAttack(100); // an attack animation while not targeting Pete
        assertFalse(gate.isActive(100));
    }

    @Test
    void endsWhenAttacksStopAndResets() {
        PeteFightGate gate = new PeteFightGate();
        gate.update(true, 100);
        gate.onAttack(100);
        gate.update(true, 112);
        assertFalse(gate.isActive(100 + PeteFightGate.ATTACK_GRACE_TICKS + 1));
        gate.onAttack(200);
        gate.update(true, 200);
        assertTrue(gate.isActive(200));
        gate.reset();
        assertFalse(gate.isActive(200));
    }
}
