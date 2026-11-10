package clre20.zoneReplica.task;

import clre20.zoneReplica.ZoneReplica;
import clre20.zoneReplica.model.AutoUpdate;
import clre20.zoneReplica.model.Cuboid;
import clre20.zoneReplica.model.Placement;
import clre20.zoneReplica.model.Sample;
import clre20.zoneReplica.storage.Snapshot;
import clre20.zoneReplica.storage.SnapshotIO;
import clre20.zoneReplica.util.Placeholders;
import clre20.zoneReplica.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * 覆蓋（UPDATE）或復原（UNDO）一個場地。
 * <p>覆蓋順序：載入快照 → 送走玩家 → 備份目標區域 → 寫入備份 → 逐區塊柱放置方塊（先清除原有實體）→ 放回實體。
 * 復原：載入備份 → 送走玩家 → 放置（清除實體、完整覆蓋）→ 放回實體 → 刪除該份備份。</p>
 */
final class PlaceTask extends ZoneTask {

    enum Phase { LOAD, BACKUP, BACKUP_SAVE, PLACE, ENTITIES, CLEANUP }

    private record Loaded(Snapshot snapshot, int seq) {
    }

    private Phase phase = Phase.LOAD;
    /** 覆蓋：本次備份序號；復原：要還原的備份序號。 */
    private int backupSeq = -1;
    /** 被中斷的前一個任務留下的備份，沿用而不再備份。 */
    private int reuseBackupSeq = -1;
    private long cursor;
    private int entityCursor;
    private boolean resumed;

    private String worldName;
    private Cuboid target;
    private ColumnOrder order;
    private ChunkGate gate;
    private Snapshot source;
    private BlockData[] paletteData;
    private boolean[] paletteAir;
    private BitSet tileMask;
    private Capturer backup;
    private CompletableFuture<?> pending;
    private int entered = -1;
    private int minHeight;
    private int maxHeight;

    private boolean copyEntities;
    private boolean clearEntities;
    private boolean copyContainers;
    private boolean replaceAir;
    private int failedTiles;
    private int failedEntities;

    PlaceTask(ZoneReplica plugin, String id, Kind kind, String sampleName, String placementName,
              UUID requester, boolean console, boolean auto, long createdAt) {
        super(plugin, id, kind, sampleName, placementName, requester, console, auto, createdAt);
    }

    void reuseBackup(int seq) {
        this.reuseBackupSeq = seq;
    }

    @Override
    int completedBackupSeq() {
        if (kind == Kind.UPDATE && (phase.ordinal() >= Phase.PLACE.ordinal() || (resumed && backupSeq >= 0))) {
            return backupSeq;
        }
        return -1;
    }

    private Placement placement() {
        Sample s = plugin.store().get(sampleName);
        return s == null ? null : s.placement(placementName);
    }

    @Override
    boolean needsCountdown() {
        if (resumed) {
            return false;
        }
        if (auto) {
            return true;
        }
        Placement p = placement();
        return p != null && p.autoUpdate().teleportEnabled();
    }

    // ---------- 倒數與送走玩家 ----------

    private List<Player> playersInside() {
        List<Player> out = new ArrayList<>();
        Sample s = plugin.store().get(sampleName);
        Placement p = s == null ? null : s.placement(placementName);
        if (p == null) {
            return out;
        }
        Cuboid box = p.target(s);
        World w = p.world() == null ? null : Bukkit.getWorld(p.world());
        if (box == null || w == null) {
            return out;
        }
        for (Player pl : w.getPlayers()) {
            if (box.contains(pl.getLocation())) {
                out.add(pl);
            }
        }
        return out;
    }

