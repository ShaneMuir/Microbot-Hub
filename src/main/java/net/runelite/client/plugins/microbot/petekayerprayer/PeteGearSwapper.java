package net.runelite.client.plugins.microbot.petekayerprayer;

import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.api.VarPlayer;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.ComponentID;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Times a weapon swap to land just before each of the player's attacks, so the attack goes in before
 * Pete's overhead prayer has caught up with the swap (it takes about 2 ticks). When Pete isn't frozen
 * the attack is instead a freeze: the mage set goes on and Ice Barrage is cast in the same tick.
 *
 * <p>Runs on the client thread from the game tick and sends the clicks as direct menu actions, because
 * scripted mouse input is dropped whenever the player is using the mouse - which is all the time here.
 */
final class PeteGearSwapper {
    private static final Logger log = LoggerFactory.getLogger(PeteGearSwapper.class);
    private static final int INVENTORY = 93;
    private static final int EQUIPMENT = 94;
    private static final int SPELLBOOK_VARBIT = 4070;
    private static final int ANCIENT_SPELLBOOK = 1;
    private static final int ICE_BARRAGE_LEVEL = 94;
    private static final String[] EQUIP_ACTIONS = {"wield", "wear", "equip"};

    // Animations of the player's own attacks (halberd, zcb, staff/ice barrage, Voidwaker) and of eating.
    // 428 is the staff's melee bash: a staff that isn't casting still attacks, so it has to count.
    // 440 is the real halberd swing (its hit lands 1 tick later, every time). 430 is a separate animation that
    // plays a tick before the 440, so counting it measured the cooldown from the wrong tick and swapped early.
    private static final Set<Integer> ATTACK_ANIMS = Set.of(440, 9168, 10092, 1979, 390, 11275, 428);
    // The Voidwaker special comes back after 4 ticks, not 5.
    private static final int SPEC_ANIM = 11275;
    private static final int SPEC_TICKS = 4;
    // The minimap special attack orb; the click (CC_OP, identifier 1, "Use" / "Special Attack") is copied from a real one.
    private static final int SPEC_ORB_WIDGET = 10485796;
    // From the equip tick, allow the next attack to come around (up to 5 ticks) plus slack.
    private static final int SPEC_WAIT_TICKS = 7;
    // What the player plays when the barrage is actually cast (10092 with the Staff of the Dead).
    private static final Set<Integer> CAST_ANIMS = Set.of(1979, 10092);
    // Halberd, zcb and staff spells are all 5-tick weapons.
    private static final int ATTACK_TICKS = 5;
    // Without an attack for this long the cadence is stale and the swap falls back to reacting.
    private static final int CADENCE_STALE_TICKS = 12;

    // Freeze spotanims on an actor: bind, snare, entangle, ice rush/burst/blitz/barrage.
    private static final Set<Integer> FREEZE_SPOTANIMS = Set.of(179, 180, 181, 361, 363, 366, 369);
    // The halberd reaches 2 tiles.
    private static final int MELEE_REACH = 2;
    /** Between swaps while Pete is on the player's tile, about one attack cycle. */
    private static final long STANDBY_SWAP_COOLDOWN_MS = 3000;
    // A freeze runs about 29 ticks from the message to the thaw warning, and a little past it.
    private static final int MAX_FREEZE_TICKS = 40;
    private static final int THAW_TICKS = 3;
    // A queued manual cast fires when the weapon's cooldown is up, so allow a full attack cycle plus slack.
    private static final int CAST_WAIT_TICKS = 5;
    // Ice Barrage freezes for about 20 seconds.
    private static final int PETE_FREEZE_TICKS = 32;
    private static final int FREEZE_RETRY_TICKS = 8;
    private static final int FREEZE_IMMUNITY_TICKS = 4;
    private static final int FREEZE_BACKOFF_TICKS = 30;
    private static final long PRAYER_RESEND_MS = 900;

    private final Client client;
    private long lastSwapMs;
    private long lastStandbySwapMs;
    private long lastPrayerMs;
    private long specSinceMs;

    private int lastAttackTick;
    private int nextAttackTick;
    private boolean cycleActed;

    private Set<Integer> lastLoggedSpotAnims = new HashSet<>();
    private int lastFreezeCastTick;
    private int frozenEndTick;
    private boolean wasFrozen;
    private int failedCasts;
    private int castCheckTick;
    private int pendingCastTick;
    private boolean castAnimSeen;
    private int frozenUntilTick;
    private int awaitingCastUntilTick;
    private int specAssistUntilTick;
    private boolean restPending;
    private boolean specAnimSeen;
    private boolean suppressSpecHold;
    private int peteFrozenUntilTick;
    private int lastLowMagicLogTick;

    PeteGearSwapper(Client client) {
        this.client = client;
    }

