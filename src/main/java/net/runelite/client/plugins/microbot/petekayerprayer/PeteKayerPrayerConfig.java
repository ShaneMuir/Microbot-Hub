package net.runelite.client.plugins.microbot.petekayerprayer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(PeteKayerPrayerConfig.configGroup)
public interface PeteKayerPrayerConfig extends Config {
    String configGroup = "pete-kayer-prayer";

    @ConfigItem(
            keyName = "includeSparringPartner",
            name = "Prayer Protection challenge",
            description = "Also react to Pete's sparring partner, so Challenge 1 (Prayer Protection) is covered as well as the Pete fights.",
            position = 0
    )
    default boolean includeSparringPartner() {
        return true;
    }

    @ConfigItem(
            keyName = "restOnMagic",
            name = "Rest on Protect Magic",
            description = "When no attack is on its way, sit on Protect from Magic. Pete's melee and his Voidwaker special land on "
                    + "the same tick they start so they can't be reacted to, and magic is his most common attack, so a resting "
                    + "magic prayer covers the most hits. Has no effect on the sparring partner.",
            position = 1
    )
    default boolean restOnMagic() {
        return true;
    }

    @ConfigItem(
            keyName = "autoSwap",
            name = "Auto weapon swap (Pete only)",
            description = "Pete's overhead prayer follows your weapon a few ticks after you swap. When it matches the weapon you're "
                    + "holding, swap to a style he isn't protecting. Sends equip clicks, so leave it off if you'd rather swap by hand.",
            position = 2
    )
    default boolean autoSwap() {
        return false;
    }

    @ConfigItem(
            keyName = "tankBetweenSwings",
            name = "Tank gear between swings",
            description = "After a swing with the two-handed halberd, go straight back to the Ranged set (crossbow, Dragonfire shield, Masori) for the "
                    + "cooldown. The next attack still picks its weapon from the prayer Pete has up at that moment.",
            position = 2
    )
    default boolean tankBetweenSwings() {
        return true;
    }

    @ConfigItem(
            keyName = "swapOffensivePrayer",
            name = "Swap offensive prayer too",
            description = "Piety with melee, Rigour with ranged, Augury with magic, switched along with the weapon, and turned off again when the fight ends.",
            position = 3
    )
    default boolean swapOffensivePrayer() {
        return true;
    }

    @ConfigItem(
            keyName = "autoFreeze",
            name = "Freeze Pete with Ice Barrage",
            description = "When Pete isn't frozen, make your next attack a freeze: the magic set goes on and Ice Barrage is cast on him in "
                    + "the same tick. Needs the ancient spellbook, the runes, and the magic tab opened once. Only runs with auto weapon swap on.",
            position = 3
    )
    default boolean autoFreeze() {
        return false;
    }

    @ConfigItem(
            keyName = "autoCast",
            name = "Plugin casts the barrage itself",
            description = "Off (default): the plugin only puts the mage set on at the right moment and YOU cast with the Cast Ice Barrage "
                    + "click on Pete, spamming it like in any PvP fight. On: the plugin also sends the cast.",
            position = 3
    )
    default boolean autoCast() {
        return false;
    }

    @ConfigItem(
            keyName = "clickToCast",
            name = "Left-click Pete casts Ice Barrage (staff on)",
            description = "While the staff is in hand, the left-click on Pete says Cast Ice Barrage instead of bashing. Turn the separate "
                    + "Left-Click Cast plugin off, or you will get two cast entries.",
            position = 3
    )
    default boolean clickToCast() {
        return true;
    }

    @ConfigItem(
            keyName = "tankGear",
            name = "Tank gear",
            description = "Comma-separated armour to put straight back on after a barrage. Mage gear is only for the cast.",
            position = 3
    )
    default String tankGear() {
        return "Masori body (f), Masori chaps (f)";
    }

    @ConfigItem(
            keyName = "meleeGear",
            name = "Melee set",
            description = "Comma-separated item names to equip for melee. The first is the weapon.",
            position = 4
    )
    default String meleeGear() {
        return "Noxious halberd";
    }

    @ConfigItem(
            keyName = "rangedGear",
            name = "Ranged set",
            description = "Comma-separated item names to equip for ranged. The first is the weapon.",
            position = 5
    )
    default String rangedGear() {
        return "Zaryte crossbow, Dragonfire shield";
    }

    @ConfigItem(
            keyName = "magicGear",
            name = "Freeze set (staff)",
            description = "Comma-separated item names to equip for the Ice Barrage freeze. The first is the staff. Only used by the freeze, never for the normal swapping.",
            position = 6
    )
    default String magicGear() {
        return "Staff of the Dead, Dragonfire shield";
    }

    @ConfigItem(
            keyName = "swapOrder",
            name = "Swap order",
            description = "Styles to prefer when swapping, first choice first, e.g. RANGED,MELEE,MAGIC.",
            position = 7
    )
    default String swapOrder() {
        return "MELEE,RANGED";
    }

    @ConfigItem(
            keyName = "specWeapon",
            name = "Special weapon",
            description = "While you wield this the auto swap leaves your weapon and prayers alone, so you can spec when you want. Leave empty to disable.",
            position = 8
    )
    default String specWeapon() {
        return "Voidwaker";
    }

    @ConfigItem(
            keyName = "specAssist",
            name = "Voidwaker special assist",
            description = "Whenever your special energy allows, use the special on the next attack: Piety on, the special weapon equipped and the "
                    + "special bar switched on the tick before the attack, then straight back to the halberd and crossbow. Needs the special "
                    + "weapon in your inventory. Sends equip and special-bar clicks, so leave it off if you'd rather spec by hand.",
            position = 8
    )
    default boolean specAssist() {
        return false;
    }

    @ConfigItem(
            keyName = "specEnergyPercent",
            name = "Special energy needed (%)",
            description = "The special energy the assist waits for before it uses the special. 50 is the Voidwaker's cost.",
            position = 8
    )
    default int specEnergyPercent() {
        return 50;
    }

    @ConfigItem(
            keyName = "specHoldMs",
            name = "Special hold (ms)",
            description = "How long to leave the special weapon alone after you wield it, before swapping resumes.",
            position = 9
    )
    default int specHoldMs() {
        return 4000;
    }

    @ConfigItem(
            keyName = "swapCooldownMs",
            name = "Swap cooldown (ms)",
            description = "Minimum time between weapon swaps.",
            position = 10
    )
    default int swapCooldownMs() {
        return 1200;
    }
}