    @Override
    void warnPlayers(int secondsLeft) {
        Placeholders ph = Placeholders.of("seconds", secondsLeft, "sample", sampleName, "placement", placementName);
        for (Player pl : playersInside()) {
            plugin.messages().send(pl, "auto-warning", ph);
            if (plugin.config().warningTitle()) {
                pl.showTitle(Title.title(plugin.messages().component("auto-warning-title", ph),
                        plugin.messages().component("auto-warning-subtitle", ph),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(1500), Duration.ofMillis(300))));
            }
        }
    }

    private void teleportPlayers(Placement p) {
        AutoUpdate au = p.autoUpdate();
        if (!au.teleportEnabled()) {
            return;
        }
        String custom = au.message();
        for (Player pl : playersInside()) {
            if (au.whitelist().contains(pl.getUniqueId())) {
                plugin.messages().send(pl, "whitelist-warning");
                continue;
            }
            Location dest = null;
            if (au.destination() == AutoUpdate.Destination.CUSTOM && au.customLocation() != null) {
                dest = au.customLocation().toLocation();
            }
            if (dest == null) {
                dest = pl.getWorld().getSpawnLocation();
            }
            pl.teleportAsync(dest);
            if (custom != null && !custom.isBlank()) {
                plugin.messages().sendRaw(pl, Text.parse(custom));
            } else {
                plugin.messages().send(pl, "teleported");
            }
        }
    }

    // ---------- 執行 ----------

    @Override
    void begin() {
        Sample s = plugin.store().get(sampleName);
        Placement p = s == null ? null : s.placement(placementName);
        if (p == null) {
            fail("reason.deleted");
            return;
        }
        if (kind == Kind.UPDATE) {
            copyEntities = Boolean.TRUE.equals(p.copyEntities());
            clearEntities = Boolean.TRUE.equals(p.clearEntities());
            copyContainers = Boolean.TRUE.equals(p.copyContainers());
            replaceAir = Boolean.TRUE.equals(p.replaceAir());
        } else {
            copyEntities = true;
            clearEntities = true;
            copyContainers = true;
            replaceAir = true;
        }
        teleportPlayers(p);

        File snapshotFile = plugin.store().snapshotFile(sampleName);
        boolean resume = resumed;
        int reuse = reuseBackupSeq;
        int knownSeq = backupSeq;
        Kind k = kind;
        phase = Phase.LOAD;
        pending = CompletableFuture.supplyAsync(() -> {
            try {
                List<Integer> seqs = plugin.store().backupSeqs(sampleName, placementName);
                if (k == Kind.UPDATE) {
                    int seq = resume ? knownSeq : reuse >= 0 ? reuse : (seqs.isEmpty() ? 1 : seqs.get(0) + 1);
                    return new Loaded(SnapshotIO.read(snapshotFile), seq);
                }
                int seq = resume ? knownSeq : (seqs.isEmpty() ? -1 : seqs.get(0));
                if (seq < 0) {
                    return new Loaded(null, -1);
                }
                return new Loaded(SnapshotIO.read(plugin.store().backupFile(sampleName, placementName, seq)), seq);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "load " + sampleName + "/" + placementName, e);
                return null;
            }
        }, plugin.ioExecutor());
    }

    /** 重啟後還原：已開始修改世界的任務從中斷的位置接續；其餘從頭開始。 */
    void restore(ConfigurationSection s) {
        Phase saved;
        try {
            saved = Phase.valueOf(s.getString("phase", "LOAD"));
        } catch (IllegalArgumentException e) {
            saved = Phase.LOAD;
        }
        int seq = s.getInt("backup-seq", -1);
        if (saved.ordinal() >= Phase.PLACE.ordinal() && seq >= 0) {
            resumed = true;
            resumePhase = saved;
            resumeCursor = Math.max(0, s.getLong("cursor", 0));
            resumeEntityCursor = Math.max(0, s.getInt("entity-cursor", 0));
            backupSeq = seq;
            countdownDone = true;
        }
    }

    private Phase resumePhase = Phase.PLACE;
    private long resumeCursor;
    private int resumeEntityCursor;

    @Override
    void save(ConfigurationSection s) {
        super.save(s);
        boolean waitingResume = resumed && (phase == Phase.LOAD || state != State.RUNNING);
        s.set("phase", (waitingResume ? resumePhase : phase).name());
        s.set("backup-seq", backupSeq);
        s.set("cursor", waitingResume ? resumeCursor : cursor);
        s.set("entity-cursor", waitingResume ? resumeEntityCursor : entityCursor);
    }

    @Override
    boolean step(long deadline, int budget) {
        if (failure != null) {
            return true;
        }
        try {
            return switch (phase) {
                case LOAD -> stepLoad();
                case BACKUP -> stepBackup(deadline, budget);
                case BACKUP_SAVE -> stepBackupSave();
                case PLACE -> stepPlace(deadline, budget);
                case ENTITIES -> stepEntities(deadline, budget);
                case CLEANUP -> stepCleanup();
            };
        } catch (IllegalStateException e) {
            fail("reason.chunk-failed");
            return true;
        }
    }

    private World world() {
        World w = Bukkit.getWorld(worldName);
        if (w == null) {
            fail("reason.world-not-loaded");
        }
        return w;
    }

    private boolean stepLoad() {
        if (!pending.isDone()) {
            return false;
        }
        Loaded loaded = (Loaded) pending.join();
        pending = null;
        if (loaded == null) {
            fail(kind == Kind.UPDATE ? "reason.snapshot-missing" : "reason.io");
            return true;
        }
        if (loaded.snapshot() == null) {
            fail("reason.backup-missing");
            return true;
        }
        source = loaded.snapshot();
        backupSeq = loaded.seq();

        Sample s = plugin.store().get(sampleName);
        Placement p = s == null ? null : s.placement(placementName);
        if (p == null) {
            fail("reason.deleted");
            return true;
        }
        if (kind == Kind.UPDATE) {
            worldName = p.world();
            target = p.target(s);
            if (target == null || target.sizeX() != source.sizeX() || target.sizeY() != source.sizeY()
                    || target.sizeZ() != source.sizeZ()) {
                fail("reason.size-mismatch");
                return true;
            }
        } else {
            // 復原到備份當時的位置
            worldName = source.world();
            target = Cuboid.ofSize(source.originX(), source.originY(), source.originZ(),
                    source.sizeX(), source.sizeY(), source.sizeZ());
        }
        World w = world();
        if (w == null) {
            return true;
        }
        minHeight = w.getMinHeight();
        maxHeight = w.getMaxHeight();
        int[] unknown = new int[1];
        paletteData = WorldOps.parsePalette(source.palette(), unknown);
        if (unknown[0] > 0) {
            plugin.messages().log("log.unknown-blocks", Placeholders.of("count", unknown[0],
                    "sample", sampleName, "placement", placementName));
        }
        paletteAir = new boolean[paletteData.length];
        for (int i = 0; i < paletteData.length; i++) {
            paletteAir[i] = paletteData[i].getMaterial().isAir();
        }
        tileMask = new BitSet(source.blocks().length);
        for (Integer idx : source.tiles().keySet()) {
            tileMask.set(idx);
        }
        order = new ColumnOrder(target);
        gate = new ChunkGate(plugin, w);

        if (resumed) {
            phase = resumePhase;
            cursor = Math.min(resumeCursor, order.total);
            entityCursor = resumeEntityCursor;
        } else if (kind == Kind.UPDATE && reuseBackupSeq < 0) {
            backup = new Capturer(target, gate);
            phase = Phase.BACKUP;
        } else {
            phase = Phase.PLACE;
        }
        markDirty();
        return false;
    }

    private boolean stepBackup(long deadline, int budget) {
        World w = world();
        if (w == null) {
            return true;
        }
        if (!backup.step(w, budget * 4, deadline)) {
            return false;
        }
        gate.releaseAll(w);
        Snapshot snap = backup.buffer.build(worldName, target.minX(), target.minY(), target.minZ());
        backup = null;
        File file = plugin.store().backupFile(sampleName, placementName, backupSeq);
        int keep = plugin.config().backupKeep();
        int seq = backupSeq;
        pending = CompletableFuture.runAsync(() -> {
            try {
                SnapshotIO.write(file, snap);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            // 只保留最近 keep 份
            List<Integer> seqs = plugin.store().backupSeqs(sampleName, placementName);
            for (int i = keep; i < seqs.size(); i++) {
                if (seqs.get(i) != seq) {
                    plugin.store().backupFile(sampleName, placementName, seqs.get(i)).delete();
                }
            }
        }, plugin.ioExecutor());
        phase = Phase.BACKUP_SAVE;
        return false;
    }

    private boolean stepBackupSave() {
        if (!pending.isDone()) {
            return false;
        }
        try {
            pending.join();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "backup " + sampleName + "/" + placementName, e);
            fail("reason.io");
            return true;
        }
        pending = null;
        cursor = 0;
        phase = Phase.PLACE;
        markDirty();
        return false;
    }

    private boolean stepPlace(long deadline, int budget) {
        World w = world();
        if (w == null) {
            return true;
        }
        int sx = source.sizeX();
        int sz = source.sizeZ();
        int[] blocks = source.blocks();
        while (budget > 0 && cursor < order.total) {
            int ci = order.columnOf(cursor);
            ColumnOrder.Column col = order.columns.get(ci);
            if (ci != entered) {
                if (!gate.ensure(w, col.cx(), col.cz(), clearEntities)) {
                    return false;
                }
                if (clearEntities) {
                    WorldOps.clearEntities(w, col, target);
                }
                entered = ci;
                for (int i = ci + 1; i <= ci + 2 && i < order.columns.size(); i++) {
                    gate.prefetch(w, order.columns.get(i).cx(), order.columns.get(i).cz());
                }
            }
            long local = cursor - col.offset();
            long end = Math.min(col.count(), local + budget);
            int width = col.width();
            int layer = width * col.depth();
            for (long i = local; i < end; i++) {
                int li = (int) i;
                int y = target.minY() + li / layer;
                int rem = li % layer;
                int z = col.z0() + rem / width;
                int x = col.x0() + rem % width;
                if (y < minHeight || y >= maxHeight) {
                    continue;
                }
                int idx = ((y - target.minY()) * sz + (z - target.minZ())) * sx + (x - target.minX());
                if (tileMask.get(idx)) {
                    if (!WorldOps.placeTile(w, x, y, z, source.tiles().get(idx), copyContainers)) {
                        failedTiles++;
                        WorldOps.placeBlock(w, x, y, z, paletteData[blocks[idx]]);
                    }
                    budget -= 16;
                    continue;
                }
                int pi = blocks[idx];
                if (!replaceAir && paletteAir[pi]) {
                    continue;
                }
                WorldOps.placeBlock(w, x, y, z, paletteData[pi]);
            }
            budget -= (int) (end - local);
            cursor = col.offset() + end;
            if (end >= col.count()) {
                gate.release(w, col.cx(), col.cz());
            }
            if (System.nanoTime() > deadline) {
                break;
            }
        }
        if (cursor >= order.total) {
            gate.releaseAll(w);
            phase = copyEntities && !source.entities().isEmpty() ? Phase.ENTITIES : Phase.CLEANUP;
            entityCursor = 0;
            markDirty();
        }
        return false;
    }

    private boolean stepEntities(long deadline, int budget) {
        World w = world();
        if (w == null) {
            return true;
        }
        List<Snapshot.EntityEntry> list = source.entities();
        while (entityCursor < list.size() && budget > 0) {
            Snapshot.EntityEntry e = list.get(entityCursor);
            int bx = (int) Math.floor(target.minX() + e.x());
            int bz = (int) Math.floor(target.minZ() + e.z());
            if (!gate.ensure(w, bx >> 4, bz >> 4, false)) {
                return false;
            }
            if (!WorldOps.spawnEntity(w, e, target.minX(), target.minY(), target.minZ())) {
                failedEntities++;
            }
            entityCursor++;
            budget -= 64;
            if (System.nanoTime() > deadline) {
                break;
            }
        }
        if (entityCursor >= list.size()) {
            gate.releaseAll(w);
            phase = Phase.CLEANUP;
            markDirty();
        }
        return false;
    }

    private boolean stepCleanup() {
        if (failedTiles > 0 || failedEntities > 0) {
            plugin.messages().log("log.place-skipped", Placeholders.of("sample", sampleName,
                    "placement", placementName, "tiles", failedTiles, "entities", failedEntities));
        }
        if (kind == Kind.UNDO && backupSeq >= 0) {
            File f = plugin.store().backupFile(sampleName, placementName, backupSeq);
            plugin.ioExecutor().execute(f::delete);
        }
        source = null;
        return true;
    }

    @Override
    public float progress() {
        if (phase == Phase.LOAD) {
            return 0f;
        }
        long vol = order == null ? 1 : Math.max(1, order.total);
        int entities = source == null || !copyEntities ? 0 : source.entities().size();
        boolean doBackup = kind == Kind.UPDATE && reuseBackupSeq < 0 && !resumed;
        double total = (doBackup ? vol : 0) + vol + entities;
        double done = switch (phase) {
            case LOAD -> 0;
            case BACKUP -> backup == null ? 0 : backup.cursor();
            case BACKUP_SAVE -> vol;
            case PLACE -> (doBackup ? vol : 0) + cursor;
            case ENTITIES -> (doBackup ? vol : 0) + vol + entityCursor;
            case CLEANUP -> total;
        };
        return (float) Math.max(0, Math.min(1, done / Math.max(1, total)));
    }

    @Override
    void stop() {
        if (gate != null) {
            gate.releaseAll(Bukkit.getWorld(gate.worldName()));
        }
        backup = null;
        source = null;
    }
}
