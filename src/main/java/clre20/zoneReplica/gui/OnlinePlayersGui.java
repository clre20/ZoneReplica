package clre20.zoneReplica.gui;

import clre20.zoneReplica.ZoneReplica;
import clre20.zoneReplica.model.AutoUpdate;
import clre20.zoneReplica.util.Placeholders;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 從線上玩家加入白名單（6 列）。 */
public final class OnlinePlayersGui extends Gui {

    private static final int PER_PAGE = 45;

    private final WhitelistGui back;
    private final AutoUpdate au;
    private final Runnable touch;
    private int page;

    OnlinePlayersGui(ZoneReplica plugin, Player viewer, WhitelistGui back, AutoUpdate au, Runnable touch) {
        super(plugin, viewer);
        this.back = back;
        this.au = au;
        this.touch = touch;
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        return plugin.messages().component("gui.online.title");
    }

    @Override
    protected void build() {
        List<Player> list = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!au.whitelist().contains(p.getUniqueId())) {
                list.add(p);
            }
        }
        list.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = page * PER_PAGE + i;
            if (idx >= list.size()) {
                break;
            }
            Player target = list.get(idx);
            Placeholders ph = Placeholders.of("player", target.getName());
            java.util.UUID id = target.getUniqueId();
            String name = target.getName();
            set(i, Items.head(target, true, plugin.messages().itemName("gui.online.entry.name", ph),
                    plugin.messages().lore("gui.online.entry.lore", ph)), p -> back.add(p, id, name));
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
        set(49, Items.of(plugin, Material.ARROW, "gui.common.back", pagePh), p -> back.open(p));
    }
}
