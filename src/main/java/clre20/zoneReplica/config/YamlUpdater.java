package clre20.zoneReplica.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;

/**
 * 設定檔自動補齊：以插件內建的預設檔（含註解）為底，填回使用者原有的值後寫回。
 * 只有在缺少項目或版本不同時才會寫檔，避免不必要的磁碟 I/O。
 */
public final class YamlUpdater {

    private YamlUpdater() {
    }

    /** 讀取插件 jar 內的預設檔。 */
    public static YamlConfiguration loadDefaults(JavaPlugin plugin, String resource) {
        YamlConfiguration defaults = new YamlConfiguration();
        try (InputStream in = plugin.getResource(resource)) {
            if (in != null) {
                defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().severe("無法讀取內建的 " + resource + "：" + e.getMessage());
        }
        return defaults;
    }

    /**
     * 讀取並更新使用者的檔案。
     *
     * @return 使用者目前的設定（已補齊缺少的項目）
     */
    public static YamlConfiguration loadAndUpdate(JavaPlugin plugin, String resource, String versionKey) {
        Logger log = plugin.getLogger();
        File file = new File(plugin.getDataFolder(), resource);
        YamlConfiguration defaults = loadDefaults(plugin, resource);
        defaults.options().width(4096);
        if (!file.exists()) {
            plugin.saveResource(resource, false);
            return defaults;
        }

        YamlConfiguration user = new YamlConfiguration();
        try {
            user.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            // 檔案格式錯誤：保留原檔供服主修正，改用預設值寫回
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
            File broken = new File(file.getParentFile(), resource + ".broken-" + stamp);
            try {
                Files.move(file.toPath(), broken.toPath(), StandardCopyOption.REPLACE_EXISTING);
                plugin.saveResource(resource, true);
            } catch (IOException ex) {
                log.severe("無法備份格式錯誤的 " + resource + "：" + ex.getMessage());
            }
            log.severe(resource + " 格式錯誤，已改用預設值（原檔保留為 "
                    + broken.getName() + "）：" + e.getMessage());
            return defaults;
        }

        List<String> missing = new ArrayList<>();
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) {
                continue;
            }
            if (!user.contains(key, true)) {
                missing.add(key);
            }
        }
        int defaultVersion = defaults.getInt(versionKey, 1);
        int userVersion = user.getInt(versionKey, 0);
        if (missing.isEmpty() && defaultVersion == userVersion) {
            return user;
        }

        // 以預設檔為底（保留註解），填回使用者原有的值
        YamlConfiguration merged = defaults;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key) || key.equals(versionKey)) {
                continue;
            }
            if (user.contains(key, true) && !user.isConfigurationSection(key)) {
                merged.set(key, user.get(key));
            }
        }
        merged.set(versionKey, defaultVersion);

        try {
            Files.copy(file.toPath(), new File(file.getParentFile(), resource + ".bak").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            merged.save(file);
            if (!missing.isEmpty()) {
                log.info(resource + "：已補上 " + missing.size() + " 個缺少的項目：" + String.join(", ", missing));
            }
            if (defaultVersion != userVersion) {
                log.info(resource + "：已更新為第 " + defaultVersion + " 版（舊檔備份為 " + resource + ".bak）");
            }
        } catch (IOException e) {
            log.severe("無法寫入 " + resource + "：" + e.getMessage());
        }
        return merged;
    }
}
