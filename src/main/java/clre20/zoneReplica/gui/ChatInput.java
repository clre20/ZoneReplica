package clre20.zoneReplica.gui;

import clre20.zoneReplica.ZoneReplica;
import clre20.zoneReplica.util.Placeholders;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 聊天欄輸入（時刻、提示文字、玩家名稱、場地名稱）。
 * 輸入取消字放棄，逾時自動取消；回呼一律在主執行緒執行。
 */
public final class ChatInput implements Listener {

    private record Request(Consumer<String> onInput, Runnable onCancel, long expiresAt) {
    }

    private final ZoneReplica plugin;
    private final Map<UUID, Request> waiting = new ConcurrentHashMap<>();
    private BukkitTask timeoutTask;

    public ChatInput(ZoneReplica plugin) {
        this.plugin = plugin;
    }

    public void start() {
        timeoutTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkTimeouts, 20L, 20L);
    }

    public boolean isWaiting(UUID id) {
        return waiting.containsKey(id);
    }

    /**
     * 關閉介面並請玩家在聊天欄輸入。
     *
     * @param whatKey messages.yml 中描述要輸入什麼的鍵（chat-what.*）
     */
    public void ask(Player p, String whatKey, Consumer<String> onInput, Runnable onCancel) {
        waiting.put(p.getUniqueId(), new Request(onInput, onCancel,
                System.currentTimeMillis() + plugin.config().chatInputTimeout() * 1000L));
        p.closeInventory();
        plugin.messages().send(p, "chat-prompt", Placeholders.of(
                "what", plugin.messages().raw(whatKey),
                "cancel", plugin.config().chatCancelWord(),
                "seconds", plugin.config().chatInputTimeout()));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Request req = waiting.remove(id);
        if (req == null) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                return;
            }
            if (text.equalsIgnoreCase(plugin.config().chatCancelWord())) {
                plugin.messages().send(p, "chat-cancelled");
                if (req.onCancel() != null) {
                    req.onCancel().run();
                }
                return;
            }
            req.onInput().accept(text);
        });
    }

    private void checkTimeouts() {
        if (waiting.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Request>> it = waiting.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Request> e = it.next();
            if (e.getValue().expiresAt() > now) {
                continue;
            }
            it.remove();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) {
                plugin.messages().send(p, "chat-timeout");
                if (e.getValue().onCancel() != null) {
                    e.getValue().onCancel().run();
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        waiting.remove(event.getPlayer().getUniqueId());
    }

    public void shutdown() {
        if (timeoutTask != null) {
            timeoutTask.cancel();
        }
        waiting.clear();
    }
}
