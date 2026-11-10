package clre20.zoneReplica.gui;

import clre20.zoneReplica.ZoneReplica;
import clre20.zoneReplica.selection.OutlineManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 所有 ZoneReplica 箱子介面的基底。
 * <p>本類別即為介面的 InventoryHolder，並以 {@link #getPlugin()} 與 {@link #SIGNATURE} 簽署插件來源，
 * 其他插件可用 {@code holder instanceof Gui} 或比對簽署字串辨識。</p>
 * <p>只接受左鍵點擊，所有點擊都會被取消，物品無法被拿走。</p>
 */
public abstract class Gui implements InventoryHolder {

    /** 插件簽署字串。 */
    public static final String SIGNATURE = "clre20:zonereplica";

    protected final ZoneReplica plugin;
    protected final UUID viewer;
    private final Map<Integer, Consumer<Player>> actions = new HashMap<>();
    private Inventory inventory;
    private Component shownTitle;

    protected Gui(ZoneReplica plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer.getUniqueId();
    }

    /** 插件簽署：建立此介面的插件實例。 */
    public ZoneReplica getPlugin() {
        return plugin;
    }

    public String getSignature() {
        return SIGNATURE;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    protected abstract int rows();

    protected abstract Component title();

    /** 以 {@link #set} 放置按鈕。 */
    protected abstract void build();

    /** 介面開啟時玩家看到的外框。 */
    protected List<OutlineManager.Box> outline() {
        return Collections.emptyList();
    }

    public void open(Player p) {
        Component t = title();
        inventory = Bukkit.createInventory(this, rows() * 9, t);
        shownTitle = t;
        render();
        p.openInventory(inventory);
        plugin.outlines().show(p, outline());
    }

    /** 重新繪製；標題改變時重新開啟。 */
    public void refresh() {
        Player p = Bukkit.getPlayer(viewer);
        if (p == null || inventory == null) {
            return;
        }
        if (!title().equals(shownTitle) || p.getOpenInventory().getTopInventory() != inventory) {
            open(p);
            return;
        }
        render();
        plugin.outlines().show(p, outline());
    }

    private void render() {
        actions.clear();
        inventory.clear();
        build();
        ItemStack filler = Items.filler(plugin);
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, filler);
            }
        }
    }

    protected void set(int slot, ItemStack item, Consumer<Player> action) {
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        inventory.setItem(slot, item);
        if (action != null) {
            actions.put(slot, action);
        } else {
            actions.remove(slot);
        }
    }

    protected void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    void click(Player p, int slot) {
        Consumer<Player> action = actions.get(slot);
        if (action == null) {
            return;
        }
        if (plugin.config().sounds()) {
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
        }
        action.accept(p);
    }

    protected void fail(Player p) {
        if (plugin.config().sounds()) {
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
        }
    }

    protected void success(Player p) {
        if (plugin.config().sounds()) {
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        }
    }

    /** 介面關閉時（非切換到另一個介面）。 */
    void onClosed(Player p) {
    }

    protected static Material tri(Boolean v) {
        return v == null ? Material.GRAY_STAINED_GLASS_PANE : v ? Material.LIME_STAINED_GLASS_PANE
                : Material.RED_STAINED_GLASS_PANE;
    }

    /** 三態切換：未設定 → 是 → 否 → 是 …。 */
    protected static Boolean cycle(Boolean v) {
        return v == null ? Boolean.TRUE : !v;
    }
}
