package net.runelite.client.plugins.microbot.baropasgefiremaker;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.gameval.ItemID;

/**
 * Loggable-for-fire logs, ordered by Firemaking level requirement.
 */
@Getter
@RequiredArgsConstructor
public enum LogType {
    NORMAL("Logs", ItemID.LOGS, 1),
    OAK("Oak logs", ItemID.OAK_LOGS, 15),
    WILLOW("Willow logs", ItemID.WILLOW_LOGS, 30),
    TEAK("Teak logs", ItemID.TEAK_LOGS, 35),
    MAPLE("Maple logs", ItemID.MAPLE_LOGS, 45),
    MAHOGANY("Mahogany logs", ItemID.MAHOGANY_LOGS, 50),
    YEW("Yew logs", ItemID.YEW_LOGS, 60),
    MAGIC("Magic logs", ItemID.MAGIC_LOGS, 75),
    REDWOOD("Redwood logs", ItemID.REDWOOD_LOGS, 90);

    private final String logName;
    private final int itemId;
    private final int levelRequired;

    @Override
    public String toString() {
        return logName;
    }
}
