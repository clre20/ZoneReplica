package clre20.zoneReplica.gui;

import clre20.zoneReplica.ZoneReplica;
import clre20.zoneReplica.model.AutoUpdate;
import clre20.zoneReplica.util.Placeholders;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 4.5 白名單（6 列）：每個場地各自設定，白名單玩家不會被送走。 */
public final class WhitelistGui extends Gui {

    private static final int PER_PAGE = 36;

    private final Gui back;
    private final AutoUpdate au;
    private final Runnable touch;
    private int page;

    WhitelistGui(ZoneReplica plugin, Player viewer, Gui back, AutoUpdate au, Runnable touch) {
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
        return plugin.messages().component("gui.whitelist.title", Placeholders.of("count", au.whitelist().size()));
    }

    static String nameOf(UUID id) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        String n = op.getName();
        return n == null ? id.toString().substring(0, 8) : n;
    }

    @Override
    protected void build() {
        List<UUID> list = new ArrayList<>(au.whitelist());
        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = page * PER_PAGE + i;
            if (idx >= list.size()) {
                break;
            }
            UUID id = list.get(idx);
            String name = nameOf(id);
            Player online = Bukkit.getPlayer(id);
            Placeholders ph = Placeholders.of("player", name, "uuid", id);
            set(i, Items.head(Bukkit.getOfflinePlayer(id), online != null,
                    plugin.messages().itemName("gui.whitelist.entry.name", ph),
                    plugin.messages().lore("gui.whitelist.entry.lore", ph)), p -> {
                au.whitelist().remove(id);
                touch.run();
                plugin.messages().send(p, "whitelist-removed", ph);
                refresh();
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
        set(47, Items.of(plugin, Material.LIME_DYE, "gui.whitelist.add-online", pagePh),
                p -> new OnlinePlayersGui(plugin, p, this, au, touch).open(p));
        set(48, Items.of(plugin, Material.NAME_TAG, "gui.whitelist.add-name", pagePh), this::addByName);
        set(49, Items.of(plugin, Material.ARROW, "gui.common.back", pagePh), p -> back.open(p));
    }

    private void addByName(Player p) {
        plugin.chatInput().ask(p, "chat-what.player", text -> {
            OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(text);
            if (cached != null) {
                add(p, cached.getUniqueId(), cached.getName() == null ? text : cached.getName());
                return;
            }
            // 伺服器沒有記錄的玩家：在網路執行緒查詢，不卡主執行緒
            UUID viewerId = p.getUniqueId();
            CompletableFuture.supplyAsync(() -> Bukkit.getOfflinePlayer(text), plugin.netExecutor())
                    .thenAccept(op -> Bukkit.getScheduler().runTask(plugin, () -> {
                        Player pl = Bukkit.getPlayer(viewerId);
                        if (pl == null) {
                            return;
                        }
                        if (op == null || (!op.hasPlayedBefore() && op.getName() == null)) {
                            plugin.messages().send(pl, "player-not-found", Placeholders.of("player", text));
                            open(pl);
                            return;
                        }
                        add(pl, op.getUniqueId(), op.getName() == null ? text : op.getName());
                    }));
        }, () -> open(p));
    }

    void add(Player p, UUID id, String name) {
        Placeholders ph = Placeholders.of("player", name);
        if (!au.whitelist().add(id)) {
            plugin.messages().send(p, "whitelist-exists", ph);
        } else {
            touch.run();
            plugin.messages().send(p, "whitelist-added", ph);
        }
        open(p);
    }
}
