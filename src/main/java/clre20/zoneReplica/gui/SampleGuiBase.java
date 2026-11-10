package clre20.zoneReplica.gui;

import clre20.zoneReplica.ZoneReplica;
import clre20.zoneReplica.model.BlockPos;
import clre20.zoneReplica.model.Cuboid;
import clre20.zoneReplica.model.Placement;
import clre20.zoneReplica.model.Sample;
import clre20.zoneReplica.selection.OutlineManager;
import clre20.zoneReplica.service.ZoneService;
import clre20.zoneReplica.util.Placeholders;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** 建立樣本（4.1）與編輯樣本（4.2）共用的部分。 */
abstract class SampleGuiBase extends Gui {

    protected final Sessions.SampleDraft draft;
    protected final String draftKey;

    protected SampleGuiBase(ZoneReplica plugin, Player viewer, Sessions.SampleDraft draft) {
        super(plugin, viewer);
        this.draft = draft;
        this.draftKey = Sessions.sampleKey(draft.name);
    }

    protected Sample preview() {
        return ZoneService.draftToSample(draft);
    }

    protected List<String> overlaps() {
        Sample s = preview();
        if (s.region() == null) {
            return List.of();
        }
        return plugin.store().overlaps(s.world(), s.region(), s.key(), null, null);
    }

    protected Placeholders basePh() {
        Sample s = preview();
        Cuboid r = s.region();
        Sample stored = plugin.store().get(draft.name);
        return Placeholders.of(
                "sample", draft.name,
                "world", draft.world == null ? plugin.messages().raw("gui.state.unset") : draft.world,
                "a", draft.a == null ? plugin.messages().raw("gui.state.unset") : draft.a.toString(),
                "b", draft.b == null ? plugin.messages().raw("gui.state.unset") : draft.b.toString(),
                "size", r == null ? "-" : r.sizeText(),
                "blocks", r == null ? "-" : String.valueOf(r.volume()),
                "snapshot", stored == null || stored.snapshotTime() == null
                        ? plugin.messages().raw("gui.state.none") : stored.snapshotTime(),
                "placements", stored == null ? 0 : stored.placements().size());
    }

    protected ItemStack infoItem() {
        List<Component> extra = new ArrayList<>();
        for (String o : overlaps()) {
            extra.addAll(plugin.messages().lore("gui.common.overlap-line", Placeholders.of("target", o)));
        }
        return Items.of(plugin, Material.BOOK, "gui.sample.info", basePh(), extra);
    }

    protected ItemStack pointItem(boolean a) {
        BlockPos pos = a ? draft.a : draft.b;
        Placeholders ph = basePh().with("pos", pos == null ? plugin.messages().raw("gui.state.unset") : pos.toString());
        ItemStack item = Items.of(plugin, a ? Material.RED_WOOL : Material.BLUE_WOOL,
                a ? "gui.sample.point-a" : "gui.sample.point-b", ph);
        return pos == null ? item : Items.glow(item);
    }

    protected ItemStack markersItem() {
        Placeholders ph = basePh().with("state", Items.stateText(plugin, draft.removeMarkers));
        return Items.of(plugin, tri(draft.removeMarkers), "gui.sample.markers", ph);
    }

    protected void toggleMarkers(Player p) {
        draft.removeMarkers = cycle(draft.removeMarkers);
        plugin.sessions().touch(viewer, draftKey);
        refresh();
    }

    /** 拿選取工具點方塊設定 A 或 B。 */
    protected void pickPoint(Player p, boolean a) {
        p.closeInventory();
        plugin.selections().selectBlock(p, a ? "point.a" : "point.b", block -> {
            String world = block.getWorld().getName();
            BlockPos pos = BlockPos.of(block);
            if (draft.world != null && !draft.world.equals(world)) {
                if (a) {
                    draft.b = null;
                } else {
                    draft.a = null;
                }
                plugin.messages().send(p, "selection-world-changed", Placeholders.of("world", world));
            }
            BlockPos other = a ? draft.b : draft.a;
            if (other != null) {
                long volume = Cuboid.of(pos, other).volume();
                if (volume > plugin.config().maxVolume()) {
                    plugin.messages().send(p, "selection-too-large", Placeholders.of("volume", volume,
                            "max", plugin.config().maxVolume()));
                    fail(p);
                    open(p);
                    return;
                }
            }
            draft.world = world;
            if (a) {
                draft.a = pos;
            } else {
                draft.b = pos;
            }
            plugin.sessions().put(viewer, draftKey, draft);
            plugin.messages().send(p, "selection-set", Placeholders.of(
                    "point", plugin.messages().raw(a ? "point.a" : "point.b"), "location", world + " " + pos));
            success(p);
            open(p);
        }, () -> open(p));
    }

    @Override
    protected List<OutlineManager.Box> outline() {
        List<OutlineManager.Box> out = new ArrayList<>();
        if (draft.world == null) {
            return out;
        }
        Cuboid region = draft.a != null && draft.b != null ? Cuboid.of(draft.a, draft.b)
                : draft.a != null ? Cuboid.of(draft.a, draft.a) : draft.b != null ? Cuboid.of(draft.b, draft.b) : null;
        if (region == null) {
            return out;
        }
        boolean overlap = draft.a != null && draft.b != null && !overlaps().isEmpty();
        out.add(new OutlineManager.Box(draft.world, region,
                overlap ? plugin.config().overlapColor() : plugin.config().sampleColor()));
        Sample stored = plugin.store().get(draft.name);
        if (stored != null && draft.a != null && draft.b != null) {
            Sample s = preview();
            for (Placement pl : stored.placements()) {
                Cuboid t = pl.target(s);
                if (t != null) {
                    out.add(new OutlineManager.Box(pl.world(), t, plugin.config().placementColor()));
                }
            }
        }
        return out;
    }
}
