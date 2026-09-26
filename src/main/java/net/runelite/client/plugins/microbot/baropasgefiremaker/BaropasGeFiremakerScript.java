package net.runelite.client.plugins.microbot.baropasgefiremaker;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.statemachine.StateMachineScript;
import net.runelite.client.plugins.microbot.statemachine.Transition;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.enums.Activity;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    // OSRS blocks firemaking within a couple of tiles of a bank booth. Observed failures were at
    // distance 2 from the booth, so exclude that ring from the scan up front instead of relying
    // solely on learning it the hard way (via noFireZones) each session.
    private static final int BANK_EXCLUSION_RADIUS = 3;

    private BaropasGeFiremakerConfig config;
    private boolean readyToStart;

    // Line layout: a precomputed, ordered (east to west) run of walkable, fire-free tiles.
    @Getter
    private List<WorldPoint> currentLine;
    @Getter
    private int lineIndex;
    @Getter
    private WorldPoint currentTarget;
    @Getter
    private int walkFailures;
    @Getter
    private int lightAttempts;

    // Per-tick action flag, mirrors the StateMachineExampleScript pattern.
    private boolean actionDone;
    private boolean restockSucceeded;
    @Getter
    private String lastRestockResult = "n/a";

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
                Microbot.status = "Baropa's GE Firemaker: stopped (" + lastRestockResult + ")";
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
        if (used) {
            boolean started = sleepUntil(() ->
                    Rs2Player.isAnimating() || Rs2Inventory.count(logType.getItemId()) < logsBefore, 2400);
            if (started) {
                sleepUntil(() -> !Rs2Player.isAnimating(), 5000);
                success = Rs2Inventory.count(logType.getItemId()) < logsBefore;
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
            Microbot.showMessage("Baropa's GE Firemaker: " + lastRestockResult + ". Stopping - fix the bank and restart the plugin.");
        }
        actionDone = true;
    }

    /**
     * Withdraws a tinderbox (if missing) and logs (if missing) from the bank in one visit,
     * forcing the "item" (unnoted) withdraw mode first. Logs bought via the Grand Exchange, or
     * banked while that mode was toggled to "note", would otherwise land in the inventory
     * noted and go unrecognised as usable logs.
     */
    private boolean restockFromBank(LogType logType) {
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

        if (needLogs) {
            if (Rs2Bank.hasBankItem(logType.getItemId(), 1)) {
                Rs2Bank.withdrawX(logType.getItemId(), config.restockAmount());
                sleepUntil(() -> Rs2Inventory.hasItem(logType.getItemId()), 3000);
            }
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
     * Scans a window of nearby rows around the Grand Exchange bank booth for the longest contiguous run
     * of walkable, fire-free tiles and commits to it as one straight line, east to west - the way
     * a real player eyeballs the courtyard for open space rather than working off a fixed grid.
     * Anchored to the booth (not the player's live position) so the search stays inside the
     * courtyard instead of drifting into unrelated walkable areas (e.g. the Clan Hall entrance).
     * Falls back to a straight line west of the current tile if nothing clear enough is found.
     */
    private void planNewLine() {
        WorldPoint origin = BankLocation.GRAND_EXCHANGE.getWorldPoint();
        int maxLen = config.lineLength();

        // Collect every row tied for the longest run, not just the first one found, so a capped
        // line (very likely once maxLen is reached) doesn't always resolve to the same row -
        // a real player wouldn't always plant themselves on the exact same tiles either.
        List<List<WorldPoint>> tiedForBest = new ArrayList<>();
        int bestLength = 0;

        for (int dy = -ROW_SCAN_RADIUS; dy <= ROW_SCAN_RADIUS; dy++) {
            List<WorldPoint> run = longestRunOnRow(origin, origin.getY() + dy, maxLen);
            if (run == null) continue;
            if (run.size() > bestLength) {
                bestLength = run.size();
                tiedForBest.clear();
                tiedForBest.add(run);
            } else if (run.size() == bestLength) {
                tiedForBest.add(run);
            }
        }

        List<WorldPoint> best;
        if (tiedForBest.isEmpty() || bestLength < MIN_LINE_LENGTH) {
            best = new ArrayList<>();
            for (int i = 0; i < maxLen; i++) {
                best.add(new WorldPoint(origin.getX() - i, origin.getY(), origin.getPlane()));
            }
            log.warn("[BaropasGeFiremaker] No clear line found near {}, falling back to a straight line west", origin);
        } else {
            best = tiedForBest.get(Rs2Random.betweenInclusive(0, tiedForBest.size() - 1));
        }

        currentLine = best;
        lineIndex = 0;
        currentTarget = currentLine.get(0);
        log.info("[BaropasGeFiremaker] Planned a {}-tile line from {} to {}",
                currentLine.size(), currentLine.get(0), currentLine.get(currentLine.size() - 1));
    }

    /**
     * Finds the longest contiguous run of walkable, fire-free tiles on the given row, scanning
     * from the east edge of the search window inward. Returns null if nothing usable exists.
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

    // --- Lifecycle ---

    public boolean run(BaropasGeFiremakerConfig config) {
        this.config = config;
        this.readyToStart = false;
        this.currentLine = null;
        this.lineIndex = 0;
        this.currentTarget = null;
        this.walkFailures = 0;
        this.lightAttempts = 0;
        this.firesLit = 0;
        this.logsBurned = 0;
        this.lastRestockResult = "n/a";
        this.startXp = -1;
        this.startedAt = null;
        this.noFireZones.clear();

        if (config.useAntiban()) {
            Rs2Antiban.antibanSetupTemplates.applyFiremakingSetup();
            Rs2Antiban.setActivity(Activity.GENERAL_FIREMAKING);
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
        }
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
