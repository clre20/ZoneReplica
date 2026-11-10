package clre20.zoneReplica.gui;

import clre20.zoneReplica.ZoneReplica;
import clre20.zoneReplica.model.AutoUpdate;
import clre20.zoneReplica.model.StoredLocation;
import clre20.zoneReplica.selection.OutlineManager;
import clre20.zoneReplica.util.Placeholders;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 4.4 自動更新（6 列）。 */
public final class AutoUpdateGui extends Gui {

    private final PlacementGui parent;

    AutoUpdateGui(ZoneReplica plugin, Player viewer, PlacementGui parent) {
        super(plugin, viewer);
        this.parent = parent;
    }

    private AutoUpdate au() {
        return parent.draft().placement.autoUpdate();
    }

    private void touch() {
        Sessions.PlacementDraft d = parent.draft();
        plugin.sessions().put(viewer, Sessions.placementKey(d.sampleName, d.placement.name()), d);
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        return plugin.messages().component("gui.auto.title", parent.basePh());
    }

    @Override
    protected List<OutlineManager.Box> outline() {
        return parent.outline();
    }

    private Placeholders ph() {
        AutoUpdate au = au();
        String unset = plugin.messages().raw("gui.state.unset");
        return parent.basePh()
                .with("count", au.times().size())
                .with("max", AutoUpdate.MAX_TIMES)
                .with("destination", plugin.messages().raw(au.destination() == AutoUpdate.Destination.SPAWN
                        ? "gui.auto.dest-spawn-name" : "gui.auto.dest-custom-name"))
                .with("location", au.customLocation() == null ? unset : au.customLocation().display())
                .with("message", au.message().isEmpty() ? plugin.messages().raw("gui.auto.message-default")
                        : au.message())
                .with("whitelist", au.whitelist().size());
    }

    @Override
    protected void build() {
        AutoUpdate au = au();
        set(4, Items.of(plugin, tri(au.enabled()), "gui.auto.enabled",
                ph().with("state", Items.stateText(plugin, au.enabled()))), p -> {
            au.enabled(cycle(au.enabled()));
            touch();
            refresh();
        });

        int slot = 10;
        for (String time : au.times()) {
            set(slot++, Items.of(plugin, Material.CLOCK, "gui.auto.time-entry", ph().with("time", time)), p -> {
                au.times().remove(time);
                touch();
                refresh();
            });
        }
        if (au.times().size() < AutoUpdate.MAX_TIMES) {
            set(17, Items.of(plugin, Material.LIME_DYE, "gui.auto.add-time", ph()), this::addTime);
        } else {
            set(17, Items.of(plugin, Material.GRAY_DYE, "gui.auto.time-full", ph()), this::fail);
        }

        DayOfWeek[] days = DayOfWeek.values();
        for (int i = 0; i < days.length; i++) {
            DayOfWeek day = days[i];
            boolean on = au.days().contains(day);
            Placeholders dph = ph().with("day", plugin.messages().raw("days." + day.name().toLowerCase(Locale.ROOT)))
                    .with("state", plugin.messages().raw(on ? "gui.state.enabled" : "gui.state.disabled"));
            ItemStack item = Items.of(plugin, on ? Material.PAPER : Material.MAP, "gui.auto.day", dph);
            set(19 + i, on ? Items.glow(item) : item, p -> {
                if (!au.days().remove(day)) {
                    au.days().add(day);
                }
                touch();
                refresh();
            });
        }

        set(29, Items.of(plugin, tri(au.teleportPlayers()), "gui.auto.teleport",
                ph().with("state", Items.stateText(plugin, au.teleportPlayers()))), p -> {
            au.teleportPlayers(cycle(au.teleportPlayers()));
            touch();
            refresh();
        });
        boolean spawn = au.destination() == AutoUpdate.Destination.SPAWN;
        set(31, Items.of(plugin, spawn ? Material.RED_BED : Material.ENDER_PEARL, "gui.auto.destination", ph()), p -> {
            au.destination(spawn ? AutoUpdate.Destination.CUSTOM : AutoUpdate.Destination.SPAWN);
            touch();
            refresh();
        });
        ItemStack compass = Items.of(plugin, Material.COMPASS, "gui.auto.set-location", ph());
        set(32, au.customLocation() == null ? compass : Items.glow(compass), this::pickLocation);
        set(33, Items.of(plugin, Material.OAK_SIGN, "gui.auto.message", ph()), this::editMessage);
        set(34, Items.of(plugin, Material.PLAYER_HEAD, "gui.auto.whitelist", ph()),
                p -> new WhitelistGui(plugin, p, this, au(), this::touch).open(p));

        List<String> missing = new ArrayList<>(au.missing());
        if (!missing.isEmpty()) {
            set(40, Items.of(plugin, Material.BARRIER, "gui.auto.missing",
                    ph().with("missing", plugin.service().missingText(missing))));
        }
        set(49, Items.of(plugin, Material.ARROW, "gui.common.back", ph()), p -> parent.open(p));
    }

    private void addTime(Player p) {
        plugin.chatInput().ask(p, "chat-what.time", text -> {
            String t = AutoUpdate.normalizeTime(text);
            if (t == null) {
                plugin.messages().send(p, "invalid-time");
            } else if (au().times().contains(t)) {
                plugin.messages().send(p, "time-exists", Placeholders.of("time", t));
            } else if (au().times().size() >= AutoUpdate.MAX_TIMES) {
                plugin.messages().send(p, "time-full", Placeholders.of("max", AutoUpdate.MAX_TIMES));
            } else {
                au().times().add(t);
                touch();
                plugin.messages().send(p, "time-added", Placeholders.of("time", t));
            }
            open(p);
        }, () -> open(p));
    }

    private void editMessage(Player p) {
        plugin.chatInput().ask(p, "chat-what.message", text -> {
            au().message("-".equals(text) ? "" : text);
            touch();
            plugin.messages().send(p, "message-set");
            open(p);
        }, () -> open(p));
    }

    private void pickLocation(Player p) {
        p.closeInventory();
        plugin.selections().selectLocation(p, "point.destination", loc -> {
            au().customLocation(StoredLocation.of(loc));
            au().destination(AutoUpdate.Destination.CUSTOM);
            touch();
            plugin.messages().send(p, "selection-set", Placeholders.of(
                    "point", plugin.messages().raw("point.destination"),
                    "location", au().customLocation().display()));
            success(p);
            open(p);
        }, () -> open(p));
    }
}
