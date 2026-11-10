package clre20.zoneReplica.storage;

import clre20.zoneReplica.ZoneReplica;
import clre20.zoneReplica.model.Cuboid;
import clre20.zoneReplica.model.Placement;
import clre20.zoneReplica.model.Sample;
import clre20.zoneReplica.util.Placeholders;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * 樣本與場地設定的記憶體快取與存檔。
 * 修改時只標記為髒資料，每 30 秒於非同步 I/O 執行緒批次寫入，關服時統一存檔。
 */
public final class ZoneStore {

    private static final Pattern NAME = Pattern.compile("^[\\p{IsHan}A-Za-z0-9_][\\p{IsHan}A-Za-z0-9_-]{0,31}$");

    private final ZoneReplica plugin;
    private final ExecutorService io;
    private final File zonesDir;
    private final File snapshotsDir;
    private final File backupsDir;
    private final Map<String, Sample> samples = new TreeMap<>();
    private final Set<String> dirty = new HashSet<>();

    public ZoneStore(ZoneReplica plugin, ExecutorService io) {
        this.plugin = plugin;
        this.io = io;
        File data = new File(plugin.getDataFolder(), "data");
        this.zonesDir = new File(data, "zones");
        this.snapshotsDir = new File(data, "snapshots");
        this.backupsDir = new File(data, "backups");
    }

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** 啟動時讀取全部樣本（同步，只在啟動時執行一次）。 */
    public void loadAll() {
        samples.clear();
        dirty.clear();
        if (!zonesDir.isDirectory() && !zonesDir.mkdirs()) {
            plugin.getLogger().severe("無法建立資料夾 " + zonesDir);
            return;
        }
        File[] files = zonesDir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) {
            return;
        }
        for (File f : files) {
            String base = f.getName().substring(0, f.getName().length() - 4);
            try {
                YamlConfiguration y = new YamlConfiguration();
                y.load(f);
                Sample s = Sample.fromYaml(base, y);
                if (!validName(s.name())) {
                    plugin.messages().log("log.invalid-zone-file", Placeholders.of("file", f.getName()));
                    continue;
                }
                samples.put(s.key(), s);
            } catch (Exception e) {
                plugin.messages().log("log.invalid-zone-file", Placeholders.of("file", f.getName()));
                plugin.getLogger().log(Level.WARNING, f.getName(), e);
            }
        }
    }

    public Sample get(String name) {
        return name == null ? null : samples.get(Sample.key(name));
    }

    public Collection<Sample> all() {
        return Collections.unmodifiableCollection(samples.values());
    }

    public List<String> names() {
        List<String> out = new ArrayList<>(samples.size());
        for (Sample s : samples.values()) {
            out.add(s.name());
        }
        return out;
    }

    public void put(Sample s) {
        samples.put(s.key(), s);
        markDirty(s);
    }

    public void markDirty(Sample s) {
        dirty.add(s.key());
    }

    // ---------- 路徑 ----------

    public File zoneFile(String sampleName) {
        return new File(zonesDir, sampleName + ".yml");
    }

    public File snapshotFile(String sampleName) {
        return new File(snapshotsDir, Sample.key(sampleName) + ".zrs");
    }

    public File backupDir(String sampleName, String placementName) {
        return new File(new File(backupsDir, Sample.key(sampleName)), Sample.key(placementName));
    }

    public File backupFile(String sampleName, String placementName, int seq) {
        return new File(backupDir(sampleName, placementName), seq + ".zrs");
    }

    public boolean hasSnapshot(Sample s) {
        return s.snapshotTime() != null && snapshotFile(s.name()).isFile();
    }

    /** 由大到小排序的備份序號（I/O 執行緒呼叫）。 */
    public List<Integer> backupSeqs(String sampleName, String placementName) {
        File dir = backupDir(sampleName, placementName);
        String[] names = dir.list((d, n) -> n.endsWith(".zrs"));
        List<Integer> out = new ArrayList<>();
        if (names != null) {
            for (String n : names) {
                try {
                    out.add(Integer.parseInt(n.substring(0, n.length() - 4)));
                } catch (NumberFormatException ignored) {
                    // 略過其他檔案
                }
            }
        }
        out.sort(Collections.reverseOrder());
        return out;
    }

    // ---------- 刪除 ----------

    public void deleteSample(Sample s) {
        samples.remove(s.key());
        dirty.remove(s.key());
        File zone = zoneFile(s.name());
        File snap = snapshotFile(s.name());
        File backups = new File(backupsDir, s.key());
        io.execute(() -> {
            deleteQuietly(zone);
            deleteQuietly(snap);
            deleteTree(backups);
        });
    }

    public void deletePlacement(Sample s, Placement p) {
        s.removePlacement(p.name());
        markDirty(s);
        File dir = backupDir(s.name(), p.name());
        io.execute(() -> deleteTree(dir));
    }

    private static void deleteQuietly(File f) {
        try {
            Files.deleteIfExists(f.toPath());
        } catch (IOException ignored) {
            // 檔案已不存在或無法刪除
        }
    }

    public static void deleteTree(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteTree(c);
            }
        }
        deleteQuietly(f);
    }

    // ---------- 存檔 ----------

    /** 把髒資料於 I/O 執行緒寫入（序列化在主執行緒完成，寫檔在背景）。 */
    public void flushAsync() {
        Map<File, String> jobs = drainDirty();
        if (!jobs.isEmpty()) {
            io.execute(() -> writeAll(jobs));
        }
    }

    /** 關服時同步存檔。 */
    public void flushSync() {
        writeAll(drainDirty());
    }

    public CompletableFuture<Void> flushNow() {
        Map<File, String> jobs = drainDirty();
        return CompletableFuture.runAsync(() -> writeAll(jobs), io);
    }

    private Map<File, String> drainDirty() {
        Map<File, String> jobs = new LinkedHashMap<>();
        for (String key : dirty) {
            Sample s = samples.get(key);
            if (s != null) {
                jobs.put(zoneFile(s.name()), s.toYaml().saveToString());
            }
        }
        dirty.clear();
        return jobs;
    }

    private void writeAll(Map<File, String> jobs) {
        for (Map.Entry<File, String> e : jobs.entrySet()) {
            try {
                writeAtomic(e.getKey(), e.getValue());
            } catch (IOException ex) {
                plugin.getLogger().log(Level.SEVERE, "無法儲存 " + e.getKey().getName(), ex);
            }
        }
    }

    public static void writeAtomic(File file, String content) throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        File tmp = new File(dir, file.getName() + ".tmp");
        Files.writeString(tmp.toPath(), content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ---------- 重疊檢查 ----------

    /**
     * 回傳與指定範圍重疊的樣本原位置或場地（顯示用名稱，例如「城堡」或「城堡 / 東門」）。
     *
     * @param ignoreSampleRegion 不比對這個樣本的原位置（檢查樣本本身時使用）
     * @param ignorePlacement    不比對這個場地（格式 sampleKey/placementKey）
     * @param override           編輯中尚未儲存的樣本（以它的 A、B 計算，可為 null）
     */
    public List<String> overlaps(String world, Cuboid box, String ignoreSampleRegion, String ignorePlacement,
                                 Sample override) {
        List<String> out = new ArrayList<>();
        if (world == null || box == null) {
            return out;
        }
        for (Sample stored : samples.values()) {
            Sample s = override != null && override.key().equals(stored.key()) ? override : stored;
            Cuboid region = s.region();
            if (region != null && world.equals(s.world()) && !s.key().equals(ignoreSampleRegion)
                    && region.intersects(box)) {
                out.add(plugin.messages().raw("format.sample", Placeholders.of("sample", s.name())));
            }
            if (region == null) {
                continue;
            }
            for (Placement p : stored.placements()) {
                String pk = s.key() + "/" + Sample.key(p.name());
                if (pk.equals(ignorePlacement) || !world.equals(p.world()) || p.c() == null) {
                    continue;
                }
                Cuboid t = p.target(s);
                if (t != null && t.intersects(box)) {
                    out.add(plugin.messages().raw("format.target",
                            Placeholders.of("sample", s.name(), "placement", p.name())));
                }
            }
        }
        return out;
    }
}
