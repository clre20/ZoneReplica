package clre20.zoneReplica.storage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * .zrs 檔案格式（GZIP 壓縮）：
 * <pre>
 * int   magic "ZRS1"、int 格式版本、int Minecraft 資料版本
 * UTF   世界名稱、int 最小角 x、y、z
 * int   sizeX、sizeY、sizeZ
 * int   調色盤數量，接著每個 BlockData 字串（UTF）
 * 方塊：連續相同索引以 (長度 varint, 索引 varint) 記錄（大片空氣只占幾個位元組）
 * int   方塊實體數量，每個 (索引 int, 長度 int, 結構 NBT)
 * int   實體數量，每個 (x, y, z double, yaw, pitch float, 長度 int, 實體資料)
 * </pre>
 * 只在非同步 I/O 執行緒呼叫。
 */
public final class SnapshotIO {

    private static final int MAGIC = 0x5A525331; // "ZRS1"
    private static final int FORMAT = 1;
    private static final int MAX_DIM = 1 << 16;

    private SnapshotIO() {
    }

    public static void write(File file, Snapshot s) throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create directory " + dir);
        }
        File tmp = new File(dir, file.getName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(new FileOutputStream(tmp), 1 << 16), 1 << 16))) {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT);
            out.writeInt(s.dataVersion());
            out.writeUTF(s.world());
            out.writeInt(s.originX());
            out.writeInt(s.originY());
            out.writeInt(s.originZ());
            out.writeInt(s.sizeX());
            out.writeInt(s.sizeY());
            out.writeInt(s.sizeZ());
            String[] palette = s.palette();
            out.writeInt(palette.length);
            for (String p : palette) {
                out.writeUTF(p);
            }
            int[] blocks = s.blocks();
            int i = 0;
            while (i < blocks.length) {
                int v = blocks[i];
                int run = 1;
                while (i + run < blocks.length && blocks[i + run] == v) {
                    run++;
                }
                writeVarInt(out, run);
                writeVarInt(out, v);
                i += run;
            }
            out.writeInt(s.tiles().size());
            for (Map.Entry<Integer, byte[]> e : s.tiles().entrySet()) {
                out.writeInt(e.getKey());
                out.writeInt(e.getValue().length);
                out.write(e.getValue());
            }
            out.writeInt(s.entities().size());
            for (Snapshot.EntityEntry e : s.entities()) {
                out.writeDouble(e.x());
                out.writeDouble(e.y());
                out.writeDouble(e.z());
                out.writeFloat(e.yaw());
                out.writeFloat(e.pitch());
                out.writeInt(e.data().length);
                out.write(e.data());
            }
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static Snapshot read(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(new FileInputStream(file), 1 << 16), 1 << 16))) {
            if (in.readInt() != MAGIC) {
                throw new IOException("Not a ZoneReplica snapshot: " + file.getName());
            }
            int format = in.readInt();
            if (format > FORMAT) {
                throw new IOException("Snapshot format " + format + " is newer than this plugin supports");
            }
            int dataVersion = in.readInt();
            String world = in.readUTF();
            int ox = in.readInt();
            int oy = in.readInt();
            int oz = in.readInt();
            int sx = in.readInt();
            int sy = in.readInt();
            int sz = in.readInt();
            if (sx <= 0 || sy <= 0 || sz <= 0 || sx > MAX_DIM || sy > MAX_DIM || sz > MAX_DIM
                    || (long) sx * sy * sz > Integer.MAX_VALUE - 8) {
                throw new IOException("Invalid snapshot size " + sx + "x" + sy + "x" + sz);
            }
            int paletteSize = in.readInt();
            if (paletteSize <= 0 || paletteSize > 1_000_000) {
                throw new IOException("Invalid palette size " + paletteSize);
            }
            String[] palette = new String[paletteSize];
            for (int i = 0; i < paletteSize; i++) {
                palette[i] = in.readUTF();
            }
            int total = sx * sy * sz;
            int[] blocks = new int[total];
            int i = 0;
            while (i < total) {
                int run = readVarInt(in);
                int v = readVarInt(in);
                if (run <= 0 || i + run > total || v < 0 || v >= paletteSize) {
                    throw new IOException("Corrupted block data");
                }
                if (v != 0) {
                    java.util.Arrays.fill(blocks, i, i + run, v);
                }
                i += run;
            }
            int tileCount = in.readInt();
            if (tileCount < 0 || tileCount > total) {
                throw new IOException("Invalid tile count");
            }
            Map<Integer, byte[]> tiles = new HashMap<>(Math.max(16, tileCount * 2));
            for (int t = 0; t < tileCount; t++) {
                int idx = in.readInt();
                byte[] data = readBytes(in);
                if (idx >= 0 && idx < total) {
                    tiles.put(idx, data);
                }
            }
            int entityCount = in.readInt();
            if (entityCount < 0 || entityCount > 10_000_000) {
                throw new IOException("Invalid entity count");
            }
            List<Snapshot.EntityEntry> entities = new ArrayList<>(entityCount);
            for (int e = 0; e < entityCount; e++) {
                double x = in.readDouble();
                double y = in.readDouble();
                double z = in.readDouble();
                float yaw = in.readFloat();
                float pitch = in.readFloat();
                entities.add(new Snapshot.EntityEntry(x, y, z, yaw, pitch, readBytes(in)));
            }
            return new Snapshot(world, ox, oy, oz, sx, sy, sz, palette, blocks, tiles, entities, dataVersion);
        } catch (EOFException e) {
            throw new IOException("Snapshot file is truncated: " + file.getName(), e);
        }
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > 64 * 1024 * 1024) {
            throw new IOException("Invalid data length " + len);
        }
        byte[] data = new byte[len];
        in.readFully(data);
        return data;
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
            if (shift > 28) {
                throw new IOException("VarInt too long");
            }
        }
    }
}
