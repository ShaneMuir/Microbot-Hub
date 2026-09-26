package net.runelite.client.plugins.microbot.baropasgefiremaker;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;

@PluginDescriptor(
        name = PluginConstants.BAROPA + "GE Firemaker",
        authors = {"Baropa"},
        version = BaropasGeFiremakerPlugin.version,
        description = "Lines logs east to west, restocking from the bank. Start it while standing at the "
                + "Grand Exchange - it does not walk you there.",
        tags = {"firemaking", "microbot", "skilling", "grand exchange", "baropa"},
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL,
        minClientVersion = "2.6.24"
)
@Slf4j
public class BaropasGeFiremakerPlugin extends Plugin {
    static final String version = "1.0.0";

    @Inject
    private BaropasGeFiremakerScript script;

    @Inject
    private BaropasGeFiremakerOverlay overlay;

    @Inject
    private BaropasGeFiremakerConfig config;

    @Inject
    private OverlayManager overlayManager;

    @Provides
    BaropasGeFiremakerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(BaropasGeFiremakerConfig.class);
    }

    @Override
    protected void startUp() throws AWTException {
        overlayManager.add(overlay);
        script.run(config);
    }

    @Override
    protected void shutDown() {
        overlayManager.remove(overlay);
        script.shutdown();
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        script.onChatMessage(event);
    }
}
