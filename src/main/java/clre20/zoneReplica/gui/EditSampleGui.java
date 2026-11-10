package clre20.zoneReplica.gui;

import clre20.zoneReplica.ZoneReplica;
import clre20.zoneReplica.model.Cuboid;
import clre20.zoneReplica.model.Placement;
import clre20.zoneReplica.model.Sample;
import clre20.zoneReplica.storage.ZoneStore;
import clre20.zoneReplica.task.ZoneTask;
import clre20.zoneReplica.util.Placeholders;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** 4.2 編輯樣本（6 列）：中間三列是場地列表。 */
public final class EditSampleGui extends SampleGuiBase {

    private static final int PER_PAGE = 27;
    private int page;

    public EditSampleGui(ZoneReplica plugin, Player viewer, Sessions.SampleDraft draft) {
        super(plugin, viewer, draft);
    }

    /** 開啟（有未過期的暫存時繼續編輯）。 */
    public static void open(ZoneReplica plugin, Player p, Sample s) {
        String key = Sessions.sampleKey(s.name());
        Sessions.SampleDraft d = plugin.sessions().get(p.getUniqueId(), key, Sessions.SampleDraft.class);
        if (d == null || d.isNew) {
            d = new Sessions.SampleDraft(s.name(), false);
            d.world = s.world();
            d.a = s.a();
            d.b = s.b();
            d.removeMarkers = s.removeMarkers();
        } else {
            plugin.messages().send(p, "draft-resumed");
        }
        plugin.sessions().put(p.getUniqueId(), key, d);
        new EditSampleGui(plugin, p, d).open(p);
    }

    private Sample stored() {
        return plugin.store().get(draft.name);
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        return plugin.messages().component("gui.edit.title", Placeholders.of("sample", draft.name));
    }

    @Override
    protected void build() {
        Sample s = stored();
        if (s == null) {
            return;
        }
        set(4, infoItem());
        set(10, pointItem(true), p -> pickPoint(p, true));
        set(12, pointItem(false), p -> pickPoint(p, false));
        set(14, markersItem(), this::toggleMarkers);
        set(16, Items.of(plugin, Material.SPYGLASS, "gui.edit.recapture", basePh()), p -> {
            if (plugin.service().saveSample(p, draft, true)) {
                success(p);
                plugin.sessions().remove(viewer, draftKey);
                p.closeInventory();
            } else {
                fail(p);
            }
        });

        List<Placement> list = new ArrayList<>(s.placements());
        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        Sample preview = preview();
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = page * PER_PAGE + i;
            if (idx >= list.size()) {
                break;
            }
            Placement pl = list.get(idx);
            set(18 + i, placementItem(preview, pl), p -> PlacementGui.openExisting(plugin, p, s, pl, () -> open(p)));
        }
        Placeholders pagePh = basePh().with("page", page + 1).with("pages", pages);
        if (page > 0) {
            set(45, Items.of(plugin, Material.ARROW, "gui.common.previous", pagePh), p -> {
                page--;
                refresh();
            });
        }
        if (page < pages - 1) {
            set(53, Items.of(plugin, Material.ARROW, "gui.common.next", pagePh), p -> {
                page++;
                refresh();
            });
        }
        set(47, Items.of(plugin, Material.LIME_DYE, "gui.edit.add-placement", basePh()), this::addPlacement);
        set(49, Items.of(plugin, Material.LAVA_BUCKET, "gui.edit.delete", basePh()),
                p -> plugin.service().deleteSample(p, s, false, () -> open(p)));
        set(50, Items.of(plugin, Material.BARRIER, "gui.common.close", basePh()), p -> {
            plugin.sessions().remove(viewer, draftKey);
            p.closeInventory();
        });
        List<String> missing = preview.missing();
        if (missing.isEmpty()) {
            set(51, Items.of(plugin, Material.EMERALD_BLOCK, "gui.edit.save", basePh()), p -> {
                if (plugin.service().saveSample(p, draft, false)) {
                    success(p);
                    plugin.sessions().remove(viewer, draftKey);
                    p.closeInventory();
                } else {
                    fail(p);
                }
            });
        } else {
            set(51, Items.of(plugin, Material.BARRIER, "gui.common.save-missing",
                    basePh().with("missing", plugin.service().missingText(missing))), this::fail);
        }
    }

    private org.bukkit.inventory.ItemStack placementItem(Sample preview, Placement pl) {
        Cuboid t = pl.target(preview);
        String autoText;
        if (Boolean.TRUE.equals(pl.autoUpdate().enabled())) {
            autoText = plugin.messages().raw("gui.edit.auto-on", Placeholders.of(
                    "times", String.join(", ", pl.autoUpdate().times())));
        } else {
            autoText = plugin.messages().raw("gui.edit.auto-off");
        }
        String taskText = plugin.messages().raw("gui.edit.task-idle");
        for (ZoneTask t2 : plugin.tasks().find(Sample.key(draft.name), Sample.key(pl.name()))) {
            taskText = plugin.messages().raw("gui.edit.task-busy", plugin.tasks().placeholders(t2));
            break;
        }
        Placeholders ph = basePh()
                .with("placement", pl.name())
                .with("pworld", String.valueOf(pl.world()))
                .with("c", pl.c() == null ? plugin.messages().raw("gui.state.unset") : pl.c().toString())
                .with("range", t == null ? "-" : t.toString())
                .with("auto", autoText)
                .with("task", taskText);
        return Items.of(plugin, Material.FILLED_MAP, "gui.edit.placement-entry", ph);
    }

    private void addPlacement(Player p) {
        plugin.chatInput().ask(p, "chat-what.placement-name", text -> {
            Sample s = stored();
            if (s == null) {
                return;
            }
            if (!ZoneStore.validName(text)) {
                plugin.messages().send(p, "invalid-name");
                open(p);
                return;
            }
            if (s.placement(text) != null) {
                plugin.messages().send(p, "placement-exists", Placeholders.of("sample", s.name(), "placement", text,
                        "target", plugin.messages().raw("format.target",
                                Placeholders.of("sample", s.name(), "placement", text))));
                open(p);
                return;
            }
            PlacementGui.openNew(plugin, p, s, text, () -> open(p));
        }, () -> open(p));
    }
}