    /** Pete's overhead prayer as an icon index (0 melee, 1 ranged, 2 magic), or -1 for none. */
    static int protectedIcon(NPC pete) {
        short[] sprites = pete.getOverheadSpriteIds();
        if (sprites == null) {
            return -1;
        }
        for (short sprite : sprites) {
            if (sprite != -1) {
                return sprite;
            }
        }
        return -1;
    }

    /** Whether an animation is one of the player's own attacks (or a cast). */
    static boolean isAttackAnimation(int animation) {
        return ATTACK_ANIMS.contains(animation);
    }

    /** Called for the local player's animation changes, to learn when the next attack is due. */
    void onPlayerAnimation(int animation) {
        int tick = client.getTickCount();
        if (CAST_ANIMS.contains(animation)) {
            castAnimSeen = true;
        }
        if (animation == SPEC_ANIM) {
            specAnimSeen = true;
        }
        // Eating doesn't move the next attack when it's eaten mid-cooldown (checked against a real fight),
        // so only attack animations count. One attack can play two animations a tick apart (the halberd's
        // 430 then 440), so a second one right after the first isn't a new attack.
        if (ATTACK_ANIMS.contains(animation) && (lastAttackTick == 0 || tick - lastAttackTick >= 3)) {
            lastAttackTick = tick;
            nextAttackTick = tick + (animation == SPEC_ANIM ? SPEC_TICKS : ATTACK_TICKS);
            cycleActed = false;
            restPending = true;
        }
    }
    void onGameTick(NPC pete, PeteKayerPrayerConfig config) {
        List<String> melee = PeteSwapPlanner.parseItems(config.meleeGear());
        List<String> ranged = PeteSwapPlanner.parseItems(config.rangedGear());
        List<String> magic = PeteSwapPlanner.parseItems(config.magicGear());
        ItemContainer equipment = client.getItemContainer(EQUIPMENT);
        ItemContainer inventory = client.getItemContainer(INVENTORY);
        if (equipment == null || inventory == null) {
            return;
        }

        long now = System.currentTimeMillis();
        int tick = client.getTickCount();
        trackFreeze(pete, tick);

        if (holdingSpecWeapon(equipment, config, now)) {
            logCycleSkip(tick, "holding the special weapon");
            // The special is a melee attack, so it gets Piety.
            if (config.swapOffensivePrayer()) {
                ensureOffensivePrayer(PeteSwapPlanner.Style.MELEE, now);
            }
            return;
        }

        // An assisted special is in flight: the Voidwaker is on and the bar is armed, so just wait for the swing.
        if (specAssistUntilTick > 0) {
            if (specAnimSeen) {
                specAssistUntilTick = 0; // fired; the normal cycle takes the Voidwaker off before the next attack
            } else if (tick > specAssistUntilTick) {
                specAssistUntilTick = 0;
                log.warn("[PeteKayerPrayer] special did not fire in {} ticks, going back to the weapons", SPEC_WAIT_TICKS);
                if (client.getVarpValue(VarPlayer.SPECIAL_ATTACK_ENABLED) == 1) {
                    clickSpecOrb();
                }
                cycleActed = false;
                nextAttackTick = tick + 1;
            } else {
                logCycleSkip(tick, "waiting for the special to fire");
                if (config.swapOffensivePrayer()) {
                    ensureOffensivePrayer(PeteSwapPlanner.Style.MELEE, now);
                }
                return;
            }
        }

        // A manual cast waits for the weapon's attack cooldown and then fires, so once it has been sent leave
        // the weapon and prayers alone until the barrage animation shows - swapping away early cancels it.
        // If it never fires, give up on this freeze, back off, and let the next swap take us off the staff.
        if (awaitingCastUntilTick > 0) {
            if (castAnimSeen) {
                // The barrage is away: straight back into the tank gear. Mage gear is only for the cast tick.
                awaitingCastUntilTick = 0;
                leaveMageGear(config, pete, melee, ranged, magic, equipment, inventory, now, "barrage cast");
            } else if (tick > awaitingCastUntilTick || (tick >= awaitingCastUntilTick - CAST_WAIT_TICKS + 2 && castBlocked(pete))) {
                // Out of time, or Pete has gone under me (a cast at a target on my own tile is refused with
                // "I can't reach that!"): stop waiting rather than sit in mage gear bashing.
                awaitingCastUntilTick = 0;
                failedCasts = config.autoCast() ? 2 : 1;
                nextAttackTick = tick + 1;
                cycleActed = true; // leaveMageGear below already swaps off the staff this tick
                log.warn("[PeteKayerPrayer] no barrage in {} ticks, going back to the weapons and tank gear", CAST_WAIT_TICKS);
                leaveMageGear(config, pete, melee, ranged, magic, equipment, inventory, now, "no cast");
            } else {
                logCycleSkip(tick, "waiting for the barrage to fire");
                if (config.swapOffensivePrayer()) {
                    ensureOffensivePrayer(PeteSwapPlanner.Style.MAGIC, now);
                }
                if (config.autoCast()) {
                    resendCast(pete, equipment, magic, tick);
                }
                return;
            }
        }

        PeteSwapPlanner.Style current = currentStyle(equipment, melee, ranged, magic);
        PeteSwapPlanner.Style effective = current;

        // Pete on the player's tile: the cycle below needs attacks to time from and there are none.
        if (castBlocked(pete)) {
            standbyOnTop(config, pete, current, melee, ranged, magic, equipment, inventory, now);
            return;
        }

        // Everything runs off the player's own attack cadence. With no attack for a while (the fight is
        // over, or the player has stopped) there is nothing to time, so do nothing rather than react.
        boolean timed = lastAttackTick > 0 && tick - lastAttackTick <= CADENCE_STALE_TICKS;
        if (!timed) {
            if (lastAttackTick > 0 && tick - lastAttackTick == CADENCE_STALE_TICKS + 1) {
                logCycleSkip(tick, "no attack for " + (tick - lastAttackTick) + " ticks, waiting for the next one");
            }
            return;
        }

        // Tank between swings: the halberd (or the special weapon) is only in hand for the attack itself. As soon as the
        // swing has been seen, straight back to the tankiest set for the cooldown. The next attack then picks its weapon
        // from the prayer Pete has up at that moment.
        if (restPending) {
            restPending = false;
            if (config.tankBetweenSwings() && awaitingCastUntilTick == 0) {
                List<String> sent = equipSet(ranged, equipment, inventory);
                if (!sent.isEmpty()) {
                    log.info("[PeteKayerPrayer] back to the tank set after the attack: {}", sent);
                }
            }
        }

        if (cycleActed && nextAttackTick - tick == 1) {
            logCycleSkip(tick, "already acted this cycle");
        }

        // A freeze goes out two ticks before the attack is due, not one: the spell is queued by the click and
        // fires when the cooldown is up, and the attack has been seen coming a tick earlier than predicted, in
        // which case a one-tick lead lets the staff's own bash use the attack first. The prayer goes first, then
        // the staff set, then the manual cast, all in this tick (the clicks are processed in order).
        if (!cycleActed && nextAttackTick - tick <= 2 && config.autoFreeze()
                && canFreeze(pete, tick, magic, equipment, inventory) && !castBlocked(pete)) {
            cycleActed = true;
            if (config.swapOffensivePrayer()) {
                ensureOffensivePrayer(PeteSwapPlanner.Style.MAGIC, now);
            }
            if (config.autoCast()) {
                freeze(pete, tick, magic, equipment, inventory);
            } else {
                // The player casts: put the mage set on and leave the click to them (Cast Ice Barrage is the
                // left-click on Pete while the staff is on).
                List<String> sent = equipSet(magic, equipment, inventory);
                lastFreezeCastTick = tick;
                castCheckTick = tick + 4;
                castAnimSeen = false;
                log.info("[PeteKayerPrayer] mage gear on for a barrage: {} (attack in {} ticks) - waiting for your click", sent,
                        nextAttackTick - tick);
            }
            awaitingCastUntilTick = tick + CAST_WAIT_TICKS;
            effective = PeteSwapPlanner.Style.MAGIC;
        }

        // Act once per attack cycle, on the tick before the attack is due.
        if (!cycleActed && nextAttackTick - tick <= 1) {
            cycleActed = true;
            int head = protectedIcon(pete);
            if (specReady(config, pete, head, equipment, inventory)) {
                startSpec(config, equipment, inventory, now);
                effective = PeteSwapPlanner.Style.MELEE;
            } else {
                // Read the overhead prayer Pete has up right now and attack with a style he isn't praying - never
                // onto one that can't reach him (frozen out of range, a halberd can't attack at all). The weapon
                // already in hand counts: after a swing the tank set puts the crossbow back in hand, so a
                // ranged attack costs no swap at all.
                boolean meleeReachable = meleeReachable(pete);
                PeteSwapPlanner.Style target = PeteSwapPlanner.choose(null, head,
                        PeteSwapPlanner.parseOrder(config.swapOrder()),
                        style -> style != PeteSwapPlanner.Style.MAGIC
                                && (style != PeteSwapPlanner.Style.MELEE || meleeReachable)
                                && styleAvailable(gear(style, melee, ranged, magic), equipment, inventory),
                        now, 0, 0);
                if (target == null) {
                    log.info("[PeteKayerPrayer] cycle: nothing to attack with (Pete protects {}, melee reachable {})",
                            PeteSwapPlanner.Style.fromIcon(head), meleeReachable);
                } else if (target == current) {
                    log.info("[PeteKayerPrayer] attack with {} already in hand (Pete protects {})", target,
                            PeteSwapPlanner.Style.fromIcon(head));
                    effective = target;
                } else {
                    if (config.swapOffensivePrayer()) {
                        ensureOffensivePrayer(target, now); // prayer first, then the gear
                    }
                    swapTo(target, current, pete, melee, ranged, magic, equipment, inventory);
                    effective = target;
                }
            }
        }
        // Keep the offensive prayer matching the weapon in hand (or the one just ordered, which won't
        // show as equipped until next tick).
        if (config.swapOffensivePrayer() && effective != null) {
            ensureOffensivePrayer(effective, now);
        }
    }

