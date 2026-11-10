package clre20.zoneReplica.selection;

import clre20.zoneReplica.ZoneReplica;
import clre20.zoneReplica.util.Placeholders;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 選取工具：介面按鈕發放，左鍵點方塊記錄座標後自動收回並回到介面。
 * 工具不能丟棄、放置或放進其他容器；離線、死亡、逾時都會自動收回。
 */
public final class SelectionManager implements Listener {

    /** 點方塊（記錄方塊座標）或記錄玩家站立的位置與朝向。 */
    public enum Mode { BLOCK, LOCATION }

    private record Pending(Mode mode, String pointKey, Consumer<Block> onBlock, Consumer<Location> onLocation,
                           Runnable onCancel, long expiresAt) {
    }

    private final ZoneReplica plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private BukkitTask timeoutTask;

    public SelectionManager(ZoneReplica plugin) {
        this.plugin = plugin;
    }

    public void start() {
        timeoutTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkTimeouts, 20L, 20L);
    }

    public boolean isSelecting(UUID id) {
        return pending.containsKey(id);
    }

    /** 開始選取方塊。 */
    public void selectBlock(Player p, String pointKey, Consumer<Block> onPick, Runnable onCancel) {
        begin(p, new Pending(Mode.BLOCK, pointKey, onPick, null, onCancel,
                System.currentTimeMillis() + plugin.config().toolTimeoutSeconds() * 1000L));
    }

    /** 開始記錄站立位置。 */
    public void selectLocation(Player p, String pointKey, Consumer<Location> onPick, Runnable onCancel) {
        begin(p, new Pending(Mode.LOCATION, pointKey, null, onPick, onCancel,
                System.currentTimeMillis() + plugin.config().toolTimeoutSeconds() * 1000L));
    }

    private void begin(Player p, Pending req) {
        cancel(p, false);
        removeTools(p);
        ItemStack tool = createTool();
        PlayerInventory inv = p.getInventory();
        int slot = inv.getHeldItemSlot();
        if (inv.getItem(slot) == null || inv.getItem(slot).getType().isAir()) {
            inv.setItem(slot, tool);
        } else if (!inv.addItem(tool).isEmpty()) {
            plugin.messages().send(p, "selection-inventory-full");
            if (req.onCancel() != null) {
                req.onCancel().run();
            }
            return;
        } else {
            // 切到工具所在的格子，方便直接使用
            for (int i = 0; i < 9; i++) {
                if (isTool(inv.getItem(i))) {
                    inv.setHeldItemSlot(i);
                    break;
                }
            }
        }
        pending.put(p.getUniqueId(), req);
        Placeholders ph = Placeholders.of("point", plugin.messages().raw(req.pointKey()),
                "seconds", plugin.config().toolTimeoutSeconds());
        plugin.messages().send(p, req.mode() == Mode.BLOCK ? "selection-start" : "selection-start-location", ph);
    }

    public ItemStack createTool() {
        ItemStack item = new ItemStack(plugin.config().toolMaterial());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().itemName("tool.name", null));
        meta.lore(plugin.messages().lore("tool.lore", null));
        meta.getPersistentDataContainer().set(plugin.toolKey(), PersistentDataType.BYTE, (byte) 1);
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isTool(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(plugin.toolKey(), PersistentDataType.BYTE);
    }

    public void removeTools(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isTool(contents[i])) {
                inv.setItem(i, null);
            }
        }
        if (isTool(p.getItemOnCursor())) {
            p.setItemOnCursor(null);
        }
    }

    /** 取消選取（收回工具）；notify = 是否執行取消回呼（回到介面）。 */
    public void cancel(Player p, boolean notify) {
        Pending req = pending.remove(p.getUniqueId());
        removeTools(p);
        if (req != null && notify && req.onCancel() != null) {
            req.onCancel().run();
        }
    }

    private void checkTimeouts() {
        if (pending.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Pending>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Pending> e = it.next();
            if (e.getValue().expiresAt() > now) {
                continue;
            }
            it.remove();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) {
                removeTools(p);
                plugin.messages().send(p, "selection-timeout");
                if (e.getValue().onCancel() != null) {
                    e.getValue().onCancel().run();
                }
            }
        }
    }

    public void shutdown() {
        if (timeoutTask != null) {
            timeoutTask.cancel();
        }
        for (UUID id : pending.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                removeTools(p);
            }
        }
        pending.clear();
    }

    // ---------- 事件 ----------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !isTool(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        Player p = event.getPlayer();
        Pending req = pending.get(p.getUniqueId());
        if (req == null) {
            // 失效的工具
            removeTools(p);
            return;
        }
        Action a = event.getAction();
        if (a != Action.LEFT_CLICK_BLOCK && a != Action.LEFT_CLICK_AIR) {
            return;
        }
        if (req.mode() == Mode.BLOCK && a == Action.LEFT_CLICK_AIR) {
            pending.remove(p.getUniqueId());
            removeTools(p);
            plugin.messages().send(p, "selection-cancelled");
            if (req.onCancel() != null) {
                req.onCancel().run();
            }
            return;
        }
        pending.remove(p.getUniqueId());
        removeTools(p);
        if (req.mode() == Mode.BLOCK) {
            Block b = event.getClickedBlock();
            if (b != null && req.onBlock() != null) {
                req.onBlock().accept(b);
            }
        } else if (req.onLocation() != null) {
            req.onLocation().accept(p.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isTool(event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isTool(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (isTool(event.getPlayer().getInventory().getItem(event.getHand()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p && isTool(p.getInventory().getItemInMainHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isTool(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (isTool(event.getMainHandItem()) || isTool(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        boolean involved = isTool(event.getCurrentItem()) || isTool(event.getCursor());
        if (!involved && event.getHotbarButton() >= 0 && event.getWhoClicked() instanceof Player p) {
            involved = isTool(p.getInventory().getItem(event.getHotbarButton()));
        }
        if (!involved) {
            return;
        }
        // 只允許在自己的背包內移動
        if (event.getView().getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING
                || event.getClick().isShiftClick() || event.getClick() == org.bukkit.event.inventory.ClickType.DROP
                || event.getClick() == org.bukkit.event.inventory.ClickType.CONTROL_DROP
                || event.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (isTool(event.getOldCursor()) && event.getView().getTopInventory().getType()
                != org.bukkit.event.inventory.InventoryType.CRAFTING) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(this::isTool);
        Player p = event.getPlayer();
        Pending req = pending.remove(p.getUniqueId());
        if (req != null) {
            plugin.messages().send(p, "selection-cancelled");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
        removeTools(event.getPlayer());
    }
}
