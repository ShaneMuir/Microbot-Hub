package net.runelite.client.plugins.microbot.petekayerprayer;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.MenuAction;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class PeteKayerPrayerScript extends Script {

    private static final long RESEND_MS = 180;
    private static final long GIVE_UP_MS = 3_000;

    private final PeteHitQueue queue = new PeteHitQueue();

    // The prayer we want on right now. Cleared only once it is confirmed active, so a click that
    // gets dropped is simply sent again rather than lost.
    private final AtomicReference<Rs2PrayerEnum> desired = new AtomicReference<>();
    private volatile long desiredAtMs;
    private Rs2PrayerEnum target;
    private long lastSendMs;
    private Rs2PrayerEnum loggedPrayer;

    // What to sit on when no hit is on its way; null leaves the prayer alone.
    private volatile Rs2PrayerEnum rest;

    public void setRest(Rs2PrayerEnum prayer) {
        rest = prayer;
    }

    /** He started an attack in {@code style} from {@code distance} tiles away. */
    public void onAttack(PeteAttackStyle style, int distance, String source) {
        log.info("[PeteKayerPrayer] attack {} from {} tiles ({})", style, distance, source);
        queue.onAttack(style, distance, System.currentTimeMillis());
    }

    public boolean run() {
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                if (!super.run()) return;

                long now = System.currentTimeMillis();
                PeteAttackStyle incoming = queue.next(now);
                Rs2PrayerEnum want = incoming != null ? incoming.getPrayer() : rest;
                if (want != null && want != target) {
                    target = want;
                    desiredAtMs = now;
                    desired.set(want);
                    log.info("[PeteKayerPrayer] pray {} ({})", want, incoming != null ? "incoming" : "rest");
                }

                Rs2PrayerEnum current = desired.get();
                if (current == null) return;

                if (Rs2Prayer.isPrayerActive(current)) {
                    desired.compareAndSet(current, null);
                    Microbot.status = "Pete Kayer Prayer: " + current;
                    if (loggedPrayer == current) {
                        log.info("[PeteKayerPrayer] {} active after {}ms", current, now - desiredAtMs);
                        loggedPrayer = null;
                    }
                    return;
                }
                if (now - desiredAtMs > GIVE_UP_MS) {
                    log.warn("[PeteKayerPrayer] Gave up switching to {} after {}ms", current, now - desiredAtMs);
                    desired.compareAndSet(current, null);
                    return;
                }
                if (Rs2Prayer.isOutOfPrayer()) {
                    desired.compareAndSet(current, null);
                    return;
                }
                if (now - lastSendMs < RESEND_MS) return;

                lastSendMs = now;
                loggedPrayer = current;
                send(current);
            } catch (Exception ex) {
                log.error("[PeteKayerPrayer] Error in main loop", ex);
            }
        }, 0, 50, TimeUnit.MILLISECONDS);
        return true;
    }

    /**
     * Sends the prayer as a direct menu action instead of through the mouse. Scripted mouse input
     * yields whenever the player is using the mouse or keyboard, which in a fight (eating, speccing,
     * switching gear) is constantly - so the click would be silently dropped exactly when it matters.
     */
    private void send(Rs2PrayerEnum prayer) {
        Microbot.getClientThread().invoke(() ->
                Microbot.getClient().menuAction(-1, prayer.getIndex(), MenuAction.CC_OP, 1, -1, "Activate", "Activate"));
    }

    @Override
    public void shutdown() {
        desired.set(null);
        target = null;
        rest = null;
        queue.clear();
        super.shutdown();
    }
}
