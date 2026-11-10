package clre20.zoneReplica.model;

import org.bukkit.configuration.ConfigurationSection;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** 場地的自動更新設定。Boolean 為 null 代表「未設定」。 */
public final class AutoUpdate {

    public static final int MAX_TIMES = 7;
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    public enum Destination { SPAWN, CUSTOM }

    private Boolean enabled;
    private final TreeSet<String> times = new TreeSet<>();
    private final EnumSet<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
    private Boolean teleportPlayers;
    private Destination destination = Destination.SPAWN;
    private StoredLocation customLocation;
    private String message = "";
    private final LinkedHashSet<UUID> whitelist = new LinkedHashSet<>();

    public AutoUpdate copy() {
        AutoUpdate a = new AutoUpdate();
        a.enabled = enabled;
        a.times.addAll(times);
        a.days.addAll(days);
        a.teleportPlayers = teleportPlayers;
        a.destination = destination;
        a.customLocation = customLocation;
        a.message = message;
        a.whitelist.addAll(whitelist);
        return a;
    }

    /** 把 "4:00"、"04:00" 等格式正規化為 HH:mm；格式錯誤回傳 null。 */
    public static String normalizeTime(String input) {
        if (input == null) {
            return null;
        }
        String s = input.trim().replace('：', ':');
        try {
            String[] parts = s.split(":");
            if (parts.length != 2) {
                return null;
            }
            int h = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            return LocalTime.of(h, m).format(HH_MM);
        } catch (NumberFormatException | java.time.DateTimeException e) {
            return null;
        }
    }

    public boolean firesAt(DayOfWeek day, String hhmm) {
        return Boolean.TRUE.equals(enabled) && days.contains(day) && times.contains(hhmm);
    }

    /** 送走玩家：未設定視為否。 */
    public boolean teleportEnabled() {
        return Boolean.TRUE.equals(teleportPlayers);
    }

    /** 回傳缺少的項目（messages.yml 的 missing.* 鍵）。 */
    public List<String> missing() {
        List<String> out = new ArrayList<>();
        if (enabled == null) {
            out.add("missing.auto-enabled");
            return out;
        }
        if (!enabled) {
            return out;
        }
        if (times.isEmpty()) {
            out.add("missing.auto-times");
        }
        if (days.isEmpty()) {
            out.add("missing.auto-days");
        }
        if (teleportPlayers == null) {
            out.add("missing.auto-teleport");
        } else if (teleportPlayers && destination == Destination.CUSTOM && customLocation == null) {
            out.add("missing.auto-destination");
        }
        return out;
    }

    public Map<String, Object> serialize() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("times", new ArrayList<>(times));
        List<String> d = new ArrayList<>();
        for (DayOfWeek day : days) {
            d.add(day.name());
        }
        m.put("days", d);
        m.put("teleport-players", teleportPlayers);
        Map<String, Object> dest = new LinkedHashMap<>();
        dest.put("type", destination.name());
        dest.put("location", customLocation == null ? null : customLocation.serialize());
        m.put("destination", dest);
        m.put("message", message == null ? "" : message);
        List<String> wl = new ArrayList<>();
        for (UUID id : whitelist) {
            wl.add(id.toString());
        }
        m.put("whitelist", wl);
        return m;
    }

    public static AutoUpdate read(ConfigurationSection s) {
        AutoUpdate a = new AutoUpdate();
        if (s == null) {
            return a;
        }
        a.enabled = s.isBoolean("enabled") ? s.getBoolean("enabled") : null;
        for (String t : s.getStringList("times")) {
            String n = normalizeTime(t);
            if (n != null && a.times.size() < MAX_TIMES) {
                a.times.add(n);
            }
        }
        for (String d : s.getStringList("days")) {
            try {
                a.days.add(DayOfWeek.valueOf(d.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // 略過無效的星期
            }
        }
        a.teleportPlayers = s.isBoolean("teleport-players") ? s.getBoolean("teleport-players") : null;
        ConfigurationSection dest = s.getConfigurationSection("destination");
        if (dest != null) {
            a.destination = "CUSTOM".equalsIgnoreCase(dest.getString("type")) ? Destination.CUSTOM : Destination.SPAWN;
            a.customLocation = StoredLocation.read(dest.getConfigurationSection("location"));
        }
        a.message = s.getString("message", "");
        for (String id : s.getStringList("whitelist")) {
            try {
                a.whitelist.add(UUID.fromString(id.trim()));
            } catch (IllegalArgumentException ignored) {
                // 略過無效的 UUID
            }
        }
        return a;
    }

    public Boolean enabled() { return enabled; }
    public void enabled(Boolean v) { enabled = v; }
    public Set<String> times() { return times; }
    public Set<DayOfWeek> days() { return days; }
    public Boolean teleportPlayers() { return teleportPlayers; }
    public void teleportPlayers(Boolean v) { teleportPlayers = v; }
    public Destination destination() { return destination; }
    public void destination(Destination d) { destination = d; }
    public StoredLocation customLocation() { return customLocation; }
    public void customLocation(StoredLocation l) { customLocation = l; }
    public String message() { return message == null ? "" : message; }
    public void message(String m) { message = m == null ? "" : m; }
    public Set<UUID> whitelist() { return whitelist; }
}
