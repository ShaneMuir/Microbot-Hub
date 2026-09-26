package net.runelite.client.plugins.microbot.baropasgefiremaker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(BaropasGeFiremakerConfig.configGroup)
public interface BaropasGeFiremakerConfig extends Config {
    String configGroup = "baropas-ge-firemaker";

    @ConfigSection(
            name = "Firemaking",
            description = "Log type and line layout. Start the plugin while standing at the Grand Exchange "
                    + "bank booth - it lines fires from wherever you're standing and does not walk you there.",
            position = 0
    )
    String firemakingSection = "firemakingSection";

    @ConfigSection(
            name = "Restocking",
            description = "How to get more logs when you run out",
            position = 1
    )
    String restockSection = "restockSection";

    @ConfigSection(
            name = "Anti-ban",
            description = "Human-like timing and behavior",
            position = 2
    )
    String antibanSection = "antibanSection";

    @ConfigSection(
            name = "Debug",
            description = "Diagnostics for developing/troubleshooting the script",
            position = 3,
            closedByDefault = true
    )
    String debugSection = "debugSection";

    @ConfigItem(
            keyName = "logType",
            name = "Log type",
            description = "Which log to burn",
            position = 0,
            section = firemakingSection
    )
    default LogType logType() {
        return LogType.WILLOW;
    }

    @Range(min = 3, max = 27)
    @ConfigItem(
            keyName = "lineLength",
            name = "Max line length",
            description = "Upper limit on fires per line. The script scans nearby tiles and burns the "
                    + "longest clear east-to-west stretch it can find, up to this many fires. Defaults to "
                    + "a full inventory (27 logs, since the tinderbox takes the 28th slot).",
            position = 1,
            section = firemakingSection
    )
    default int lineLength() {
        return 27;
    }

    @Range(min = 1, max = 27)
    @ConfigItem(
            keyName = "restockAmount",
            name = "Restock amount",
            description = "How many logs to withdraw from the bank per restock",
            position = 0,
            section = restockSection
    )
    default int restockAmount() {
        return 27;
    }

    @ConfigItem(
            keyName = "useAntiban",
            name = "Enable anti-ban",
            description = "Randomized reaction times, mouse movement, and micro-breaks",
            position = 0,
            section = antibanSection
    )
    default boolean useAntiban() {
        return true;
    }

    @ConfigItem(
            keyName = "showDebugOverlay",
            name = "Show debug info",
            description = "Adds state machine and bank/tile diagnostics to the overlay",
            position = 0,
            section = debugSection
    )
    default boolean showDebugOverlay() {
        return false;
    }
}
