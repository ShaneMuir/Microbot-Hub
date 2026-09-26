package net.runelite.client.plugins.microbot.baropasgefiremaker;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.statemachine.StateMachineScript;
import net.runelite.client.plugins.microbot.statemachine.Transition;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.antiban.enums.Activity;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import javax.swing.SwingUtilities;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Scans nearby tiles for the longest clear east-to-west stretch and burns the whole thing in one
 * pass, the way a real player would, rather than working off a fixed grid. Restocks from the bank
 * when supplies run out and replans a fresh line afterwards; stops and messages the user if the
 * bank is empty.
 */
@Slf4j
public class BaropasGeFiremakerScript extends StateMachineScript<BaropasGeFiremakerScript.State> {

    enum State {
        PLAN_LINE,
        WALK_TO_TILE,
        LIGHT_LOG,
        ADVANCE,
        RESTOCK,
        STOPPED
    }

    private static final int MAX_LIGHT_ATTEMPTS_PER_TILE = 3;
    private static final int MAX_WALK_FAILURES_PER_TILE = 2;
    private static final int ROW_SCAN_RADIUS = 8;
    private static final int COL_SCAN_RADIUS = 15;
    private static final int MIN_LINE_LENGTH = 3;
    // How many tiles shorter than the single longest row still counts as "good enough" to be a
    // candidate for random selection, so line choice actually varies instead of collapsing to
    // whichever row happens to be uniquely longest.
    private static final int LINE_LENGTH_TOLERANCE = 4;
    // OSRS blocks firemaking within a couple of tiles of a bank booth. Observed failures were at
    // distance 2 from the booth, so exclude that ring from the scan up front instead of relying
    // solely on learning it the hard way (via noFireZones) each session.
    private static final int BANK_EXCLUSION_RADIUS = 3;
    // How often to re-check the bank while stopped for missing supplies - frequent enough to
    // resume soon after a restock, infrequent enough not to spam bank visits while genuinely empty.
    private static final int STOPPED_RETRY_INTERVAL_SECONDS = 20;

    private BaropasGeFiremakerConfig config;
    private boolean readyToStart;

    // Line layout: a precomputed, ordered (east to west) run of walkable, fire-free tiles.
    @Getter
    private List<WorldPoint> currentLine;
    @Getter
    private int lineIndex;
    @Getter
    private WorldPoint currentTarget;
    // Sticky row choice: reused across replans while it still yields a usable line, since a real
    // player keeps working the same spot rather than hunting for somewhere new every trip.
    private Integer preferredRowY;
    @Getter
    private int walkFailures;
    @Getter
    private int lightAttempts;

    // Per-tick action flag, mirrors the StateMachineExampleScript pattern.
    private boolean actionDone;
    private boolean restockSucceeded;
    @Getter
    private String lastRestockResult = "n/a";
    private Instant stoppedRetryAt;
    // Set when the bank simply has none of the configured log type - unlike a missing tinderbox
    // or a bank the character couldn't reach, more waiting won't fix this, so it's worth giving up
    // outright instead of retrying forever.
    private boolean logTypeUnavailable;

    // Chat feedback, set on the client/event thread and consumed on the script thread.
    private final AtomicBoolean cannotLightHere = new AtomicBoolean(false);

    // Tiles where the game has told us firemaking isn't allowed (e.g. near the Clan Hall),
    // excluded permanently from both planning and in-progress lines.
    private final Set<WorldPoint> noFireZones = new HashSet<>();

    // Stats
    @Getter
    private int firesLit;
    @Getter
    private int logsBurned;
    private int startXp = -1;
    private Instant startedAt;

