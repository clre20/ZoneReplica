package clre20.zoneReplica.model;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 樣本底下的覆蓋區域（場地）。Boolean 為 null 代表「未設定」。 */
public final class Placement {

    private final String name;
    private String world;
    private BlockPos c;
    private Boolean copyEntities;
    private Boolean clearEntities;
    private Boolean copyContainers;
    private Boolean replaceAir;
    private AutoUpdate autoUpdate = new AutoUpdate();

    public Placement(String name) {
        this.name = name;
    }

    public Placement copy(String newName) {
        Placement p = new Placement(newName);
        p.world = world;
        p.c = c;
        p.copyEntities = copyEntities;
        p.clearEntities = clearEntities;
        p.copyContainers = copyContainers;
        p.replaceAir = replaceAir;
        p.autoUpdate = autoUpdate.copy();
        return p;
    }

    public Placement copy() {
        return copy(name);
    }

    /** 目標範圍：C 對應樣本的 A 點，大小沿用 A-B。 */
    public Cuboid target(Sample sample) {
        if (c == null || sample.a() == null || sample.b() == null) {
            return null;
        }
        Cuboid r = sample.region();
        int minX = c.x() + (r.minX() - sample.a().x());
        int minY = c.y() + (r.minY() - sample.a().y());
        int minZ = c.z() + (r.minZ() - sample.a().z());
        return Cuboid.ofSize(minX, minY, minZ, r.sizeX(), r.sizeY(), r.sizeZ());
    }

    public List<String> missing() {
        List<String> out = new ArrayList<>();
        if (c == null || world == null) {
            out.add("missing.point-c");
        }
        if (copyEntities == null) {
            out.add("missing.copy-entities");
        }
        if (clearEntities == null) {
            out.add("missing.clear-entities");
        }
        if (copyContainers == null) {
            out.add("missing.copy-containers");
        }
        if (replaceAir == null) {
            out.add("missing.replace-air");
        }
        out.addAll(autoUpdate.missing());
        return out;
    }

    public Map<String, Object> serialize() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("world", world);
        m.put("pos-c", c == null ? null : c.serialize());
        m.put("copy-entities", copyEntities);
        m.put("clear-entities", clearEntities);
        m.put("copy-containers", copyContainers);
        m.put("replace-air", replaceAir);
        m.put("auto-update", autoUpdate.serialize());
        return m;
    }

    public static Placement read(String name, ConfigurationSection s) {
        Placement p = new Placement(name);
        p.world = s.getString("world");
        p.c = BlockPos.read(s, "pos-c");
        p.copyEntities = bool(s, "copy-entities");
        p.clearEntities = bool(s, "clear-entities");
        p.copyContainers = bool(s, "copy-containers");
        p.replaceAir = bool(s, "replace-air");
        p.autoUpdate = AutoUpdate.read(s.getConfigurationSection("auto-update"));
        return p;
    }

    private static Boolean bool(ConfigurationSection s, String key) {
        return s.isBoolean(key) ? s.getBoolean(key) : null;
    }

    public String name() { return name; }
    public String world() { return world; }
    public void world(String w) { world = w; }
    public BlockPos c() { return c; }
    public void c(BlockPos p) { c = p; }
    public Boolean copyEntities() { return copyEntities; }
    public void copyEntities(Boolean v) { copyEntities = v; }
    public Boolean clearEntities() { return clearEntities; }
    public void clearEntities(Boolean v) { clearEntities = v; }
    public Boolean copyContainers() { return copyContainers; }
    public void copyContainers(Boolean v) { copyContainers = v; }
    public Boolean replaceAir() { return replaceAir; }
    public void replaceAir(Boolean v) { replaceAir = v; }
    public AutoUpdate autoUpdate() { return autoUpdate; }
}
