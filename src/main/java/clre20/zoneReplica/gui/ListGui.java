package clre20.zoneReplica.gui;

import clre20.zoneReplica.ZoneReplica;
import clre20.zoneReplica.model.Cuboid;
import clre20.zoneReplica.model.Sample;
import clre20.zoneReplica.util.Placeholders;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** 4.7 樣本列表（6 列）：左鍵進入編輯樣本。 */
public final class ListGui extends Gui {

    private static final int PER_PAGE = 45;
    private int page;

    public ListGui(ZoneReplica plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        return plugin.messages().component("gui.list.title",
                Placeholders.of("count", plugin.store().all().size()));
    }

    @Override
    protected void build() {
        List<Sample> list = new ArrayList<>(plugin.store().all());
        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = page * PER_PAGE + i;
            if (idx >= list.size()) {
                break;
            }
            Sample s = list.get(idx);
            Cuboid r = s.region();
            Placeholders ph = Placeholders.of("sample", s.name(),
                    "world", String.valueOf(s.world()),
                    "size", r == null ? "-" : r.sizeText(),
                    "placements", s.placements().size(),
                    "snapshot", s.snapshotTime() == null ? plugin.messages().raw("gui.state.none") : s.snapshotTime());
            boolean ok = plugin.store().hasSnapshot(s);
            set(i, Items.of(plugin, ok ? Material.CHEST : Material.BARREL, "gui.list.entry", ph), p -> {
                if (!p.hasPermission("zonereplica.edit")) {
                    plugin.messages().send(p, "no-permission");
                    return;
                }
                Sample cur = plugin.store().get(s.name());
                if (cur != null) {
                    EditSampleGui.open(plugin, p, cur);
                }
            });
        }
        Placeholders pagePh = Placeholders.of("page", page + 1, "pages", pages);
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
        set(49, Items.of(plugin, Material.BARRIER, "gui.common.close", pagePh), Player::closeInventory);
    }
}
