package clre20.zoneReplica.gui;

import clre20.zoneReplica.ZoneReplica;
import clre20.zoneReplica.config.Messages;
import clre20.zoneReplica.util.Placeholders;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

/** 介面物品：名稱與 Lore 全部取自 messages.yml。 */
public final class Items {

    private Items() {
    }

    /**
     * 以 messages.yml 的 {key}.name 與 {key}.lore 建立物品。
     */
    public static ItemStack of(ZoneReplica plugin, Material material, String key, Placeholders ph) {
        Messages m = plugin.messages();
        return build(material, m.itemName(key + ".name", ph), m.lore(key + ".lore", ph), false);
    }

    public static ItemStack of(ZoneReplica plugin, Material material, String key, Placeholders ph,
                               List<Component> extraLore) {
        Messages m = plugin.messages();
        List<Component> lore = new ArrayList<>(m.lore(key + ".lore", ph));
        lore.addAll(extraLore);
        return build(material, m.itemName(key + ".name", ph), lore, false);
    }

    public static ItemStack build(Material material, Component name, List<Component> lore, boolean glow) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(name);
        if (lore != null && !lore.isEmpty()) {
            meta.lore(lore);
        }
        meta.addItemFlags(ItemFlag.values());
        if (glow) {
            meta.setEnchantmentGlintOverride(true);
        }
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack glow(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack filler(ZoneReplica plugin) {
        return build(Material.BLACK_STAINED_GLASS_PANE, plugin.messages().itemName("gui.filler", null), null, false);
    }

    /** 玩家頭顱；只對線上玩家套用外觀，避免為離線玩家向 Mojang 查詢。 */
    public static ItemStack head(OfflinePlayer owner, boolean online, Component name, List<Component> lore) {
        ItemStack item = build(Material.PLAYER_HEAD, name, lore, false);
        if (online && item.getItemMeta() instanceof SkullMeta skull) {
            skull.setOwningPlayer(owner);
            item.setItemMeta(skull);
        }
        return item;
    }

    /** 三態選項的狀態文字。 */
    public static String stateText(ZoneReplica plugin, Boolean v) {
        return plugin.messages().raw(v == null ? "gui.state.unset" : v ? "gui.state.yes" : "gui.state.no");
    }
}
