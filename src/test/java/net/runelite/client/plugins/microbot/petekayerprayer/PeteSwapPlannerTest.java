package net.runelite.client.plugins.microbot.petekayerprayer;

import net.runelite.client.plugins.microbot.petekayerprayer.PeteSwapPlanner.Style;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public final class PeteSwapPlannerTest {
    private static final List<Style> ORDER = Arrays.asList(Style.RANGED, Style.MELEE, Style.MAGIC);

    private static Style choose(Style current, int protectedIcon, boolean magicAvailable, long now, long last) {
        return PeteSwapPlanner.choose(current, protectedIcon, ORDER, s -> s != Style.MAGIC || magicAvailable, now, last, 1_200);
    }

    @Test
    void staysWhenHeHasNoOverheadPrayer() {
        assertNull(choose(Style.MELEE, -1, false, 10_000, 0));
    }

    @Test
    void leavesAnUnrecognisedWeaponEvenWhenHeHasNoPrayerUp() {
        // The staff is not a style, so wielding it is "unknown": with no prayer to dodge, still return to a set.
        assertEquals(Style.RANGED, choose(null, -1, false, 10_000, 0));
        assertNull(choose(null, -1, false, 10_500, 10_000)); // but not inside the cooldown
    }

    @Test
    void staysWhenHittingAStyleHeIsNotProtecting() {
        assertNull(choose(Style.MELEE, Style.RANGED.getIcon(), false, 10_000, 0));
    }

    @Test
    void swapsOffAStyleOnceHeProtectsIt() {
        assertEquals(Style.MELEE, choose(Style.RANGED, Style.RANGED.getIcon(), false, 10_000, 0));
        assertEquals(Style.RANGED, choose(Style.MELEE, Style.MELEE.getIcon(), false, 10_000, 0));
    }

    @Test
    void respectsTheCooldown() {
        assertNull(choose(Style.RANGED, Style.RANGED.getIcon(), false, 10_500, 10_000));
        assertEquals(Style.MELEE, choose(Style.RANGED, Style.RANGED.getIcon(), false, 11_300, 10_000));
    }

    @Test
    void usesTheOrderAndSkipsUnavailableStyles() {
        // Wielding melee while he protects melee: ranged is first in the order.
        assertEquals(Style.RANGED, choose(Style.MELEE, Style.MELEE.getIcon(), true, 10_000, 0));
        // An unknown weapon is treated like any blocked style.
        assertEquals(Style.MELEE, choose(null, Style.RANGED.getIcon(), true, 10_000, 0));
    }

    @Test
    void parsesOrderAndItems() {
        assertEquals(Arrays.asList(Style.MAGIC, Style.RANGED, Style.MELEE), PeteSwapPlanner.parseOrder("magic, ranged, nonsense"));
        assertEquals(Arrays.asList("Zaryte crossbow", "Dragonfire shield"), PeteSwapPlanner.parseItems(" Zaryte crossbow , Dragonfire shield,, "));
        assertEquals(0, PeteSwapPlanner.parseItems("").size());
    }
}
