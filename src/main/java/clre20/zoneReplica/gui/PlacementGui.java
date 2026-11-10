package clre20.zoneReplica.gui;

import clre20.zoneReplica.ZoneReplica;
import clre20.zoneReplica.model.AutoUpdate;
import clre20.zoneReplica.model.BlockPos;
import clre20.zoneReplica.model.Cuboid;
import clre20.zoneReplica.model.Placement;
import clre20.zoneReplica.model.Sample;
import clre20.zoneReplica.selection.OutlineManager;
import clre20.zoneReplica.task.TaskManager;
import clre20.zoneReplica.util.Placeholders;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 4.3 設定場地（6 列）。 */
public final class PlacementGui extends Gui {

    private final Sessions.PlacementDraft draft;
    private final String draftKey;
    private final Runnable back;

    private PlacementGui(ZoneReplica plugin, Player viewer, Sessions.PlacementDraft draft, Runnable back) {
        super(plugin, viewer);
        this.draft = draft;
        this.draftKey = Sessions.placementKey(draft.sampleName, draft.placement.name());
        this.back = back;
    }

    /** 新增場地（/zr place）。 */
    public static void openNew(ZoneReplica plugin, Player p, Sample s, String name, Runnable back) {
        String key = Sessions.placementKey(s.name(), name);
        Sessions.PlacementDraft d = plugin.sessions().get(p.getUniqueId(), key, Sessions.PlacementDraft.class);
        if (d == null || !d.isNew) {
            d = new Sessions.PlacementDraft(s.name(), true, new Placement(name));
        } else {
            plugin.messages().send(p, "draft-resumed");
        }
        plugin.sessions().put(p.getUniqueId(), key, d);
        new PlacementGui(plugin, p, d, back).open(p);
    }

    /** 編輯既有場地（/zr edit 樣本 場地，或從樣本介面點選）。 */
    public static void openExisting(ZoneReplica plugin, Player p, Sample s, Placement pl, Runnable back) {
        String key = Sessions.placementKey(s.name(), pl.name());
        Sessions.PlacementDraft d = plugin.sessions().get(p.getUniqueId(), key, Sessions.PlacementDraft.class);
        if (d == null || d.isNew) {
            d = new Sessions.PlacementDraft(s.name(), false, pl.copy());
        } else {
            plugin.messages().send(p, "draft-resumed");
        }
        plugin.sessions().put(p.getUniqueId(), key, d);
        new PlacementGui(plugin, p, d, back).open(p);
    }

    Sessions.PlacementDraft draft() {
        return draft;
    }

    private Sample sample() {
        return plugin.store().get(draft.sampleName);
    }

    private Placement pl() {
        return draft.placement;
    }

    private void touch() {
        plugin.sessions().put(viewer, draftKey, draft);
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        return plugin.messages().component(draft.isNew ? "gui.placement.title-new" : "gui.placement.title-edit",
                Placeholders.of("sample", draft.sampleName, "placement", pl().name()));
    }

    private List<String> overlaps() {
        Sample s = sample();
        if (s == null || pl().c() == null) {
            return List.of();
        }
        return plugin.store().overlaps(pl().world(), pl().target(s), null,
                s.key() + "/" + Sample.key(pl().name()), null);
    }

    Placeholders basePh() {
        Sample s = sample();
        Cuboid t = s == null ? null : pl().target(s);
        String unset = plugin.messages().raw("gui.state.unset");
        return Placeholders.of(
                "sample", draft.sampleName,
                "placement", pl().name(),
                "world", pl().world() == null ? unset : pl().world(),
                "c", pl().c() == null ? unset : pl().c().toString(),
                "range", t == null ? "-" : t.toString(),
                "size", s == null || s.region() == null ? "-" : s.region().sizeText(),
                "snapshot", s == null || s.snapshotTime() == null
                        ? plugin.messages().raw("gui.state.none") : s.snapshotTime());
    }

