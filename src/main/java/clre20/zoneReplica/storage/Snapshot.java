package clre20.zoneReplica.storage;

import java.util.List;
import java.util.Map;

/**
 * 一份區域快照（樣本快照或覆蓋前備份）。
 * 方塊以調色盤索引記錄，順序為 index = (y * sizeZ + z) * sizeX + x。
 * 方塊實體（箱子、告示牌、生怪磚…）以原版結構 NBT 記錄，保留完整資料。
 */
public final class Snapshot {

    /** 實體：相對於範圍最小角的位置，以及 Paper 序列化後的資料。 */
    public record EntityEntry(double x, double y, double z, float yaw, float pitch, byte[] data) {
    }

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final String[] palette;
    private final int[] blocks;
    private final Map<Integer, byte[]> tiles;
    private final List<EntityEntry> entities;
    private final int dataVersion;
    private final String world;
    private final int originX;
    private final int originY;
    private final int originZ;

    public Snapshot(String world, int originX, int originY, int originZ,
                    int sizeX, int sizeY, int sizeZ, String[] palette, int[] blocks,
                    Map<Integer, byte[]> tiles, List<EntityEntry> entities, int dataVersion) {
        this.world = world == null ? "" : world;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.palette = palette;
        this.blocks = blocks;
        this.tiles = tiles;
        this.entities = entities;
        this.dataVersion = dataVersion;
    }

    public int index(int x, int y, int z) {
        return (y * sizeZ + z) * sizeX + x;
    }

    public int sizeX() { return sizeX; }
    public int sizeY() { return sizeY; }
    public int sizeZ() { return sizeZ; }
    public long volume() { return (long) sizeX * sizeY * sizeZ; }
    public String[] palette() { return palette; }
    public int[] blocks() { return blocks; }
    public Map<Integer, byte[]> tiles() { return tiles; }
    public List<EntityEntry> entities() { return entities; }
    public int dataVersion() { return dataVersion; }
    /** 記錄時的世界名稱與最小角座標（備份用來還原到原本的位置）。 */
    public String world() { return world; }
    public int originX() { return originX; }
    public int originY() { return originY; }
    public int originZ() { return originZ; }
}