    /**
     * A frozen player can't walk into range, so a halberd only works while Pete is within its reach.
     * The freeze is followed through the game's own chat messages, which are reliable (the freeze graphic
     * on the player wasn't).
     */
    private boolean meleeReachable(NPC pete) {
        net.runelite.api.Player me = client.getLocalPlayer();
        if (me == null || pete.getWorldLocation().distanceTo(me.getWorldLocation()) <= MELEE_REACH) {
            return true;
        }
        if (client.getTickCount() < frozenUntilTick) {
            return false;
        }
        for (int id : FREEZE_SPOTANIMS) {
            if (me.hasSpotAnim(id)) {
                return false;
            }
        }
        return true;
    }

    /** From the game messages "You have been frozen!" and "The freeze cast upon you is beginning to thaw." */
    void onGameMessage(String message) {
        int tick = client.getTickCount();
        if (awaitingCastUntilTick > 0) {
            log.info("[PeteKayerPrayer] game message while waiting for the cast: {}", message);
        }
        if (message.contains("You have been frozen")) {
            frozenUntilTick = tick + MAX_FREEZE_TICKS;
        } else if (message.contains("beginning to thaw")) {
            frozenUntilTick = tick + THAW_TICKS;
        } else if (message.contains("Magic level of") && awaitingCastUntilTick > 0) {
            // The game refused the cast for the level: stop waiting on it and go back to the weapons.
            awaitingCastUntilTick = 0;
            failedCasts = 2;
            cycleActed = false;
            nextAttackTick = tick + 1;
            log.warn("[PeteKayerPrayer] freeze refused: {}", message);
        }
    }

