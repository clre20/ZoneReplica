package clre20.zoneReplica.task;

import clre20.zoneReplica.ZoneReplica;
import clre20.zoneReplica.model.Cuboid;
import clre20.zoneReplica.model.Sample;
import clre20.zoneReplica.storage.Snapshot;
import clre20.zoneReplica.storage.SnapshotIO;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/** 記錄樣本快照：讀取 A-B 範圍的方塊、方塊實體與實體，寫入 .zrs。 */
final class CaptureTask extends ZoneTask {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private enum Phase { CAPTURE, SAVE }

    private Phase phase = Phase.CAPTURE;
    private String worldName;
    private Capturer capturer;
    private ChunkGate gate;
    private CompletableFuture<Void> saving;
    private int markerA = -1;
    private int markerB = -1;

    CaptureTask(ZoneReplica plugin, String id, String sampleName, UUID requester, boolean console, long createdAt) {
        super(plugin, id, Kind.CAPTURE, sampleName, null, requester, console, false, createdAt);
    }

    @Override
    boolean needsCountdown() {
        return false;
    }

    @Override
    void begin() {
        Sample s = plugin.store().get(sampleName);
        if (s == null || s.region() == null) {
            fail("reason.deleted");
            return;
        }
        World world = Bukkit.getWorld(s.world());
        if (world == null) {
            fail("reason.world-not-loaded");
            return;
        }
        Cuboid box = s.region();
        if (box.volume() > plugin.config().maxVolume()) {
            fail("reason.too-large");
            return;
        }
        worldName = world.getName();
        gate = new ChunkGate(plugin, world);
        capturer = new Capturer(box, gate);
        if (Boolean.TRUE.equals(s.removeMarkers())) {
            markerA = capturer.buffer.index(s.a().x() - box.minX(), s.a().y() - box.minY(), s.a().z() - box.minZ());
            markerB = capturer.buffer.index(s.b().x() - box.minX(), s.b().y() - box.minY(), s.b().z() - box.minZ());
        }
    }

    @Override
    boolean step(long deadline, int budget) {
        if (failure != null) {
            return true;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            fail("reason.world-not-loaded");
            return true;
        }
        switch (phase) {
            case CAPTURE -> {
                boolean done;
                try {
                    done = capturer.step(world, budget * 4, deadline);
                } catch (IllegalStateException e) {
                    fail("reason.chunk-failed");
                    return true;
                }
                if (done) {
                    gate.releaseAll(world);
                    CaptureBuffer buf = capturer.buffer;
                    for (int idx : new int[]{markerA, markerB}) {
                        if (idx >= 0) {
                            buf.blocks[idx] = buf.airIndex;
                            buf.tiles.remove(idx);
                        }
                    }
                    Cuboid box = capturer.box;
                    Snapshot snap = buf.build(worldName, box.minX(), box.minY(), box.minZ());
                    File file = plugin.store().snapshotFile(sampleName);
                    capturer = null;
                    saving = CompletableFuture.runAsync(() -> {
                        try {
                            SnapshotIO.write(file, snap);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }, plugin.ioExecutor());
                    phase = Phase.SAVE;
                }
            }
            case SAVE -> {
                if (!saving.isDone()) {
                    return false;
                }
                try {
                    saving.join();
                } catch (RuntimeException e) {
                    plugin.getLogger().log(Level.SEVERE, "snapshot " + sampleName, e);
                    fail("reason.io");
                    return true;
                }
                Sample s = plugin.store().get(sampleName);
                if (s != null) {
                    s.snapshotTime(LocalDateTime.now().format(TIME));
                    plugin.store().markDirty(s);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public float progress() {
        if (phase == Phase.SAVE) {
            return 0.99f;
        }
        if (capturer == null || capturer.total() == 0) {
            return 0f;
        }
        return Math.min(0.98f, (float) capturer.cursor() / capturer.total());
    }

    @Override
    void stop() {
        if (gate != null) {
            gate.releaseAll(Bukkit.getWorld(gate.worldName()));
        }
        capturer = null;
    }
}
