package clre20.zoneReplica.model;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 樣本：A、B 兩點框出的範圍，以及底下所有場地。 */
public final class Sample {

    public static final int FORMAT_VERSION = 1;

    private final String name;
    private String world;
    private BlockPos a;
    private BlockPos b;
    private Boolean removeMarkers;
    private String snapshotTime;
    private final LinkedHashMap<String, Placement> placements = new LinkedHashMap<>();

    public Sample(String name) {
        this.name = name;
    }

    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public String key() {
        return key(name);
    }

    public Cuboid region() {
        return a == null || b == null ? null : Cuboid.of(a, b);
    }

    public List<String> missing() {
        List<String> out = new ArrayList<>();
        if (a == null) {
            out.add("missing.point-a");
        }
        if (b == null) {
            out.add("missing.point-b");
        }
        if (removeMarkers == null) {
            out.add("missing.remove-markers");
        }
        return out;
    }

    public Placement placement(String placementName) {
        return placements.get(key(placementName));
    }

    public Collection<Placement> placements() {
        return placements.values();
    }

    public void putPlacement(Placement p) {
        placements.put(key(p.name()), p);
    }

    public Placement removePlacement(String placementName) {
        return placements.remove(key(placementName));
    }

    public YamlConfiguration toYaml() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("format-version", FORMAT_VERSION);
        y.set("name", name);
        y.set("world", world);
        y.set("pos-a", a == null ? null : a.serialize());
        y.set("pos-b", b == null ? null : b.serialize());
        y.set("remove-markers", removeMarkers);
        y.set("snapshot-time", snapshotTime);
        Map<String, Object> ps = new LinkedHashMap<>();
        for (Placement p : placements.values()) {
            ps.put(p.name(), p.serialize());
        }
        y.createSection("placements", ps);
        return y;
    }

    public static Sample fromYaml(String fallbackName, YamlConfiguration y) {
        Sample s = new Sample(y.getString("name", fallbackName));
        s.world = y.getString("world");
        s.a = BlockPos.read(y, "pos-a");
        s.b = BlockPos.read(y, "pos-b");
        s.removeMarkers = y.isBoolean("remove-markers") ? y.getBoolean("remove-markers") : null;
        s.snapshotTime = y.getString("snapshot-time");
        ConfigurationSection ps = y.getConfigurationSection("placements");
        if (ps != null) {
            for (String pn : ps.getKeys(false)) {
                ConfigurationSection sec = ps.getConfigurationSection(pn);
                if (sec != null) {
                    s.putPlacement(Placement.read(pn, sec));
                }
            }
        }
        return s;
    }

    public String name() { return name; }
    public String world() { return world; }
    public void world(String w) { world = w; }
    public BlockPos a() { return a; }
    public void a(BlockPos p) { a = p; }
    public BlockPos b() { return b; }
    public void b(BlockPos p) { b = p; }
    public Boolean removeMarkers() { return removeMarkers; }
    public void removeMarkers(Boolean v) { removeMarkers = v; }
    public String snapshotTime() { return snapshotTime; }
    public void snapshotTime(String t) { snapshotTime = t; }
}
