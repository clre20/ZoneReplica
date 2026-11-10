package clre20.zoneReplica.task;

import clre20.zoneReplica.model.Cuboid;
import clre20.zoneReplica.storage.Snapshot;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.structure.Structure;
import org.bukkit.structure.StructureManager;
import org.bukkit.util.BlockVector;
import org.bukkit.util.BoundingBox;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * 在主執行緒上讀寫世界的工具方法。全部只透過 Bukkit/Paper API，不使用 NMS：
 * 方塊實體以 1×1×1 的原版結構保存（含完整 NBT），實體以 Paper 的實體序列化保存。
 */
final class WorldOps {

    /** 插件自己產生的外框實體標記，記錄與清除時略過。 */
    static NamespacedKey outlineKey;

    private static final BlockVector ONE = new BlockVector(1, 1, 1);
    private static final Random RANDOM = new Random(0L);

    private WorldOps() {
    }

    static boolean isOurs(Entity e) {
        return outlineKey != null && e.getPersistentDataContainer().has(outlineKey, PersistentDataType.BYTE);
    }

    /** 一個區塊柱在範圍內的實體範圍。 */
    static BoundingBox columnBox(ColumnOrder.Column col, Cuboid box) {
        return new BoundingBox(col.x0(), box.minY(), col.z0(), col.x1() + 1, box.maxY() + 1, col.z1() + 1);
    }

    static boolean inColumn(Location loc, ColumnOrder.Column col, Cuboid box) {
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        return x >= col.x0() && x <= col.x1() && z >= col.z0() && z <= col.z1() && y >= box.minY() && y <= box.maxY();
    }

    // ---------- 記錄 ----------

    /** 讀取區塊柱中的一段方塊到快照。 */
    static void readBlocks(ChunkSnapshot snap, ColumnOrder.Column col, Cuboid box, CaptureBuffer buf,
                           long from, long to) {
        int w = col.width();
        int layer = w * col.depth();
        for (long i = from; i < to; i++) {
            int li = (int) i;
            int y = box.minY() + li / layer;
            int rem = li % layer;
            int z = col.z0() + rem / w;
            int x = col.x0() + rem % w;
            BlockData data = snap.getBlockData(x & 15, y, z & 15);
            buf.blocks[buf.index(x - box.minX(), y - box.minY(), z - box.minZ())] = buf.indexOf(data);
        }
    }

    /** 記錄區塊柱內的方塊實體與實體（進入區塊柱時呼叫一次）。 */
    @SuppressWarnings("deprecation")
    static void readExtras(World world, ColumnOrder.Column col, Cuboid box, CaptureBuffer buf, boolean entities) {
        StructureManager sm = Bukkit.getStructureManager();
        for (BlockState state : world.getChunkAt(col.cx(), col.cz()).getTileEntities(false)) {
            int x = state.getX();
            int y = state.getY();
            int z = state.getZ();
            if (x < col.x0() || x > col.x1() || z < col.z0() || z > col.z1() || y < box.minY() || y > box.maxY()) {
                continue;
            }
            try {
                Structure st = sm.createStructure();
                st.fill(new Location(world, x, y, z), ONE, false);
                ByteArrayOutputStream out = new ByteArrayOutputStream(256);
                sm.saveStructure(out, st);
                buf.tiles.put(buf.index(x - box.minX(), y - box.minY(), z - box.minZ()), out.toByteArray());
            } catch (IOException | RuntimeException e) {
                // 無法保存的方塊實體只保留方塊狀態
            }
        }
        if (!entities) {
            return;
        }
        for (Entity e : world.getNearbyEntities(columnBox(col, box))) {
            if (e instanceof Player || isOurs(e) || !e.isValid() || !inColumn(e.getLocation(), col, box)) {
                continue;
            }
            try {
                byte[] data = Bukkit.getUnsafe().serializeEntity(e);
                Location l = e.getLocation();
                buf.entities.add(new Snapshot.EntityEntry(l.getX() - box.minX(), l.getY() - box.minY(),
                        l.getZ() - box.minZ(), l.getYaw(), l.getPitch(), data));
            } catch (RuntimeException ex) {
                // 不能序列化的實體（例如正在騎乘的玩家相關）略過
            }
        }
    }

    // ---------- 放置 ----------

    /** 放置一般方塊，不觸發物理更新；方塊相同時略過。 */
    static void placeBlock(World world, int x, int y, int z, BlockData data) {
        Block b = world.getBlockAt(x, y, z);
        if (!b.getBlockData().equals(data)) {
            b.setBlockData(data, false);
        }
    }

    /** 放置方塊實體（含 NBT）；不複製容器內容時清空。 */
    static boolean placeTile(World world, int x, int y, int z, byte[] nbt, boolean keepContents) {
        try {
            Structure st = Bukkit.getStructureManager().loadStructure(new ByteArrayInputStream(nbt));
            st.place(new Location(world, x, y, z), false, StructureRotation.NONE, Mirror.NONE, 0, 1f, RANDOM);
        } catch (IOException | RuntimeException e) {
            return false;
        }
        if (!keepContents) {
            BlockState state = world.getBlockAt(x, y, z).getState(false);
            if (state instanceof BlockInventoryHolder holder) {
                holder.getInventory().clear();
            }
        }
        return true;
    }

    /** 清除區塊柱範圍內的實體（玩家與插件自己的外框除外）。 */
    static int clearEntities(World world, ColumnOrder.Column col, Cuboid box) {
        int n = 0;
        for (Entity e : world.getNearbyEntities(columnBox(col, box))) {
            if (e instanceof Player || isOurs(e) || !inColumn(e.getLocation(), col, box)) {
                continue;
            }
            if (e.getPassengers().stream().anyMatch(p -> p instanceof Player)) {
                e.eject();
            }
            e.remove();
            n++;
        }
        return n;
    }

    @SuppressWarnings("deprecation")
    static boolean spawnEntity(World world, Snapshot.EntityEntry entry, int minX, int minY, int minZ) {
        try {
            Entity e = Bukkit.getUnsafe().deserializeEntity(entry.data(), world, false);
            Location loc = new Location(world, minX + entry.x(), minY + entry.y(), minZ + entry.z(),
                    entry.yaw(), entry.pitch());
            return e.spawnAt(loc);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** 把調色盤字串轉成 BlockData；新版本已移除的方塊改為空氣。 */
    static BlockData[] parsePalette(String[] palette, int[] unknownOut) {
        BlockData[] out = new BlockData[palette.length];
        int unknown = 0;
        for (int i = 0; i < palette.length; i++) {
            try {
                out[i] = Bukkit.createBlockData(palette[i]);
            } catch (IllegalArgumentException e) {
                out[i] = Material.AIR.createBlockData();
                unknown++;
            }
        }
        if (unknownOut != null && unknownOut.length > 0) {
            unknownOut[0] = unknown;
        }
        return out;
    }
}
