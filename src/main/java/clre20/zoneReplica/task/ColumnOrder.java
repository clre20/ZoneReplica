package clre20.zoneReplica.task;

import clre20.zoneReplica.model.Cuboid;

import java.util.ArrayList;
import java.util.List;

/**
 * 以區塊柱（16×16）為單位排列範圍內的方塊，讓處理時一次只需要載入少數區塊。
 * 排列只由範圍決定，重啟後以同一個游標就能接續。
 */
final class ColumnOrder {

    record Column(int cx, int cz, int x0, int x1, int z0, int z1, long offset, long count) {
        int width() {
            return x1 - x0 + 1;
        }

        int depth() {
            return z1 - z0 + 1;
        }
    }

    final Cuboid box;
    final List<Column> columns;
    final long total;

    ColumnOrder(Cuboid box) {
        this.box = box;
        List<Column> list = new ArrayList<>();
        long offset = 0;
        int height = box.sizeY();
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            int x0 = Math.max(box.minX(), cx << 4);
            int x1 = Math.min(box.maxX(), (cx << 4) + 15);
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                int z0 = Math.max(box.minZ(), cz << 4);
                int z1 = Math.min(box.maxZ(), (cz << 4) + 15);
                long count = (long) (x1 - x0 + 1) * (z1 - z0 + 1) * height;
                list.add(new Column(cx, cz, x0, x1, z0, z1, offset, count));
                offset += count;
            }
        }
        this.columns = list;
        this.total = offset;
    }

    /** 游標所在的區塊柱索引。 */
    int columnOf(long cursor) {
        int lo = 0;
        int hi = columns.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (columns.get(mid).offset() <= cursor) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }
}
