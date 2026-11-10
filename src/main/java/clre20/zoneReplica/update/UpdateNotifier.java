package clre20.zoneReplica.update;

import clre20.zoneReplica.ZoneReplica;
import clre20.zoneReplica.config.Messages;
import clre20.zoneReplica.util.Placeholders;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;

/**
 * 顯示版本與檢查結果：/zr、/zr version、啟動時的後台輸出，以及登入 3 秒後的提醒。
 */
public final class UpdateNotifier implements Listener {

    public static final String PERMISSION = "zonereplica.version";

    private final ZoneReplica plugin;

    public UpdateNotifier(ZoneReplica plugin) {
        this.plugin = plugin;
    }

    private Messages msg() {
        return plugin.messages();
    }

    /** 啟動時：非同步檢查，結果寫在後台。 */
    public void checkOnStartup() {
        if (!plugin.config().updateCheckEnabled()) {
            return;
        }
        plugin.updateChecker().check().thenAccept(result ->
                Bukkit.getScheduler().runTask(plugin, () -> report(Bukkit.getConsoleSender(), result, true)));
    }

    /** /zr 與 /zr version：顯示版本並檢查更新。 */
    public void showVersion(CommandSender sender) {
        Messages m = msg();
        String current = plugin.updateChecker().currentVersion();
        m.send(sender, "version-info", Placeholders.of("current", current));
        if (!plugin.config().updateCheckEnabled()) {
            m.send(sender, "update-disabled");
            return;
        }
        m.send(sender, "update-checking");
        UUID id = sender instanceof Player p ? p.getUniqueId() : null;
        plugin.updateChecker().check().thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
            CommandSender target = id == null ? sender : Bukkit.getPlayer(id);
            if (target != null) {
                report(target, result, false);
            }
        }));
    }

    private void report(CommandSender to, UpdateChecker.Result r, boolean startup) {
        Messages m = msg();
        Placeholders ph = Placeholders.of("current", r.current(), "latest", r.latest() == null ? "-" : r.latest());
        switch (r.status()) {
            case UPDATE_AVAILABLE -> m.sendRaw(to, link(m.component("update-available", ph), r.url()));
            case LATEST -> m.send(to, "update-latest", ph);
            case NEWER -> m.send(to, "update-newer", ph);
            case NO_RELEASE -> m.send(to, "update-no-release", ph);
            case FAILED -> {
                if (startup) {
                    plugin.getLogger().warning(m.plain("update-failed", ph));
                } else {
                    m.send(to, "update-failed", ph);
                }
            }
        }
    }

    private Component link(Component text, String url) {
        return text.clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(msg().component("update-hover")));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!plugin.config().updateCheckEnabled() || !plugin.config().updateNotifyOnJoin()
                || !player.hasPermission(PERMISSION)) {
            return;
        }
        UUID id = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                return;
            }
            plugin.updateChecker().check().thenAccept(r -> {
                if (r.status() != UpdateChecker.Status.UPDATE_AVAILABLE) {
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player online = Bukkit.getPlayer(id);
                    if (online != null) {
                        Placeholders ph = Placeholders.of("current", r.current(), "latest", r.latest());
                        msg().sendRaw(online, link(msg().component("update-join", ph), r.url()));
                    }
                });
            });
        }, 60L);
    }
}
