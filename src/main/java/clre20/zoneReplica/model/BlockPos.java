package clre20.zoneReplica.model;

import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Map;

/** 方塊座標（不持有 World 物件）。 */
public record BlockPos(int x, int y, int z) {

    public static BlockPos of(Block block) {
        return new BlockPos(block.getX(), block.getY(), block.getZ());
    }

    public BlockPos add(int dx, int dy, int dz) {
        return new BlockPos(x + dx, y + dy, z + dz);
    }

    public Map<String, Object> serialize() {
        Map<String, Object> m = new LinkedHashMap<>(4);
        m.put("x", x);
        m.put("y", y);
        m.put("z", z);
        return m;
    }

    public static BlockPos read(ConfigurationSection parent, String key) {
        if (parent == null) {
            return null;
        }
        ConfigurationSection s = parent.getConfigurationSection(key);
        if (s == null || !s.contains("x") || !s.contains("y") || !s.contains("z")) {
            return null;
        }
        return new BlockPos(s.getInt("x"), s.getInt("y"), s.getInt("z"));
    }

    @Override
    public String toString() {
        return x + ", " + y + ", " + z;
    }
}
