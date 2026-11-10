package clre20.zoneReplica.gui;

import clre20.zoneReplica.ZoneReplica;
import clre20.zoneReplica.util.Placeholders;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** 4.1 建立樣本（3 列）。 */
public final class CreateSampleGui extends SampleGuiBase {

    public CreateSampleGui(ZoneReplica plugin, Player viewer, Sessions.SampleDraft draft) {
        super(plugin, viewer, draft);
    }

    /** 開啟（有未過期的暫存時繼續編輯）。 */
    public static void open(ZoneReplica plugin, Player p, String name) {
        Sessions.SampleDraft d = plugin.sessions().get(p.getUniqueId(), Sessions.sampleKey(name), Sessions.SampleDraft.class);
        if (d == null || !d.isNew) {
            d = new Sessions.SampleDraft(name, true);
        } else {
            plugin.messages().send(p, "draft-resumed");
        }
        plugin.sessions().put(p.getUniqueId(), Sessions.sampleKey(name), d);
        new CreateSampleGui(plugin, p, d).open(p);
    }

    @Override
    protected int rows() {
        return 3;
    }

    @Override
    protected Component title() {
        return plugin.messages().component("gui.create.title", Placeholders.of("sample", draft.name));
    }

    @Override
    protected void build() {
        set(4, infoItem());
        set(10, pointItem(true), p -> pickPoint(p, true));
        set(12, pointItem(false), p -> pickPoint(p, false));
        set(14, markersItem(), this::toggleMarkers);
        set(18, Items.of(plugin, Material.BARRIER, "gui.create.cancel", basePh()), p -> {
            plugin.sessions().remove(viewer, draftKey);
            p.closeInventory();
        });
        List<String> missing = preview().missing();
        if (missing.isEmpty()) {
            set(26, Items.of(plugin, Material.EMERALD_BLOCK, "gui.create.save", basePh()), p -> {
                if (plugin.service().createSample(p, draft)) {
                    success(p);
                    plugin.sessions().remove(viewer, draftKey);
                    p.closeInventory();
                } else {
                    fail(p);
                    refresh();
                }
            });
        } else {
            set(26, Items.of(plugin, Material.BARRIER, "gui.common.save-missing",
                    basePh().with("missing", plugin.service().missingText(missing))), this::fail);
        }
    }
}
