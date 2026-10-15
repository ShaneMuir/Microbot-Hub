package net.runelite.client.plugins.microbot.petekayerprayer;

import com.google.inject.Provides;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.PostMenuSort;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.PluginConstants;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;

import javax.inject.Inject;

@PluginDescriptor(
        name = PluginConstants.DEFAULT_PREFIX + "Pete Kayer Prayer",
        description = "Switches protection prayers to match Pete Kayer's attacks in the PvP Challenges",
        tags = {"pete kayer", "prayer", "pvp", "ferox", "challenges", "microbot"},
        authors = {"Baropa"},
        version = PeteKayerPrayerPlugin.version,
        minClientVersion = "2.6.24",
        enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        isExternal = PluginConstants.IS_EXTERNAL
)
public class PeteKayerPrayerPlugin extends Plugin {
    static final String version = "0.2.0";

    private static final String PETE = "Pete Kayer";
    private static final String SPARRING_PARTNER = "Sparring partner";

    @Inject
    private Client client;

    @Inject
    private PeteKayerPrayerConfig config;

    @Inject
    private PeteKayerPrayerScript script;

    private PeteGearSwapper swapper;
    private final PeteFightGate gate = new PeteFightGate();
    private boolean wasFightActive;

    /**
     * With the staff in hand, the left-click on Pete becomes "Cast Ice Barrage" instead of a staff bash.
     * Built the way Left-Click Cast does it (a menu entry added at the tail, which is the left-click), but
     * it sends the two clicks of a manual cast directly, which keep working in a fight where the other
     * plugin falls back to a mouse path that is dropped while the player is clicking.
     */
    @Subscribe
    public void onPostMenuSort(PostMenuSort event) {
        if (swapper == null || !config.clickToCast() || client.isMenuOpen() || !swapper.castWindowOpen(config)) {
            return;
        }
        Menu menu = client.getMenu();
        MenuEntry[] entries = menu.getMenuEntries();
        for (int i = entries.length - 1; i >= 0; i--) {
            MenuEntry entry = entries[i];
            if (!"Attack".equals(entry.getOption()) || entry.getNpc() == null || !PETE.equals(entry.getNpc().getName())) {
                continue;
            }
            final NPC pete = entry.getNpc();
            MenuEntry cast = menu.createMenuEntry(-1)
                    .setOption("Cast Ice Barrage")
                    .setTarget(entry.getTarget())
                    .setType(MenuAction.RUNELITE)
                    .setIdentifier(entry.getIdentifier())
                    .setParam0(entry.getParam0())
                    .setParam1(entry.getParam1())
                    .onClick(e -> swapper.castFromClick(pete));
            cast.setWorldViewId(entry.getWorldViewId());
            return;
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (swapper != null && event.getMessage() != null) {
            swapper.onGameMessage(event.getMessage());
        }
    }

    @Provides
    PeteKayerPrayerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(PeteKayerPrayerConfig.class);
    }

    @Override
    protected void startUp() {
        swapper = new PeteGearSwapper(client);
        script.run();
    }

    @Override
    protected void shutDown() {
        gate.reset();
        script.shutdown();
    }

    // What to sit on between attacks. Pete's melee lands on the animation tick and his gear id flips
    // on that same tick, so there is no warning for it and no point chasing it: rest on magic, which
    // also covers his Voidwaker special and his freeze opener. The sparring partner in Challenge 1
    // attacks slowly, one style at a time, so melee is its resting prayer.
    @Subscribe
    public void onGameTick(GameTick event) {
        // Nothing but the reactive protection prayer happens until the player has started the fight by
        // attacking Pete, so standing near him doesn't camp prayers or swap gear.
        Player me = client.getLocalPlayer();
        Actor target = me == null ? null : me.getInteracting();
        int tick = client.getTickCount();
        gate.update(target instanceof NPC && PETE.equals(target.getName()), tick);
        boolean fightActive = gate.isActive(tick);

        Rs2PrayerEnum rest = null;
        for (NPC npc : client.getNpcs()) {
            if (isOpponent(npc)) {
                if (SPARRING_PARTNER.equals(npc.getName())) {
                    rest = Rs2PrayerEnum.PROTECT_MELEE;
                } else if (fightActive && config.restOnMagic()) {
                    rest = Rs2PrayerEnum.PROTECT_MAGIC;
                }
                break;
            }
        }
        script.setRest(rest);

        if (wasFightActive && !fightActive && swapper != null && config.autoSwap()) {
            swapper.onFightEnded(config);
        }
        wasFightActive = fightActive;

        if (!fightActive) {
            if (swapper != null) {
                swapper.reset();
            }
            return;
        }

        if (config.autoSwap()) {
            // Don't require that Pete is interacting with the player: his target can drop for a tick or
            // two around his gear changes, and a missed tick means a missed swap.
            NPC pete = null;
            for (NPC npc : client.getNpcs()) {
                if (PETE.equals(npc.getName())) {
                    pete = npc;
                    break;
                }
            }
            if (pete != null) {
                swapper.onGameTick(pete, config);
            } else {
                swapper.onPeteMissing();
            }
        }
    }

    @Subscribe
    public void onAnimationChanged(AnimationChanged event) {
        Actor actor = event.getActor();
        if (actor == client.getLocalPlayer()) {
            if (PeteGearSwapper.isAttackAnimation(actor.getAnimation())) {
                gate.onAttack(client.getTickCount());
            }
            if (swapper != null && config.autoSwap()) {
                swapper.onPlayerAnimation(actor.getAnimation());
            }
            return;
        }
        if (!isOpponent(actor)) {
            return;
        }
        PeteAttackStyle style = PeteAttackStyle.fromAnimation(actor.getAnimation());
        if (style != null) {
            gate.onAttack(client.getTickCount());
            int distance = actor.getWorldLocation().distanceTo(client.getLocalPlayer().getWorldLocation());
            script.onAttack(style, distance, "anim " + actor.getAnimation());
        }
    }

    private boolean isOpponent(Actor actor) {
        if (!(actor instanceof NPC) || client.getLocalPlayer() == null) {
            return false;
        }
        if (actor.getInteracting() != client.getLocalPlayer()) {
            return false;
        }
        String name = actor.getName();
        return PETE.equals(name) || (config.includeSparringPartner() && SPARRING_PARTNER.equals(name));
    }
}