    /** The fight just ended: turn off the offensive prayers this plugin was switching, so they aren't left on. */
    void onFightEnded(PeteKayerPrayerConfig config) {
        if (!config.swapOffensivePrayer()) {
            return;
        }
        for (Rs2PrayerEnum prayer : new Rs2PrayerEnum[]{Rs2PrayerEnum.PIETY, Rs2PrayerEnum.RIGOUR, Rs2PrayerEnum.AUGURY}) {
            if (client.getVarbitValue(prayer.getVarbit()) == 1) {
                client.menuAction(-1, prayer.getIndex(), MenuAction.CC_OP, 1, -1, "Deactivate", "Deactivate");
                log.info("[PeteKayerPrayer] fight over, turning {} off", prayer);
            }
        }
    }

    private int lastSkipLogTick;

    /** Why a cycle went by without a swap, at most once per tick, so skipped cycles can be explained. */
    private void logCycleSkip(int tick, String reason) {
        if (tick != lastSkipLogTick) {
            lastSkipLogTick = tick;
            log.info("[PeteKayerPrayer] cycle skipped at tick {}: {}", tick, reason);
        }
    }

    /** The fight isn't on: forget the attack cadence and any freeze state so the next fight starts clean. */
    void reset() {
        lastAttackTick = 0;
        nextAttackTick = 0;
        cycleActed = false;
        specSinceMs = 0;
        lastSwapMs = 0;
        lastStandbySwapMs = 0;
        lastFreezeCastTick = 0;
        frozenEndTick = 0;
        wasFrozen = false;
        failedCasts = 0;
        castCheckTick = 0;
        pendingCastTick = 0;
        castAnimSeen = false;
        frozenUntilTick = 0;
        awaitingCastUntilTick = 0;
        peteFrozenUntilTick = 0;
        specAssistUntilTick = 0;
        suppressSpecHold = false;
        restPending = false;
    }

    /** The game tick came and no Pete NPC was around. */
    void onPeteMissing() {
        int tick = client.getTickCount();
        if (nextAttackTick - tick == 1) {
            logCycleSkip(tick, "Pete not found");
        }
    }

    private void swapTo(PeteSwapPlanner.Style target, PeteSwapPlanner.Style current, NPC pete, List<String> melee,
                        List<String> ranged, List<String> magic, ItemContainer equipment, ItemContainer inventory) {
        List<String> sent = equipSet(gear(target, melee, ranged, magic), equipment, inventory);
        log.info("[PeteKayerPrayer] swap {} -> {} (Pete protects {}, attack in {} ticks): {}", current, target,
                PeteSwapPlanner.Style.fromIcon(protectedIcon(pete)), nextAttackTick - client.getTickCount(), sent);
    }

    // ---- freeze ----------------------------------------------------------------------------------