    @Override
    protected void build() {
        Sample s = sample();
        if (s == null) {
            return;
        }
        List<Component> extra = new ArrayList<>();
        for (String o : overlaps()) {
            extra.addAll(plugin.messages().lore("gui.common.overlap-line", Placeholders.of("target", o)));
        }
        set(4, Items.of(plugin, Material.BOOK, "gui.placement.info", basePh(), extra));
        ItemStack c = Items.of(plugin, Material.GREEN_WOOL, "gui.placement.point-c", basePh());
        set(10, pl().c() == null ? c : Items.glow(c), this::pickC);

        option(19, "gui.placement.copy-entities", pl()::copyEntities, pl()::copyEntities);
        option(20, "gui.placement.clear-entities", pl()::clearEntities, pl()::clearEntities);
        option(21, "gui.placement.copy-containers", pl()::copyContainers, pl()::copyContainers);
        option(22, "gui.placement.replace-air", pl()::replaceAir, pl()::replaceAir);

        AutoUpdate au = pl().autoUpdate();
        String autoState = au.enabled() == null ? plugin.messages().raw("gui.state.unset")
                : au.enabled() ? plugin.messages().raw("gui.edit.auto-on",
                Placeholders.of("times", String.join(", ", au.times())))
                : plugin.messages().raw("gui.edit.auto-off");
        set(24, Items.of(plugin, Material.CLOCK, "gui.placement.auto-update", basePh().with("state", autoState)),
                p -> new AutoUpdateGui(plugin, p, this).open(p));

        if (!draft.isNew) {
            set(37, Items.of(plugin, Material.PISTON, "gui.placement.update-now", basePh()), p -> {
                Sample cur = sample();
                Placement saved = cur == null ? null : cur.placement(pl().name());
                if (saved != null) {
                    plugin.service().update(p, cur, List.of(saved), TaskManager.ConflictMode.REJECT, () -> open(p));
                }
            });
            set(38, Items.of(plugin, Material.ECHO_SHARD, "gui.placement.undo", basePh()), p -> {
                Sample cur = sample();
                Placement saved = cur == null ? null : cur.placement(pl().name());
                if (saved != null) {
                    plugin.service().undo(p, cur, saved, TaskManager.ConflictMode.REJECT, false, () -> open(p));
                }
            });
            set(45, Items.of(plugin, Material.LAVA_BUCKET, "gui.placement.delete", basePh()), p -> {
                Sample cur = sample();
                Placement saved = cur == null ? null : cur.placement(pl().name());
                if (saved != null) {
                    plugin.service().deletePlacement(p, cur, saved, false, () -> open(p));
                }
            });
        }
        set(49, Items.of(plugin, Material.BARRIER, back == null ? "gui.placement.cancel" : "gui.placement.back",
                basePh()), p -> {
            plugin.sessions().remove(viewer, draftKey);
            if (back != null) {
                back.run();
            } else {
                p.closeInventory();
            }
        });
        List<String> missing = pl().missing();
        if (missing.isEmpty()) {
            set(53, Items.of(plugin, Material.EMERALD_BLOCK, "gui.placement.save", basePh()), p -> {
                if (plugin.service().savePlacement(p, draft)) {
                    success(p);
                    plugin.sessions().remove(viewer, draftKey);
                    if (back != null) {
                        back.run();
                    } else {
                        p.closeInventory();
                    }
                } else {
                    fail(p);
                    refresh();
                }
            });
        } else {
            set(53, Items.of(plugin, Material.BARRIER, "gui.common.save-missing",
                    basePh().with("missing", plugin.service().missingText(missing))), this::fail);
        }
    }

    private void option(int slot, String key, Supplier<Boolean> getter, Consumer<Boolean> setter) {
        Boolean v = getter.get();
        set(slot, Items.of(plugin, tri(v), key, basePh().with("state", Items.stateText(plugin, v))), p -> {
            setter.accept(cycle(getter.get()));
            touch();
            refresh();
        });
    }

    private void pickC(Player p) {
        p.closeInventory();
        plugin.selections().selectBlock(p, "point.c", block -> {
            Sample s = sample();
            if (s == null || s.region() == null) {
                return;
            }
            BlockPos pos = BlockPos.of(block);
            String world = block.getWorld().getName();
            Placement test = pl().copy();
            test.world(world);
            test.c(pos);
            Placeholders height = plugin.service().heightProblem(world, test.target(s));
            if (height != null) {
                plugin.messages().send(p, "selection-out-of-world", height);
                fail(p);
                open(p);
                return;
            }
            pl().world(world);
            pl().c(pos);
            touch();
            plugin.messages().send(p, "selection-set", Placeholders.of(
                    "point", plugin.messages().raw("point.c"), "location", world + " " + pos));
            for (String o : overlaps()) {
                plugin.messages().send(p, "overlap-warning", Placeholders.of("target", o));
            }
            success(p);
            open(p);
        }, () -> open(p));
    }

    @Override
    protected List<OutlineManager.Box> outline() {
        List<OutlineManager.Box> out = new ArrayList<>();
        Sample s = sample();
        if (s == null || s.region() == null) {
            return out;
        }
        out.add(new OutlineManager.Box(s.world(), s.region(), plugin.config().sampleColor()));
        if (pl().c() != null) {
            boolean overlap = !overlaps().isEmpty();
            out.add(new OutlineManager.Box(pl().world(), pl().target(s),
                    overlap ? plugin.config().overlapColor() : plugin.config().placementColor()));
        }
        return out;
    }
}
