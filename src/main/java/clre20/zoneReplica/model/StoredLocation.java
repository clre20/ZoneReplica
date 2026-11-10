package clre20.zoneReplica.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Map;

/** 以世界名稱記錄的位置（含朝向），不持有 World 物件。 */
public record StoredLocation(String world, double x, double y, double z, float yaw, float pitch) {

    public static StoredLocation of(Location loc) {
        return new StoredLocation(loc.getWorld() == null ? "" : loc.getWorld().getName(),
                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
    }

    /** 世界未載入時回傳 null。 */
    public Location toLocation() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x, y, z, yaw, pitch);
    }

    public Map<String, Object> serialize() {
        Map<String, Object> m = new LinkedHashMap<>(8);
        m.put("world", world);
        m.put("x", round(x));
        m.put("y", round(y));
        m.put("z", round(z));
        m.put("yaw", (double) Math.round(yaw * 10f) / 10.0);
        m.put("pitch", (double) Math.round(pitch * 10f) / 10.0);
        return m;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public static StoredLocation read(ConfigurationSection s) {
        if (s == null || !s.isString("world")) {
            return null;
        }
        return new StoredLocation(s.getString("world"), s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
    }

    public String display() {
        return world + " " + String.format("%.1f, %.1f, %.1f", x, y, z);
    }
}