    private Set<Integer> spotAnims(NPC npc) {
        Set<Integer> ids = new HashSet<>();
        for (ActorSpotAnim anim : npc.getSpotAnims()) {
            ids.add(anim.getId());
        }
        return ids;
    }

    private boolean isFrozen(Set<Integer> spotAnims) {
        for (int id : spotAnims) {
            if (FREEZE_SPOTANIMS.contains(id)) {
                return true;
            }
        }
        return false;
    }

    /** Follows Pete's freeze state and learns the freeze graphic from what appears after a cast. */
    private void trackFreeze(NPC pete, int tick) {
        Set<Integer> now = spotAnims(pete);
        if (!now.equals(lastLoggedSpotAnims)) {
            log.info("[PeteKayerPrayer] Pete spotanims {}", now);
            lastLoggedSpotAnims = now;
        }

        if (castCheckTick > 0 && tick > castCheckTick) {
            castCheckTick = 0;
            if (isFrozen(now)) {
                failedCasts = 0;
            } else {
                failedCasts++;
                log.info("[PeteKayerPrayer] freeze did not take ({} in a row)", failedCasts);
            }
        }

        boolean frozen = isFrozen(now);
        if (frozen && !wasFrozen) {
            // The freeze graphic fades after a few seconds but the freeze itself runs about 20 seconds, so
            // time it from when it first shows rather than from how long the graphic stays up.
            peteFrozenUntilTick = tick + PETE_FREEZE_TICKS;
            log.info("[PeteKayerPrayer] Pete frozen, no new freeze for {} ticks", PETE_FREEZE_TICKS);
        }
        if (wasFrozen && !frozen) {
            frozenEndTick = tick;
        }
        wasFrozen = frozen;
    }

    private boolean canFreeze(NPC pete, int tick, List<String> magic, ItemContainer equipment, ItemContainer inventory) {
        if (magic.isEmpty() || wasFrozen || tick < peteFrozenUntilTick) {
            return false; // already frozen (the graphic has faded but the freeze hasn't)
        }
        if (client.getVarbitValue(SPELLBOOK_VARBIT) != ANCIENT_SPELLBOOK) {
            return false;
        }
        // Ice Barrage needs 94 Magic, and a Saradomin brew drains Magic along with Attack, Strength and Ranged,
        // so a few brews can put it under 94 and every cast is then refused with a chat message.
        int magicLevel = client.getBoostedSkillLevel(Skill.MAGIC);
        if (magicLevel < ICE_BARRAGE_LEVEL) {
            if (tick - lastLowMagicLogTick >= 30) {
                lastLowMagicLogTick = tick;
                log.info("[PeteKayerPrayer] freeze skipped: Magic is {} and Ice Barrage needs {} (a brew drained it?)", magicLevel,
                        ICE_BARRAGE_LEVEL);
            }
            return false;
        }
        if (client.getWidget(InterfaceID.MagicSpellbook.ICE_BARRAGE) == null) {
            return false; // spellbook not loaded yet: open the magic tab once
        }
        if (!isWearing(equipment, magic.get(0)) && slotOf(inventory, magic.get(0)) == -1) {
            return false;
        }
        int wait = failedCasts >= 2 ? FREEZE_BACKOFF_TICKS : FREEZE_RETRY_TICKS;
        return tick - lastFreezeCastTick >= wait && tick - frozenEndTick >= FREEZE_IMMUNITY_TICKS;
    }

