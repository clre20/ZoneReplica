package clre20.zoneReplica.task;

import clre20.zoneReplica.storage.Snapshot;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 記錄中的快照：調色盤以 BlockData 為鍵，避免每格產生字串。 */
final class CaptureBuffer {

    final int sizeX;
    final int sizeY;
    final int sizeZ;
    final int[] blocks;
    private final Map<BlockData, Integer> paletteIndex = new HashMap<>();
    private final List<String> palette = new ArrayList<>();
    final Map<Integer, byte[]> tiles = new HashMap<>();
    final List<Snapshot.EntityEntry> entities = new ArrayList<>();
    final int airIndex;

    CaptureBuffer(int sizeX, int sizeY, int sizeZ) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.blocks = new int[sizeX * sizeY * sizeZ];
        // 索引 0 固定為空氣
        this.airIndex = indexOf(Material.AIR.createBlockData());
    }

    int index(int x, int y, int z) {
        return (y * sizeZ + z) * sizeX + x;
    }

    int indexOf(BlockData data) {
        Integer i = paletteIndex.get(data);
        if (i == null) {
            i = palette.size();
            paletteIndex.put(data, i);
            palette.add(data.getAsString());
        }
        return i;
    }

    Snapshot build(String world, int originX, int originY, int originZ) {
        return new Snapshot(world, originX, originY, originZ, sizeX, sizeY, sizeZ,
                palette.toArray(new String[0]), blocks, tiles, entities, dataVersion());
    }

    @SuppressWarnings("deprecation")
    static int dataVersion() {
        try {
            return Bukkit.getUnsafe().getDataVersion();
        } catch (Throwable t) {
            return 0;
        }
    }
}
