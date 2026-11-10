package clre20.zoneReplica.gui;

import clre20.zoneReplica.ZoneReplica;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * 箱子介面事件：一律取消點擊與拖曳（物品拿不走），只有左鍵會觸發按鈕。
 */
public final class GuiListener implements Listener {

    private final ZoneReplica plugin;

    public GuiListener(ZoneReplica plugin) {
        this.plugin = plugin;
    }

    private Gui ours(InventoryHolder holder) {
        return holder instanceof Gui g && g.getPlugin() == plugin ? g : null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        Gui gui = ours(event.getView().getTopInventory().getHolder(false));
        if (gui == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getClick() != ClickType.LEFT || !(event.getWhoClicked() instanceof Player p)) {
            return;
        }
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= event.getView().getTopInventory().getSize()) {
            return;
        }
        gui.click(p, raw);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (ours(event.getView().getTopInventory().getHolder(false)) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Gui gui = ours(event.getInventory().getHolder(false));
        if (gui == null || !(event.getPlayer() instanceof Player p)) {
            return;
        }
        if (event.getReason() == InventoryCloseEvent.Reason.OPEN_NEW) {
            return;
        }
        gui.onClosed(p);
        plugin.sessions().afterClose(p);
    }
}