    /**
     * Whether to use the Voidwaker special on this attack: the assist is on, there is enough energy, the
     * weapon is to hand, Pete isn't praying Magic (the special's damage is magic, so his prayer blocks it), and
     * he is in melee reach right now: beside the player on a side, not diagonal, not on top of them, not out of
     * reach of a frozen player.
     */
    private boolean specReady(PeteKayerPrayerConfig config, NPC pete, int head, ItemContainer equipment, ItemContainer inventory) {
        String spec = config.specWeapon() == null ? "" : config.specWeapon().trim();
        if (!config.specAssist() || spec.isEmpty()) {
            return false;
        }
        if (head == PeteSwapPlanner.Style.MAGIC.getIcon()) {
            log.debug("[PeteKayerPrayer] no special: Pete is praying Magic");
            return false;
        }
        if (client.getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT) < config.specEnergyPercent() * 10) {
            return false;
        }
        if (!isWearing(equipment, spec) && slotOf(inventory, spec) == -1) {
            return false;
        }
        net.runelite.api.Player me = client.getLocalPlayer();
        return me != null && pete.getWorldArea().isInMeleeDistance(me.getWorldArea());
    }

    /**
     * Pete is on the player's tile (he death-dots there between attacks): nothing can be attacked, so there are no
     * attack animations to time a cycle from. Keep the weapon in hand off whatever he is praying anyway, one swap
     * per few ticks, so the first hit after he steps off isn't onto his prayer.
     */
    private void standbyOnTop(PeteKayerPrayerConfig config, NPC pete, PeteSwapPlanner.Style current, List<String> melee,
                              List<String> ranged, List<String> magic, ItemContainer equipment, ItemContainer inventory, long now) {
        if (restPending) {
            restPending = false;
            if (config.tankBetweenSwings() && !equipSet(ranged, equipment, inventory).isEmpty()) {
                return; // the tank set is going on; the equipment isn't updated until next tick
            }
        }
        int head = protectedIcon(pete);
        PeteSwapPlanner.Style target = PeteSwapPlanner.choose(current, head, PeteSwapPlanner.parseOrder(config.swapOrder()),
                style -> style != PeteSwapPlanner.Style.MAGIC && styleAvailable(gear(style, melee, ranged, magic), equipment, inventory),
                now, lastStandbySwapMs, STANDBY_SWAP_COOLDOWN_MS);
        if (target == null) {
            return;
        }
        lastStandbySwapMs = now;
        log.debug("[PeteKayerPrayer] Pete is on top of me praying {}: {} -> {}", PeteSwapPlanner.Style.fromIcon(head), current, target);
        if (config.swapOffensivePrayer()) {
            ensureOffensivePrayer(target, now); // prayer first, then the gear
        }
        swapTo(target, current, pete, melee, ranged, magic, equipment, inventory);
    }

    /** Piety first, then the Voidwaker, then the special bar - all in this tick, so the next attack is the special. */
    private void startSpec(PeteKayerPrayerConfig config, ItemContainer equipment, ItemContainer inventory, long now) {
        String spec = config.specWeapon().trim();
        if (config.swapOffensivePrayer()) {
            ensureOffensivePrayer(PeteSwapPlanner.Style.MELEE, now);
        }
        boolean wield = !isWearing(equipment, spec) && wield(inventory, spec);
        boolean arm = client.getVarpValue(VarPlayer.SPECIAL_ATTACK_ENABLED) != 1;
        if (arm) {
            clickSpecOrb();
        }
        specAssistUntilTick = client.getTickCount() + SPEC_WAIT_TICKS;
        specAnimSeen = false;
        suppressSpecHold = true;
        log.info("[PeteKayerPrayer] special: {} (wield sent {}, bar armed by click {}, energy {}%, attack in {} ticks)", spec, wield, arm,
                client.getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT) / 10, nextAttackTick - client.getTickCount());
    }

    private void clickSpecOrb() {
        client.menuAction(-1, SPEC_ORB_WIDGET, MenuAction.CC_OP, 1, -1, "Use", "<col=ff9040>Special Attack</col>");
    }

    /** Whether the staff of the mage set is in hand, so a click on Pete should be a cast. */
    boolean staffEquipped(PeteKayerPrayerConfig config) {
        List<String> magic = PeteSwapPlanner.parseItems(config.magicGear());
        ItemContainer equipment = client.getItemContainer(EQUIPMENT);
        return !magic.isEmpty() && equipment != null && isWearing(equipment, magic.get(0));
    }

    /**
     * Whether a click on Pete should be a cast right now: only inside the mage-gear window, with the staff in
     * hand. Once the barrage is away the window closes, so spam-clicking Pete goes back to being a normal
     * attack instead of casting again in tank gear.
     */
    boolean castWindowOpen(PeteKayerPrayerConfig config) {
        return awaitingCastUntilTick > 0 && staffEquipped(config);
    }

    /** The player clicked "Cast Ice Barrage" on Pete: send the same two clicks a manual cast does. */
    void castFromClick(NPC pete) {
        boolean selected = sendCastClicks(pete);
        net.runelite.api.Player me = client.getLocalPlayer();
        log.info("[PeteKayerPrayer] click-cast on {} (selected={}, magic {}/{}, Pete {} tiles away)", pete.getName(), selected,
                client.getBoostedSkillLevel(Skill.MAGIC), client.getRealSkillLevel(Skill.MAGIC),
                me == null ? -1 : pete.getWorldLocation().distanceTo(me.getWorldLocation()));
    }

    /**
     * Out of the mage gear the moment the barrage is away: the tank armour goes back on and the staff comes
     * out, in the same tick. Swapping to a normal weapon now doesn't cost the free hit - on the tick before
     * the next attack the plugin moves off whatever is in hand anyway.
     */
    private void leaveMageGear(PeteKayerPrayerConfig config, NPC pete, List<String> melee, List<String> ranged, List<String> magic,
                               ItemContainer equipment, ItemContainer inventory, long now, String why) {
        PeteSwapPlanner.Style target = PeteSwapPlanner.choose(null, -1, PeteSwapPlanner.parseOrder(config.swapOrder()),
                style -> style != PeteSwapPlanner.Style.MAGIC && canEquip(gear(style, melee, ranged, magic), inventory),
                now, 0, 0);
        if (target != null) {
            if (config.swapOffensivePrayer()) {
                ensureOffensivePrayer(target, now); // prayer before gear
            }
            List<String> sent = equipSet(gear(target, melee, ranged, magic), equipment, inventory);
            log.info("[PeteKayerPrayer] out of mage gear after {}: {} ({})", why, sent, target);
        }
        returnToTank(config, equipment, inventory, why);
    }

    /** Back into the tank gear (the armour of the normal sets) as soon as the barrage is done. */
    private void returnToTank(PeteKayerPrayerConfig config, ItemContainer equipment, ItemContainer inventory, String why) {
        List<String> tank = PeteSwapPlanner.parseItems(config.tankGear());
        List<String> sent = equipSet(tank, equipment, inventory);
        if (!sent.isEmpty()) {
            log.info("[PeteKayerPrayer] back to tank gear after {}: {}", why, sent);
        }
    }

    /** One-tick fallback: equip the staff set and cast in the same tick. */
    private void freeze(NPC pete, int tick, List<String> magic, ItemContainer equipment, ItemContainer inventory) {
        List<String> sent = equipSet(magic, equipment, inventory);
        log.info("[PeteKayerPrayer] freeze (one tick): {}", sent);
        castIceBarrage(pete, tick);
    }

    /**
     * Pete death-dots - he stands on the player's tile between attacks - and nothing can be cast at
     * (or attacked on) a target underneath you, so a freeze can't go out while he's there.
     */
    private boolean castBlocked(NPC pete) {
        net.runelite.api.Player me = client.getLocalPlayer();
        return me == null || pete.getWorldLocation().distanceTo(me.getWorldLocation()) < 1;
    }

    /**
     * The two clicks of a manual cast: select the spell, then cast it on Pete. Copied from a real manual
     * cast (the spell select carries identifier 0 and the cast on the NPC is a "Cast" option), and the same
     * pair Left-Click Cast sends.
     */
    private boolean sendCastClicks(NPC pete) {
        client.menuAction(-1, InterfaceID.MagicSpellbook.ICE_BARRAGE, MenuAction.WIDGET_TARGET, 0, -1, "Cast", "Ice Barrage");
        boolean selected = client.isWidgetSelected();
        client.menuAction(0, 0, MenuAction.WIDGET_TARGET_ON_NPC, pete.getIndex(), -1, "Cast", "Ice Barrage");
        return selected;
    }

    /**
     * Left-Click Cast works because the player clicks Pete over and over, so a cast that gets dropped is
     * simply sent again. A single send isn't enough - it can land just as the staff's own bash is used - so
     * while the barrage hasn't fired the same two clicks go out every tick.
     */
    private void resendCast(NPC pete, ItemContainer equipment, List<String> magic, int tick) {
        if (magic.isEmpty() || !isWearing(equipment, magic.get(0)) || castBlocked(pete)) {
            return;
        }
        sendCastClicks(pete);
        log.info("[PeteKayerPrayer] ice barrage cast re-sent at tick {}", tick);
    }

    private void castIceBarrage(NPC pete, int tick) {
        boolean selected = sendCastClicks(pete);
        net.runelite.api.Player me = client.getLocalPlayer();
        log.info("[PeteKayerPrayer] ice barrage cast on {} (selected={}, attack in {} ticks, magic {}/{}, Pete {} tiles away, "
                        + "frozen {}, spellbook {})", pete.getName(), selected, nextAttackTick - tick,
                client.getBoostedSkillLevel(Skill.MAGIC), client.getRealSkillLevel(Skill.MAGIC),
                me == null ? -1 : pete.getWorldLocation().distanceTo(me.getWorldLocation()),
                client.getTickCount() < frozenUntilTick, client.getVarbitValue(SPELLBOOK_VARBIT));
        lastFreezeCastTick = tick;
        castCheckTick = tick + 4;
        pendingCastTick = tick;
        castAnimSeen = false;
    }

    // ---- special weapon, gear and prayer ---------------------------------------------------------

    /**
     * Stays out of the way while the special-attack weapon is wielded, so a spec can be fired when
     * wanted. The hold runs from the moment the weapon is first seen and then the swapping resumes.
     */
    private boolean holdingSpecWeapon(ItemContainer equipment, PeteKayerPrayerConfig config, long now) {
        String spec = config.specWeapon() == null ? "" : config.specWeapon().trim();
        Item weapon = equipment.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
        boolean wielding = !spec.isEmpty() && weapon != null && weapon.getId() >= 0
                && spec.equalsIgnoreCase(client.getItemDefinition(weapon.getId()).getName());
        if (!wielding) {
            specSinceMs = 0;
            suppressSpecHold = false;
            return false;
        }
        if (suppressSpecHold) {
            return false; // the plugin put it on for its own special, so no hold - it swaps off afterwards
        }
        if (specSinceMs == 0) {
            specSinceMs = now;
            log.info("[PeteKayerPrayer] {} wielded, holding off swaps for {}ms", spec, config.specHoldMs());
        }
        return now - specSinceMs < config.specHoldMs();
    }

    private static Rs2PrayerEnum offensivePrayer(PeteSwapPlanner.Style style) {
        switch (style) {
            case MELEE:
                return Rs2PrayerEnum.PIETY;
            case RANGED:
                return Rs2PrayerEnum.RIGOUR;
            default:
                return Rs2PrayerEnum.AUGURY;
        }
    }

    /** Piety, Rigour and Augury exclude each other, so turning the right one on turns the old one off. */
    private void ensureOffensivePrayer(PeteSwapPlanner.Style style, long now) {
        Rs2PrayerEnum prayer = offensivePrayer(style);
        if (client.getVarbitValue(prayer.getVarbit()) == 1
                || client.getBoostedSkillLevel(Skill.PRAYER) <= 0
                || now - lastPrayerMs < PRAYER_RESEND_MS) {
            return;
        }
        lastPrayerMs = now;
        client.menuAction(-1, prayer.getIndex(), MenuAction.CC_OP, 1, -1, "Activate", "Activate");
        log.info("[PeteKayerPrayer] offensive prayer -> {}", prayer);
    }

    private static List<String> gear(PeteSwapPlanner.Style style, List<String> melee, List<String> ranged, List<String> magic) {
        switch (style) {
            case MELEE:
                return melee;
            case RANGED:
                return ranged;
            default:
                return magic;
        }
    }

    /** The style of the wielded weapon, taken as the first item of each configured set. */
    private PeteSwapPlanner.Style currentStyle(ItemContainer equipment, List<String> melee, List<String> ranged, List<String> magic) {
        Item weapon = equipment.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
        if (weapon == null || weapon.getId() < 0) {
            return null;
        }
        String name = client.getItemDefinition(weapon.getId()).getName();
        if (!melee.isEmpty() && melee.get(0).equalsIgnoreCase(name)) {
            return PeteSwapPlanner.Style.MELEE;
        }
        if (!ranged.isEmpty() && ranged.get(0).equalsIgnoreCase(name)) {
            return PeteSwapPlanner.Style.RANGED;
        }
        // A staff is deliberately not a style here. It only casts when told to, so left alone it bashes
        // in melee - the freeze is a one-off cast, and the next swap takes the staff straight off again.
        return null;
    }

    /** A style can be used when its weapon (the first item) is already in hand or in the inventory. */
    private boolean styleAvailable(List<String> gear, ItemContainer equipment, ItemContainer inventory) {
        return !gear.isEmpty() && (isWearing(equipment, gear.get(0)) || slotOf(inventory, gear.get(0)) != -1);
    }

    /** A style can be swapped to when its weapon (the first item) is in the inventory. */
    private boolean canEquip(List<String> gear, ItemContainer inventory) {
        return !gear.isEmpty() && slotOf(inventory, gear.get(0)) != -1;
    }

    private List<String> equipSet(List<String> gear, ItemContainer equipment, ItemContainer inventory) {
        List<String> sent = new ArrayList<>();
        for (String name : gear) {
            if (isWearing(equipment, name)) {
                continue;
            }
            if (wield(inventory, name)) {
                sent.add(name);
            }
        }
        return sent;
    }

    private boolean isWearing(ItemContainer equipment, String name) {
        for (Item item : equipment.getItems()) {
            if (item != null && item.getId() >= 0 && name.equalsIgnoreCase(client.getItemDefinition(item.getId()).getName())) {
                return true;
            }
        }
        return false;
    }

    private int slotOf(ItemContainer inventory, String name) {
        Item[] items = inventory.getItems();
        for (int slot = 0; slot < items.length; slot++) {
            Item item = items[slot];
            if (item != null && item.getId() >= 0 && name.equalsIgnoreCase(client.getItemDefinition(item.getId()).getName())) {
                return slot;
            }
        }
        return -1;
    }

    private boolean wield(ItemContainer inventory, String name) {
        int slot = slotOf(inventory, name);
        if (slot == -1) {
            return false;
        }
        Item item = inventory.getItem(slot);
        ItemComposition definition = client.getItemDefinition(item.getId());
        String[] actions = definition.getInventoryActions();
        for (int i = 0; i < actions.length; i++) {
            String action = actions[i];
            if (action == null) {
                continue;
            }
            for (String equip : EQUIP_ACTIONS) {
                if (equip.equalsIgnoreCase(action)) {
                    // Item definitions list the actions as [null, "Wield", null, null, "Drop"], but the click
                    // for the second action carries identifier 3 (checked against a real click), so +2.
                    client.menuAction(slot, ComponentID.INVENTORY_CONTAINER, MenuAction.CC_OP, i + 2, item.getId(), action, name);
                    return true;
                }
            }
        }
        return false;
    }
}
