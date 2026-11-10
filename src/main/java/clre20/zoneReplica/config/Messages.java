package clre20.zoneReplica.config;

import clre20.zoneReplica.util.Placeholders;
import clre20.zoneReplica.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * messages.yml：所有聊天訊息、介面標題、物品名稱與 Lore。
 * 使用者檔案缺少某個鍵時改用內建預設值，不會出現 null。
 */
public final class Messages {

    private YamlConfiguration user = new YamlConfiguration();
    private YamlConfiguration defaults = new YamlConfiguration();
    private String prefix = "";

    public void load(JavaPlugin plugin, String prefix) {
        this.defaults = YamlUpdater.loadDefaults(plugin, "messages.yml");
        this.user = YamlUpdater.loadAndUpdate(plugin, "messages.yml", "messages-version");
        this.prefix = prefix == null ? "" : prefix;
    }

    /** 原始字串（未替換、未上色）。 */
    public String raw(String key) {
        String v = user.getString(key);
        if (v == null) {
            v = defaults.getString(key);
        }
        return v == null ? key : v;
    }

    public String raw(String key, Placeholders ph) {
        return Text.replace(raw(key), ph == null ? null : ph.map());
    }

    public List<String> rawList(String key) {
        List<String> list = user.isList(key) ? user.getStringList(key) : null;
        if (list == null && user.isString(key)) {
            list = List.of(user.getString(key, ""));
        }
        if (list == null) {
            list = defaults.isList(key) ? defaults.getStringList(key)
                    : defaults.isString(key) ? List.of(defaults.getString(key, "")) : Collections.emptyList();
        }
        return list;
    }

    /** 純文字（去除色碼，後台記錄用）。 */
    public String plain(String key, Placeholders ph) {
        return Text.plain(raw(key, ph));
    }

    public Component component(String key, Placeholders ph) {
        return Text.parse(raw(key, ph));
    }

    public Component component(String key) {
        return component(key, null);
    }

    /** 物品名稱（不斜體）。 */
    public Component itemName(String key, Placeholders ph) {
        return Text.item(raw(key, ph));
    }

    /**
     * 物品 Lore；佔位字的值含有換行時會展開成多行，值為空字串且整行只有該佔位字時略過該行。
     */
    public List<Component> lore(String key, Placeholders ph) {
        List<String> lines = rawList(key);
        List<Component> out = new ArrayList<>(lines.size() + 2);
        Map<String, String> map = ph == null ? null : ph.map();
        for (String line : lines) {
            String replaced = Text.replace(line, map);
            if (replaced.isEmpty() && !line.isEmpty()) {
                continue;
            }
            for (String part : replaced.split("\n", -1)) {
                out.add(Text.item(part));
            }
        }
        return out;
    }

    /** 送出聊天訊息（自動加上前綴）；訊息內容為空字串時不送出。 */
    public void send(CommandSender to, String key, Placeholders ph) {
        String msg = raw(key, ph);
        if (msg.isEmpty() || to == null) {
            return;
        }
        Component c = Text.parse(prefix + msg);
        to.sendMessage(c);
        if (to instanceof BlockCommandSender) {
            // 指令方塊看不到回覆，改寫在後台
            Bukkit.getConsoleSender().sendMessage(c);
        }
    }

    public void send(CommandSender to, String key) {
        send(to, key, null);
    }

    /** 送出已組好的 Component（加上前綴）。 */
    public void sendRaw(CommandSender to, Component body) {
        if (to == null) {
            return;
        }
        Component c = Text.parse(prefix).append(body);
        to.sendMessage(c);
        if (to instanceof BlockCommandSender) {
            Bukkit.getConsoleSender().sendMessage(c);
        }
    }

    /** 後台記錄（訊息鍵 log.*）。 */
    public void log(String key, Placeholders ph) {
        String msg = raw(key, ph);
        if (!msg.isEmpty()) {
            Bukkit.getConsoleSender().sendMessage(Text.parse(prefix + msg));
        }
    }

    public String prefix() {
        return prefix;
    }
}
