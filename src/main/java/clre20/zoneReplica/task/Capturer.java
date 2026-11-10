package clre20.zoneReplica.task;

import clre20.zoneReplica.model.Cuboid;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;

/** 分批把世界中的範圍讀進 CaptureBuffer（樣本記錄與覆蓋前備份共用）。 */
final class Capturer {

    final Cuboid box;
    final ColumnOrder order;
    final CaptureBuffer buffer;
    private final ChunkGate gate;
    private long cursor;
    private int entered = -1;
    private ChunkSnapshot snap;

    Capturer(Cuboid box, ChunkGate gate) {
        this.box = box;
        this.order = new ColumnOrder(box);
        this.buffer = new CaptureBuffer(box.sizeX(), box.sizeY(), box.sizeZ());
        this.gate = gate;
    }

    long cursor() {
        return cursor;
    }

    long total() {
        return order.total;
    }

    /** @return 是否已讀完 */
    boolean step(World world, int budget, long deadline) {
        while (budget > 0 && cursor < order.total) {
            int ci = order.columnOf(cursor);
            ColumnOrder.Column col = order.columns.get(ci);
            if (ci != entered) {
                if (!gate.ensure(world, col.cx(), col.cz(), true)) {
                    return false;
                }
                snap = world.getChunkAt(col.cx(), col.cz()).getChunkSnapshot(false, false, false);
                WorldOps.readExtras(world, col, box, buffer, true);
                entered = ci;
                prefetch(world, ci);
                budget -= 256;
            }
            long local = cursor - col.offset();
            long n = Math.min(col.count() - local, budget);
            WorldOps.readBlocks(snap, col, box, buffer, local, local + n);
            cursor += n;
            budget -= (int) n;
            if (local + n >= col.count()) {
                gate.release(world, col.cx(), col.cz());
                snap = null;
            }
            if (System.nanoTime() > deadline) {
                break;
            }
        }
        return cursor >= order.total;
    }

    private void prefetch(World world, int ci) {
        for (int i = ci + 1; i <= ci + 2 && i < order.columns.size(); i++) {
            ColumnOrder.Column c = order.columns.get(i);
            gate.prefetch(world, c.cx(), c.cz());
        }
    }
}