    public void onChatMessage(ChatMessage event) {
        if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM) {
            return;
        }
        String message = event.getMessage();
        if (message.contains("can't light a fire here") || message.contains("can't light this")) {
            cannotLightHere.set(true);
        }
    }

    @Override
    protected State initialState() {
        return State.PLAN_LINE;
    }

    @Override
    protected List<Transition<State>> defineTransitions() {
        List<Transition<State>> transitions = new ArrayList<>();

        List<State> productionStates = List.of(State.PLAN_LINE, State.WALK_TO_TILE, State.LIGHT_LOG);
        for (State state : productionStates) {
            transitions.add(Transition.<State>from(state)
                    .when(this::missingTinderbox, "missingTinderbox()")
                    .because("No tinderbox, checking the bank")
                    .goTo(State.RESTOCK));

            transitions.add(Transition.<State>from(state)
                    .when(this::outOfLogs, "outOfLogs()")
                    .because("Ran out of logs")
                    .goTo(State.RESTOCK));
        }

        transitions.add(Transition.<State>from(State.PLAN_LINE)
                .when(() -> actionDone, "actionDone")
                .because("Line planned")
                .goTo(State.WALK_TO_TILE));

        transitions.add(Transition.<State>from(State.WALK_TO_TILE)
                .when(this::lineExhausted, "lineExhausted()")
                .because("Line finished, planning a new one")
                .goTo(State.PLAN_LINE));

        transitions.add(Transition.<State>from(State.WALK_TO_TILE)
                .when(this::atTarget, "atTarget()")
                .because("Arrived at line tile")
                .goTo(State.LIGHT_LOG));

        transitions.add(Transition.<State>from(State.LIGHT_LOG)
                .when(() -> actionDone, "actionDone")
                .because("Light attempt resolved")
                .goTo(State.ADVANCE));

        transitions.add(Transition.<State>from(State.ADVANCE)
                .when(() -> actionDone && lineExhausted(), "actionDone && lineExhausted()")
                .because("Line finished, planning a new one")
                .goTo(State.PLAN_LINE));

        transitions.add(Transition.<State>from(State.ADVANCE)
                .when(() -> actionDone && !lineExhausted(), "actionDone && !lineExhausted()")
                .because("Next tile in line")
                .goTo(State.WALK_TO_TILE));

        transitions.add(Transition.<State>from(State.RESTOCK)
                .when(() -> actionDone && restockSucceeded, "actionDone && restockSucceeded")
                .because("Restock complete, replanning line")
                .goTo(State.PLAN_LINE));

        transitions.add(Transition.<State>from(State.RESTOCK)
                .when(() -> actionDone && !restockSucceeded, "actionDone && !restockSucceeded")
                .because("Bank restock failed")
                .goTo(State.STOPPED));

        transitions.add(Transition.<State>from(State.STOPPED)
                .when(this::suppliesRestored, "suppliesRestored()")
                .because("Tinderbox and logs available again")
                .goTo(State.PLAN_LINE));

        return transitions;
    }

    @Override
    protected void onState(State state) {
        switch (state) {
            case PLAN_LINE:
                doPlanLine();
                break;
            case WALK_TO_TILE:
                doWalkToTile();
                break;
            case LIGHT_LOG:
                doLightLog();
                break;
            case ADVANCE:
                doAdvance();
                break;
            case RESTOCK:
                doRestock();
                break;
            case STOPPED:
                doStoppedRetry();
                break;
        }
    }

    @Override
    protected void onTransition(State from, State to, String reason) {
        super.onTransition(from, to, reason);
        actionDone = false;
    }

    @Override
    protected State onError(State state, Exception e) {
        log.error("[BaropasGeFiremaker] Error in state {}: {}", state, e.getMessage(), e);
        return State.PLAN_LINE;
    }

    // --- State actions ---

    private void doPlanLine() {
        planNewLine();
        actionDone = true;
    }

    private void doWalkToTile() {
        if (atTarget()) {
            return;
        }
        boolean arrived = Rs2Walker.walkTo(currentTarget, 0);
        if (!arrived && !atTarget()) {
            walkFailures++;
            if (walkFailures >= MAX_WALK_FAILURES_PER_TILE) {
                log.warn("[BaropasGeFiremaker] Could not reach {} after {} attempts, skipping tile", currentTarget, walkFailures);
                advanceLineIndex();
                walkFailures = 0;
            }
        }
    }

    private void doLightLog() {
        walkFailures = 0;
        LogType logType = config.logType();

        if (cannotLightHere.getAndSet(false)) {
            log.info("[BaropasGeFiremaker] Firemaking not allowed on {}, excluding it from now on", currentTarget);
            noFireZones.add(currentTarget);
            persistNoFireZones();
            lightAttempts = 0;
            actionDone = true;
            return;
        }

        if (Rs2Player.isAnimating()) {
            return;
        }

        lightAttempts++;
        sleep(Rs2Random.reactionTime());

        int logsBefore = Rs2Inventory.count(logType.getItemId());
        boolean used = Rs2Inventory.combine(ItemID.TINDERBOX, logType.getItemId());

        boolean success = false;
        boolean failedRoll = false;
        if (used) {
            boolean started = sleepUntil(() ->
                    Rs2Player.isAnimating() || Rs2Inventory.count(logType.getItemId()) < logsBefore, 2400);
            if (started) {
                sleepUntil(() -> !Rs2Player.isAnimating(), 5000);
                success = Rs2Inventory.count(logType.getItemId()) < logsBefore;
                // Animation played but the log wasn't consumed: a genuine failed lighting roll,
                // not a click that never registered. Firemaking's success chance scales with
                // level vs. the log's requirement, so low-level accounts (e.g. normal logs at
                // level 1) fail far more often than the general firemaking antiban template
                // assumes. Pause like a player reading the "you fail to light..." message
                // instead of retrying at human-reaction speed.
                failedRoll = !success;
            }
        }

        if (success) {
            firesLit++;
            logsBurned++;
            lightAttempts = 0;
            if (config.useAntiban()) {
                Rs2Antiban.actionCooldown();
            }
            actionDone = true;
        } else if (lightAttempts >= MAX_LIGHT_ATTEMPTS_PER_TILE) {
            log.warn("[BaropasGeFiremaker] Giving up lighting {} after {} attempts, skipping tile", currentTarget, lightAttempts);
            lightAttempts = 0;
            actionDone = true;
        } else if (failedRoll) {
            sleep(Rs2Random.between(1000, 2200));
        }
        // else: stay in LIGHT_LOG, natural retry next tick
    }

    private void doAdvance() {
        advanceLineIndex();
        actionDone = true;
    }

    private void doRestock() {
        boolean success = restockFromBank(config.logType());
        restockSucceeded = success;
        if (!success) {
            if (logTypeUnavailable) {
                disablePlugin(lastRestockResult);
            } else {
                announceStop(lastRestockResult);
            }
        }
        actionDone = true;
    }

    /**
     * Periodically re-checks the bank while stopped, so restocking it is enough to resume - no
     * need to restart the plugin. Gated by a cooldown since each check is a real bank visit, not
     * a free read. Gives up outright (rather than continuing to retry) if the configured log type
     * simply isn't stocked, since that won't resolve itself no matter how many times it checks.
     */
    private void doStoppedRetry() {
        Microbot.status = "Baropa's GE Firemaker: stopped (" + lastRestockResult + ")";

        if (stoppedRetryAt != null && Instant.now().isBefore(stoppedRetryAt)) {
            return;
        }
        stoppedRetryAt = Instant.now().plusSeconds(STOPPED_RETRY_INTERVAL_SECONDS);

        if (restockFromBank(config.logType())) {
            log.info("[BaropasGeFiremaker] Bank restocked, resuming");
        } else if (logTypeUnavailable) {
            disablePlugin(lastRestockResult);
        }
    }

    /** Posts a game-message chatbox line rather than a blocking popup, so the client stays usable. */
    private void announceStop(String reason) {
        String message = "Baropa's GE Firemaker: " + reason + ". Stopping - checking the bank periodically; "
                + "no restart needed once it's restocked.";
        Microbot.getClientThread().invoke(() ->
                Microbot.getClient().addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, ""));
    }

    /**
     * Posts a game-message and fully disables the plugin, rather than leaving it checking the bank
     * forever for a log type it will never stock on its own.
     */
    private void disablePlugin(String reason) {
        String message = "Baropa's GE Firemaker: " + reason + ". Disabling - restock the bank with "
                + config.logType().getLogName() + " and re-enable to continue.";
        Microbot.getClientThread().invoke(() ->
                Microbot.getClient().addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, ""));

        Plugin plugin = Microbot.getPlugin(BaropasGeFiremakerPlugin.class.getName());
        PluginManager pluginManager = Microbot.getPluginManager();
        if (plugin == null || pluginManager == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                pluginManager.setPluginEnabled(plugin, false);
                pluginManager.stopPlugin(plugin);
            } catch (PluginInstantiationException e) {
                log.error("[BaropasGeFiremaker] Failed to disable plugin", e);
            }
        });
    }

    /**
     * Withdraws a tinderbox (if missing) and logs (if missing) from the bank in one visit,
     * forcing the "item" (unnoted) withdraw mode first. Logs bought via the Grand Exchange, or
     * banked while that mode was toggled to "note", would otherwise land in the inventory
     * noted and go unrecognised as usable logs.
     */
    private boolean restockFromBank(LogType logType) {
        logTypeUnavailable = false;
        boolean needTinderbox = missingTinderbox();
        boolean needLogs = outOfLogs();

        if (!Rs2Bank.isOpen()) {
            Rs2Bank.walkToBankAndUseBank(BankLocation.GRAND_EXCHANGE);
        }
        if (!Rs2Bank.isOpen()) {
            lastRestockResult = "Could not open the bank";
            return false;
        }

        Rs2Bank.setWithdrawAs(false);

        if (needTinderbox) {
            if (Rs2Bank.hasBankItem(ItemID.TINDERBOX, 1)) {
                Rs2Bank.withdrawX(ItemID.TINDERBOX, 1);
                sleepUntil(() -> Rs2Inventory.hasItem(ItemID.TINDERBOX), 3000);
            }
        }

        boolean bankHasLogs = Rs2Bank.hasBankItem(logType.getItemId(), 1);
        if (needLogs && bankHasLogs) {
            Rs2Bank.withdrawX(logType.getItemId(), config.restockAmount());
            sleepUntil(() -> Rs2Inventory.hasItem(logType.getItemId()), 3000);
        }

        Rs2Bank.closeBank();

        boolean hasTinderbox = !missingTinderbox();
        boolean hasLogs = !outOfLogs();

        if (hasTinderbox && hasLogs) {
            lastRestockResult = "Withdrew " + (needTinderbox ? "tinderbox and " : "") + logType.getLogName();
            return true;
        }
        if (!hasTinderbox) {
            lastRestockResult = "No tinderbox in inventory or bank";
        } else {
            lastRestockResult = "No " + logType.getLogName() + " in the bank";
            logTypeUnavailable = !bankHasLogs;
        }
        return false;
    }

    // --- Guards (pure reads, no side effects) ---

    private boolean missingTinderbox() {
        return !Rs2Inventory.hasItem(ItemID.TINDERBOX);
    }

    private boolean outOfLogs() {
        return !Rs2Inventory.hasItem(config.logType().getItemId());
    }

    private boolean atTarget() {
        WorldPoint here = Rs2Player.getWorldLocation();
        return here != null && currentTarget != null && here.equals(currentTarget);
    }

    private boolean suppliesRestored() {
        return !missingTinderbox() && !outOfLogs();
    }

    private boolean lineExhausted() {
        return currentLine == null || lineIndex >= currentLine.size();
    }

    // --- Line planning ---

    /**
     * Scans a window of rows around the Grand Exchange bank booth for the longest contiguous run
     * of walkable, fire-free tiles and commits to it as one straight line, east to west - the way
     * a real player eyeballs the courtyard for open space rather than working off a fixed grid.
     * Anchored to the booth (not the player's live position) so the search stays inside the
     * courtyard instead of drifting into unrelated walkable areas (e.g. the Clan Hall entrance).
     * Sticky: a real player who finds a good line keeps using it, since the previous pass's fires
     * have burned out by the time they've restocked and walked back. Only re-scans for a new row
     * when the preferred one can no longer produce a line that's still close to full length -
     * accepting any old scrap of a still-valid row would mean immediately re-splitting a row into
     * smaller and smaller pieces between its own not-yet-expired fires, never giving it a proper
     * rest before coming back. Falls back to a straight line west of the bank if nothing clear
     * enough is found anywhere.
     */
    private void planNewLine() {
        WorldPoint origin = BankLocation.GRAND_EXCHANGE.getWorldPoint();
        int maxLen = config.lineLength();
        int stickyThreshold = Math.max(MIN_LINE_LENGTH, maxLen - LINE_LENGTH_TOLERANCE);

        if (preferredRowY != null) {
            List<WorldPoint> run = longestRunOnRow(origin, preferredRowY, maxLen);
            if (run != null && run.size() >= stickyThreshold) {
                adoptLine(run, preferredRowY);
                return;
            }
        }

        List<List<WorldPoint>> candidates = new ArrayList<>();
        List<Integer> candidateRows = new ArrayList<>();
        int bestLength = 0;

        for (int dy = -ROW_SCAN_RADIUS; dy <= ROW_SCAN_RADIUS; dy++) {
            int y = origin.getY() + dy;
            List<WorldPoint> run = longestRunOnRow(origin, y, maxLen);
            if (run == null) continue;
            candidates.add(run);
            candidateRows.add(y);
            bestLength = Math.max(bestLength, run.size());
        }

        if (candidates.isEmpty() || bestLength < MIN_LINE_LENGTH) {
            List<WorldPoint> fallback = new ArrayList<>();
            for (int i = 0; i < maxLen; i++) {
                fallback.add(new WorldPoint(origin.getX() - i, origin.getY(), origin.getPlane()));
            }
            log.warn("[BaropasGeFiremaker] No clear line found near {}, falling back to a straight line west", origin);
            adoptLine(fallback, origin.getY());
            return;
        }

        // Pick the genuinely best line, like a player scanning for the most fires-per-trip would -
        // only spread across rows that are truly as good as the best (a tiny tolerance for
        // essentially-tied rows), never settle for a noticeably shorter one just for variety's
        // sake. That previously let it pick an 8-tile row when a 4-tiles-further row had a full
        // line available.
        int threshold = Math.max(MIN_LINE_LENGTH, bestLength - LINE_LENGTH_TOLERANCE);
        List<Integer> nearBestIndices = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).size() >= threshold) {
                nearBestIndices.add(i);
            }
        }
        int chosen = nearBestIndices.get(Rs2Random.betweenInclusive(0, nearBestIndices.size() - 1));
        adoptLine(candidates.get(chosen), candidateRows.get(chosen));
    }

    private void adoptLine(List<WorldPoint> line, int rowY) {
        currentLine = line;
        preferredRowY = rowY;
        lineIndex = 0;
        currentTarget = currentLine.get(0);
        log.info("[BaropasGeFiremaker] Planned a {}-tile line from {} to {}",
                currentLine.size(), currentLine.get(0), currentLine.get(currentLine.size() - 1));
    }

    /**
     * Finds the longest contiguous run of walkable, fire-free tiles on the given row, scanning
     * from the east edge of the search window inward and stopping once it has enough tiles for a
     * full line. Returns null if nothing usable exists.
     */
    private List<WorldPoint> longestRunOnRow(WorldPoint origin, int y, int maxLen) {
        int plane = origin.getPlane();
        List<WorldPoint> best = null;
        List<WorldPoint> run = new ArrayList<>();

        for (int x = origin.getX() + COL_SCAN_RADIUS; x >= origin.getX() - COL_SCAN_RADIUS; x--) {
            WorldPoint point = new WorldPoint(x, y, plane);
            boolean clear = Rs2Walker.isWalkableInCollisionMap(point) && !tileHasFire(point) && !noFireZones.contains(point)
                    && origin.distanceTo(point) > BANK_EXCLUSION_RADIUS;
            if (clear) {
                run.add(point);
                if (run.size() >= maxLen) {
                    return run;
                }
            } else {
                if (best == null || run.size() > best.size()) {
                    best = new ArrayList<>(run);
                }
                run.clear();
            }
        }
        if (best == null || run.size() > best.size()) {
            best = run;
        }
        return best.isEmpty() ? null : best;
    }

    /** Moves to the next tile in the planned line, skipping any that have since caught fire or been ruled out. */
    private void advanceLineIndex() {
        lineIndex++;
        while (currentLine != null && lineIndex < currentLine.size()
                && (tileHasFire(currentLine.get(lineIndex)) || noFireZones.contains(currentLine.get(lineIndex)))) {
            lineIndex++;
        }
        if (currentLine != null && lineIndex < currentLine.size()) {
            currentTarget = currentLine.get(lineIndex);
        }
    }

    private boolean tileHasFire(WorldPoint point) {
        return Microbot.getRs2TileObjectCache().query()
                .where(obj -> obj.getId() == ObjectID.FIRE && point.equals(obj.getWorldLocation()))
                .first() != null;
    }

    // --- No-fire zone persistence ---
    //
    // Tiles the game has rejected firemaking on (e.g. right by the Clan Hall entrance) are learned
    // from the "can't light a fire here" chat message, not hardcoded - GE's fire-restricted tiles
    // aren't precisely documented anywhere reliable, so guessing coordinates risks excluding tiles
    // that are actually fine (wasting good line length) or missing ones that aren't. Persisting
    // what's actually been observed means every session gets smarter than the last, and a tile is
    // never re-attempted once it's known to fail.

    private static final String NO_FIRE_ZONES_CONFIG_KEY = "noFireZones";

    private void loadPersistedNoFireZones() {
        noFireZones.clear();
        String stored = Microbot.getConfigManager() == null ? null
                : Microbot.getConfigManager().getConfiguration(BaropasGeFiremakerConfig.configGroup, NO_FIRE_ZONES_CONFIG_KEY);
        if (stored == null || stored.isEmpty()) return;

        for (String entry : stored.split(";")) {
            String[] parts = entry.split(",");
            if (parts.length != 3) continue;
            try {
                noFireZones.add(new WorldPoint(
                        Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
            } catch (NumberFormatException ignored) {
                // Corrupt entry - skip it rather than losing the rest of the learned set.
            }
        }
    }

    private void persistNoFireZones() {
        if (Microbot.getConfigManager() == null) return;
        String encoded = noFireZones.stream()
                .map(p -> p.getX() + "," + p.getY() + "," + p.getPlane())
                .collect(Collectors.joining(";"));
        Microbot.getConfigManager().setConfiguration(BaropasGeFiremakerConfig.configGroup, NO_FIRE_ZONES_CONFIG_KEY, encoded);
    }

    // --- Lifecycle ---

    public boolean run(BaropasGeFiremakerConfig config) {
        this.config = config;
        this.readyToStart = false;
        this.currentLine = null;
        this.preferredRowY = null;
        this.lineIndex = 0;
        this.currentTarget = null;
        this.walkFailures = 0;
        this.lightAttempts = 0;
        this.firesLit = 0;
        this.logsBurned = 0;
        this.lastRestockResult = "n/a";
        this.stoppedRetryAt = null;
        this.logTypeUnavailable = false;
        this.startXp = -1;
        this.startedAt = null;
        loadPersistedNoFireZones();

        // Matches the reference GEFiremakerScript's convention: this plugin walks itself
        // deliberately (short repeated hops along the line), so the framework's own run-toggling
        // would just fight with it. Restored on shutdown() since it's a process-global default.
        Microbot.enableAutoRunOn = false;

        if (config.useAntiban()) {
            // Reset first: a previous plugin's leftover antiban tuning (activity, play style,
            // cooldown chances) is otherwise inherited since Rs2AntibanSettings is process-global.
            Rs2Antiban.resetAntibanSettings();
            Rs2Antiban.antibanSetupTemplates.applyFiremakingSetup();
            Rs2Antiban.setActivity(Activity.GENERAL_FIREMAKING);
            // The template defaults these to false (a fixed profile) - the reference
            // GEFiremakerScript turns both on for more natural variation over a long session, and
            // there's no hang risk here (unlike micro-breaks below).
            Rs2AntibanSettings.dynamicActivity = true;
            Rs2AntibanSettings.dynamicIntensity = true;
            // Deliberately leave takeMicroBreaks at the template's default (false): triggering a
            // micro-break sets Rs2AntibanSettings.microBreakActive, which only the separate Break
            // Handler plugin resolves. Without it enabled, the flag never clears and the script
            // hangs indefinitely - confirmed live. actionCooldown() alone is self-contained.
            Rs2Antiban.activateAntiban();
        }

        // Plugin startUp() can run before the client has finished logging in (e.g. the plugin was
        // left enabled from a previous session and auto-starts at client boot). Wait for a real,
        // logged-in position before letting the state machine (and its first line-planning pass) run.
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;

                if (!readyToStart) {
                    if (Rs2Player.getWorldLocation() == null) return;
                    readyToStart = true;
                    startedAt = Instant.now();
                    Microbot.getClientThread().runOnClientThreadOptional(() ->
                            Microbot.getClient().getSkillExperience(Skill.FIREMAKING)).ifPresent(xp -> startXp = xp);
                    log.info("[BaropasGeFiremaker] Starting with {}", config.logType().getLogName());
                }

                step();
            } catch (Exception ex) {
                log.error("[BaropasGeFiremaker] Unexpected error in scheduled loop", ex);
            }
        }, 0, 300, TimeUnit.MILLISECONDS);

        return true;
    }

    @Override
    public void shutdown() {
        if (config != null && config.useAntiban()) {
            Rs2Antiban.deactivateAntiban();
            Rs2Antiban.resetAntibanSettings();
        }
        Microbot.enableAutoRunOn = true;
        super.shutdown();
    }

    public int getXpGained() {
        if (startXp < 0) return 0;
        Integer currentXp = Microbot.getClientThread()
                .runOnClientThreadOptional(() -> Microbot.getClient().getSkillExperience(Skill.FIREMAKING))
                .orElse(startXp);
        return Math.max(0, currentXp - startXp);
    }

    public long getRuntimeSeconds() {
        return startedAt == null ? 0 : java.time.Duration.between(startedAt, Instant.now()).getSeconds();
    }

    public boolean isWithdrawAsNote() {
        return Rs2Bank.hasWithdrawAsNote();
    }

    public int getLineLength() {
        return currentLine == null ? 0 : currentLine.size();
    }
}
