package clre20.zoneReplica.gui;

import clre20.zoneReplica.ZoneReplica;
import clre20.zoneReplica.model.BlockPos;
import clre20.zoneReplica.model.Placement;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 介面暫存設定：未儲存就關閉時保留 config 指定的分鐘數，重新開啟同一個樣本或場地時繼續編輯。
 * 玩家離線時清除。
 */
public final class Sessions implements Listener {

    /** 樣本編輯中的暫存。 */
    public static final class SampleDraft {
        public final String name;
        public final boolean isNew;
        public String world;
        public BlockPos a;
        public BlockPos b;
        public Boolean removeMarkers;

        public SampleDraft(String name, boolean isNew) {
            this.name = name;
            this.isNew = isNew;
        }
    }

    /** 場地編輯中的暫存。 */
    public static final class PlacementDraft {
        public final String sampleName;
        public final boolean isNew;
        public final Placement placement;

        public PlacementDraft(String sampleName, boolean isNew, Placement placement) {
            this.sampleName = sampleName;
            this.isNew = isNew;
            this.placement = placement;
        }
    }

    private record Entry(Object draft, long touched) {
    }

    private final ZoneReplica plugin;
    private final Map<UUID, Map<String, Entry>> drafts = new HashMap<>();
    private BukkitTask cleanup;

    public Sessions(ZoneReplica plugin) {
        this.plugin = plugin;
    }

    public void start() {
        cleanup = Bukkit.getScheduler().runTaskTimer(plugin, this::expire, 1200L, 1200L);
    }

    public static String sampleKey(String sample) {
        return "sample:" + sample.toLowerCase(java.util.Locale.ROOT);
    }

    public static String placementKey(String sample, String placement) {
        return "placement:" + sample.toLowerCase(java.util.Locale.ROOT) + "/" + placement.toLowerCase(java.util.Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(UUID player, String key, Class<T> type) {
        Map<String, Entry> m = drafts.get(player);
        if (m == null) {
            return null;
        }
        Entry e = m.get(key);
        if (e == null || !type.isInstance(e.draft())) {
            return null;
        }
        if (expired(e)) {
            m.remove(key);
            return null;
        }
        return (T) e.draft();
    }

    public void put(UUID player, String key, Object draft) {
        drafts.computeIfAbsent(player, k -> new HashMap<>()).put(key, new Entry(draft, System.currentTimeMillis()));
    }

    /** 更新暫存的時間戳（每次操作時呼叫）。 */
    public void touch(UUID player, String key) {
        Map<String, Entry> m = drafts.get(player);
        if (m != null) {
            Entry e = m.get(key);
            if (e != null) {
                m.put(key, new Entry(e.draft(), System.currentTimeMillis()));
            }
        }
    }

    public void remove(UUID player, String key) {
        Map<String, Entry> m = drafts.get(player);
        if (m != null) {
            m.remove(key);
            if (m.isEmpty()) {
                drafts.remove(player);
            }
        }
    }

    /** 樣本被刪除時，清除所有玩家關於它的暫存。 */
    public void forgetSample(String sample) {
        String sk = sampleKey(sample);
        String pk = "placement:" + sample.toLowerCase(java.util.Locale.ROOT) + "/";
        for (Map<String, Entry> m : drafts.values()) {
            m.keySet().removeIf(k -> k.equals(sk) || k.startsWith(pk));
        }
    }

    public void forgetPlacement(String sample, String placement) {
        String key = placementKey(sample, placement);
        for (Map<String, Entry> m : drafts.values()) {
            m.remove(key);
        }
    }

    private boolean expired(Entry e) {
        long keep = plugin.config().draftKeepMinutes() * 60_000L;
        return System.currentTimeMillis() - e.touched() > keep;
    }

    private void expire() {
        Iterator<Map.Entry<UUID, Map<String, Entry>>> it = drafts.entrySet().iterator();
        while (it.hasNext()) {
            Map<String, Entry> m = it.next().getValue();
            m.values().removeIf(this::expired);
            if (m.isEmpty()) {
                it.remove();
            }
        }
    }

    /** 介面關閉後，若玩家沒有在其他介面、選取或聊天輸入中，就收起外框。 */
    void afterClose(Player p) {
        UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = Bukkit.getPlayer(id);
            if (online == null) {
                plugin.outlines().clear(id);
                return;
            }
            InventoryHolder h = online.getOpenInventory().getTopInventory().getHolder(false);
            if (h instanceof Gui || plugin.selections().isSelecting(id) || plugin.chatInput().isWaiting(id)) {
                return;
            }
            plugin.outlines().clear(id);
        });
    }

    /** 選取或聊天輸入結束而沒有回到介面時呼叫。 */
    public void endInteraction(Player p) {
        afterClose(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        drafts.remove(id);
        plugin.outlines().clear(id);
    }

    public void shutdown() {
        if (cleanup != null) {
            cleanup.cancel();
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof Gui) {
                p.closeInventory();
            }
        }
        drafts.clear();
    }
}
