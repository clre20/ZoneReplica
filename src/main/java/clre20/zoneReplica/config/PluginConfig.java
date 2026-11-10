package clre20.zoneReplica.config;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * config.yml 的型別化讀取，所有數值都有預設值防呆，讀取一次後快取於記憶體。
 */
public final class PluginConfig {

    public enum OutlineMode { DISPLAY, PARTICLE }

    private String prefix;
    private boolean updateCheckEnabled;
    private boolean updateNotifyOnJoin;
    private Material toolMaterial;
    private int toolTimeoutSeconds;
    private OutlineMode outlineMode;
    private Color sampleColor;
    private Color placementColor;
    private Color overlapColor;
    private long maxVolume;
    private int blocksPerTick;
    private long maxNanosPerTick;
    private int maxConcurrentTasks;
    private int backupKeep;
    private BossBar.Color bossBarColor;
    private BossBar.Overlay bossBarOverlay;
    private List<Integer> warningSeconds;
    private boolean warningTitle;
    private int draftKeepMinutes;
    private int chatInputTimeout;
    private String chatCancelWord;
    private boolean sounds;

    public void load(JavaPlugin plugin) {
        YamlConfiguration c = YamlUpdater.loadAndUpdate(plugin, "config.yml", "config-version");
        prefix = c.getString("prefix", "&8[&bZoneReplica&8] &r");
        updateCheckEnabled = c.getBoolean("update-checker.enabled", true);
        updateNotifyOnJoin = c.getBoolean("update-checker.notify-on-join", true);

        Material m = Material.matchMaterial(c.getString("selection-tool.material", "BLAZE_ROD"));
        toolMaterial = (m == null || !m.isItem() || m.isAir()) ? Material.BLAZE_ROD : m;
        toolTimeoutSeconds = clamp(c.getInt("selection-tool.timeout-seconds", 120), 10, 3600);

        outlineMode = "PARTICLE".equalsIgnoreCase(c.getString("outline.mode", "DISPLAY"))
                ? OutlineMode.PARTICLE : OutlineMode.DISPLAY;
        sampleColor = parseColor(c.getString("outline.sample-color"), Color.AQUA);
        placementColor = parseColor(c.getString("outline.placement-color"), Color.LIME);
        overlapColor = parseColor(c.getString("outline.overlap-color"), Color.RED);

        maxVolume = Math.max(1L, c.getLong("limits.max-volume", 1_000_000L));
        blocksPerTick = clamp(c.getInt("limits.blocks-per-tick", 4096), 64, 1_000_000);
        maxNanosPerTick = clamp(c.getInt("limits.max-ms-per-tick", 20), 1, 45) * 1_000_000L;
        maxConcurrentTasks = clamp(c.getInt("limits.max-concurrent-tasks", 1), 1, 16);

        backupKeep = clamp(c.getInt("backup.keep", 1), 1, 100);

        bossBarColor = parseEnum(BossBar.Color.class, c.getString("bossbar.color", "BLUE"), BossBar.Color.BLUE);
        String style = c.getString("bossbar.style", "SEGMENTED_10").toUpperCase(Locale.ROOT)
                .replace("SEGMENTED_", "NOTCHED_").replace("SOLID", "PROGRESS");
        bossBarOverlay = parseEnum(BossBar.Overlay.class, style, BossBar.Overlay.NOTCHED_10);

        TreeSet<Integer> secs = new TreeSet<>(Collections.reverseOrder());
        for (Object o : c.getList("auto-update.warning-seconds", List.of(60, 30, 10, 5, 4, 3, 2, 1))) {
            try {
                int s = Integer.parseInt(String.valueOf(o).trim());
                if (s > 0 && s <= 3600) {
                    secs.add(s);
                }
            } catch (NumberFormatException ignored) {
                // 忽略無效的數字
            }
        }
        warningSeconds = Collections.unmodifiableList(new ArrayList<>(secs));
        warningTitle = c.getBoolean("auto-update.warning-title", true);

        draftKeepMinutes = clamp(c.getInt("gui.draft-keep-minutes", 5), 0, 1440);
        chatInputTimeout = clamp(c.getInt("gui.chat-input-timeout", 60), 5, 600);
        chatCancelWord = c.getString("gui.chat-cancel-word", "取消");
        if (chatCancelWord == null || chatCancelWord.isBlank()) {
            chatCancelWord = "取消";
        }
        sounds = c.getBoolean("gui.sounds", true);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    public static Color parseColor(String value, Color fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String v = value.trim();
        if (v.startsWith("#") && v.length() == 7) {
            try {
                return Color.fromRGB(Integer.parseInt(v.substring(1), 16));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return switch (v.toUpperCase(Locale.ROOT)) {
            case "WHITE" -> Color.WHITE;
            case "SILVER", "LIGHT_GRAY" -> Color.SILVER;
            case "GRAY" -> Color.GRAY;
            case "BLACK" -> Color.BLACK;
            case "RED" -> Color.RED;
            case "MAROON", "DARK_RED" -> Color.MAROON;
            case "YELLOW" -> Color.YELLOW;
            case "OLIVE" -> Color.OLIVE;
            case "LIME", "GREEN" -> Color.LIME;
            case "DARK_GREEN" -> Color.GREEN;
            case "AQUA", "CYAN" -> Color.AQUA;
            case "TEAL" -> Color.TEAL;
            case "BLUE" -> Color.BLUE;
            case "NAVY" -> Color.NAVY;
            case "FUCHSIA", "MAGENTA", "PINK" -> Color.FUCHSIA;
            case "PURPLE" -> Color.PURPLE;
            case "ORANGE", "GOLD" -> Color.ORANGE;
            default -> fallback;
        };
    }

    public String prefix() { return prefix; }
    public boolean updateCheckEnabled() { return updateCheckEnabled; }
    public boolean updateNotifyOnJoin() { return updateNotifyOnJoin; }
    public Material toolMaterial() { return toolMaterial; }
    public int toolTimeoutSeconds() { return toolTimeoutSeconds; }
    public OutlineMode outlineMode() { return outlineMode; }
    public Color sampleColor() { return sampleColor; }
    public Color placementColor() { return placementColor; }
    public Color overlapColor() { return overlapColor; }
    public long maxVolume() { return maxVolume; }
    public int blocksPerTick() { return blocksPerTick; }
    public long maxNanosPerTick() { return maxNanosPerTick; }
    public int maxConcurrentTasks() { return maxConcurrentTasks; }
    public int backupKeep() { return backupKeep; }
    public BossBar.Color bossBarColor() { return bossBarColor; }
    public BossBar.Overlay bossBarOverlay() { return bossBarOverlay; }
    /** 由大到小排序的倒數提醒秒數。 */
    public List<Integer> warningSeconds() { return warningSeconds; }
    /** 倒數總長（提醒秒數中的最大值）。 */
    public int countdownSeconds() { return warningSeconds.isEmpty() ? 0 : warningSeconds.get(0); }
    public boolean warningTitle() { return warningTitle; }
    public int draftKeepMinutes() { return draftKeepMinutes; }
    public int chatInputTimeout() { return chatInputTimeout; }
    public String chatCancelWord() { return chatCancelWord; }
    public boolean sounds() { return sounds; }
}
