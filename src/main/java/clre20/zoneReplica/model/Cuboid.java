package clre20.zoneReplica.model;

import org.bukkit.Location;
import org.bukkit.util.BoundingBox;

/** 含頭含尾的方塊範圍。 */
public record Cuboid(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public static Cuboid of(BlockPos a, BlockPos b) {
        return new Cuboid(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()),
                Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
    }

    public static Cuboid ofSize(int minX, int minY, int minZ, int sx, int sy, int sz) {
        return new Cuboid(minX, minY, minZ, minX + sx - 1, minY + sy - 1, minZ + sz - 1);
    }

    public int sizeX() { return maxX - minX + 1; }
    public int sizeY() { return maxY - minY + 1; }
    public int sizeZ() { return maxZ - minZ + 1; }

    public long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    public BlockPos min() {
        return new BlockPos(minX, minY, minZ);
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean contains(Location loc) {
        return contains(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public boolean intersects(Cuboid o) {
        return minX <= o.maxX && maxX >= o.minX && minY <= o.maxY && maxY >= o.minY
                && minZ <= o.maxZ && maxZ >= o.minZ;
    }

    public BoundingBox toBoundingBox() {
        return new BoundingBox(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
    }

    public String sizeText() {
        return sizeX() + "×" + sizeY() + "×" + sizeZ();
    }

    @Override
    public String toString() {
        return "(" + minX + ", " + minY + ", " + minZ + ") ~ (" + maxX + ", " + maxY + ", " + maxZ + ")";
    }
}
