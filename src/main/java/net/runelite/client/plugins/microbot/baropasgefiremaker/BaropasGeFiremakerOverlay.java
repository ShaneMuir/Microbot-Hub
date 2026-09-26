package net.runelite.client.plugins.microbot.baropasgefiremaker;

import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;

public class BaropasGeFiremakerOverlay extends OverlayPanel {

    private final BaropasGeFiremakerScript script;
    private final BaropasGeFiremakerConfig config;

    @Inject
    BaropasGeFiremakerOverlay(BaropasGeFiremakerPlugin plugin, BaropasGeFiremakerScript script, BaropasGeFiremakerConfig config) {
        super(plugin);
        this.script = script;
        this.config = config;
        setPosition(OverlayPosition.TOP_LEFT);
        setPreferredSize(new Dimension(ComponentConstants.STANDARD_WIDTH, 160));
        setNaughty();
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            panelComponent.getChildren().clear();

            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("Baropa's GE Firemaker")
                    .color(Color.ORANGE)
                    .build());

            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Log:")
                    .right(config.logType().getLogName())
                    .build());

            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Fires lit:")
                    .right(String.valueOf(script.getFiresLit()))
                    .build());

            long runtimeSeconds = Math.max(1, script.getRuntimeSeconds());
            int xpGained = script.getXpGained();
            int xpPerHour = (int) (xpGained * 3600L / runtimeSeconds);

            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Firemaking XP:")
                    .right(xpGained + " (" + xpPerHour + "/hr)")
                    .build());

            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Runtime:")
                    .right(formatDuration(runtimeSeconds))
                    .build());

            if (config.showDebugOverlay()) {
                addDebugLines();
            }

        } catch (Exception ex) {
            // Overlay rendering must never take the script down with it.
        }
        return super.render(graphics);
    }

    private void addDebugLines() {
        panelComponent.getChildren().add(LineComponent.builder()
                .left("--- debug ---")
                .leftColor(Color.CYAN)
                .build());

        panelComponent.getChildren().add(LineComponent.builder()
                .left("State:")
                .right(String.valueOf(script.getCurrentState()))
                .rightColor(Color.YELLOW)
                .build());

        panelComponent.getChildren().add(LineComponent.builder()
                .left("Line pos:")
                .right(script.getLineIndex() + " / " + script.getLineLength())
                .build());

        if (script.getCurrentTarget() != null) {
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Target tile:")
                    .right(script.getCurrentTarget().getX() + "," + script.getCurrentTarget().getY())
                    .build());
        }

        panelComponent.getChildren().add(LineComponent.builder()
                .left("Walk fails:")
                .right(String.valueOf(script.getWalkFailures()))
                .build());

        panelComponent.getChildren().add(LineComponent.builder()
                .left("Light attempts:")
                .right(String.valueOf(script.getLightAttempts()))
                .build());

        panelComponent.getChildren().add(LineComponent.builder()
                .left("Withdraw as note:")
                .right(String.valueOf(script.isWithdrawAsNote()))
                .rightColor(script.isWithdrawAsNote() ? Color.RED : Color.GREEN)
                .build());

        panelComponent.getChildren().add(LineComponent.builder()
                .left("Last restock:")
                .right(script.getLastRestockResult())
                .build());
    }

    private static String formatDuration(long totalSeconds) {
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        }
        return String.format("%dm %ds", minutes, seconds);
    }
}
