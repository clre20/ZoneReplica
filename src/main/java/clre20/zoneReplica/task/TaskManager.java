package clre20.zoneReplica.task;

import clre20.zoneReplica.ZoneReplica;
import clre20.zoneReplica.config.Messages;
import clre20.zoneReplica.storage.ZoneStore;
import clre20.zoneReplica.util.Placeholders;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * 管理所有覆蓋與記錄任務：排隊、衝突、倒數、分批執行、Boss 血條與重啟後接續。
 */
public final class TaskManager implements Listener {

    public enum ConflictMode { REJECT, QUEUE, FORCE }

    public enum SubmitResult { STARTED, QUEUED, REJECTED }

    private final ZoneReplica plugin;
    private final File tasksDir;
    /** 依建立順序排列；同一個場地的任務依此順序執行。 */
    private final List<ZoneTask> tasks = new ArrayList<>();
    /** 已完成、等待隱藏的 Boss 血條（血條 → 隱藏時間）。 */
    private final Map<BossBar, Long> fading = new HashMap<>();
    private final Map<BossBar, Set<UUID>> fadingViewers = new HashMap<>();
    private final Set<String> deletedFiles = new HashSet<>();
    private BukkitTask ticker;
    private long tick;
    private int rr;

    public TaskManager(ZoneReplica plugin) {
        this.plugin = plugin;
        this.tasksDir = new File(new File(plugin.getDataFolder(), "data"), "tasks");
        WorldOps.outlineKey = plugin.outlineKey();
    }

    public void start() {
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    // ---------- 建立任務 ----------

    public static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    public ZoneTask newCapture(String sample, CommandSender sender) {
        return new CaptureTask(plugin, newId(), sample, requesterOf(sender), isConsole(sender),
                System.currentTimeMillis());
    }

    public ZoneTask newUpdate(String sample, String placement, CommandSender sender, boolean auto) {
        return new PlaceTask(plugin, newId(), ZoneTask.Kind.UPDATE, sample, placement,
                auto ? null : requesterOf(sender), !auto && isConsole(sender), auto, System.currentTimeMillis());
    }

    public ZoneTask newUndo(String sample, String placement, CommandSender sender) {
        return new PlaceTask(plugin, newId(), ZoneTask.Kind.UNDO, sample, placement,
                requesterOf(sender), isConsole(sender), false, System.currentTimeMillis());
    }

    private static UUID requesterOf(CommandSender sender) {
        return sender instanceof Player p ? p.getUniqueId() : null;
    }

    private static boolean isConsole(CommandSender sender) {
        return sender != null && !(sender instanceof Player);
    }

    // ---------- 查詢 ----------

    public List<ZoneTask> all() {
        return List.copyOf(tasks);
    }

    public List<ZoneTask> conflicts(String conflictKey) {
        List<ZoneTask> out = new ArrayList<>();
        for (ZoneTask t : tasks) {
            if (t.conflictKey().equals(conflictKey)) {
                out.add(t);
            }
        }
        return out;
    }

    public boolean hasConflict(String conflictKey) {
        for (ZoneTask t : tasks) {
            if (t.conflictKey().equals(conflictKey)) {
                return true;
            }
        }
        return false;
    }

    /** 某個樣本（placementKey 為 null 時含全部場地）的任務。 */
    public List<ZoneTask> find(String sampleKey, String placementKey) {
        List<ZoneTask> out = new ArrayList<>();
        for (ZoneTask t : tasks) {
            if (t.matches(sampleKey, placementKey)) {
                out.add(t);
            }
        }
        return out;
    }

    /** 排隊順位（1 起算）；執行中回傳 0。 */
    public int position(ZoneTask task) {
        if (task.state == ZoneTask.State.RUNNING) {
            return 0;
        }
        int pos = 0;
        for (ZoneTask t : tasks) {
            if (t.state != ZoneTask.State.RUNNING) {
                pos++;
            }
            if (t == task) {
                return pos;
            }
        }
        return pos;
    }

    // ---------- 提交 ----------

    public SubmitResult submit(ZoneTask task, ConflictMode mode) {
        List<ZoneTask> conflicts = conflicts(task.conflictKey());
        if (!conflicts.isEmpty()) {
            if (mode == ConflictMode.REJECT) {
                return SubmitResult.REJECTED;
            }
            if (mode == ConflictMode.FORCE) {
                for (ZoneTask c : conflicts) {
                    int seq = c.completedBackupSeq();
                    if (seq >= 0 && task instanceof PlaceTask pt && task.kind == ZoneTask.Kind.UPDATE) {
                        pt.reuseBackup(seq);
                    }
                    finish(c, "interrupted");
                }
            }
        }
        if (!task.countdownDone && task.needsCountdown() && plugin.config().countdownSeconds() > 0) {
            task.state = ZoneTask.State.COUNTDOWN;
            task.countdownLeft = plugin.config().countdownSeconds();
            announceCountdown(task);
        } else {
            task.countdownDone = true;
            task.state = ZoneTask.State.WAITING;
        }
        tasks.add(task);
        task.stateDirty = true;
        showBar(task);
        if (task.state == ZoneTask.State.WAITING) {
            startWaiting();
        }
        return task.state == ZoneTask.State.RUNNING ? SubmitResult.STARTED : SubmitResult.QUEUED;
    }

    private void announceCountdown(ZoneTask task) {
        if (plugin.config().warningSeconds().contains(task.countdownLeft)) {
            task.warnPlayers(task.countdownLeft);
        }
    }

    // ---------- 中止 ----------

    /** 中止某個場地或樣本的任務，回傳中止的數量。 */
    public int cancel(String sampleKey, String placementKey, String reason) {
        int n = 0;
        for (ZoneTask t : new ArrayList<>(tasks)) {
            if (t.matches(sampleKey, placementKey)) {
                finish(t, reason);
                n++;
            }
        }
        return n;
    }

    // ---------- 主迴圈 ----------

    private void tick() {
        tick++;
        if (tick % 20 == 0) {
            countdownSecond();
        }
        startWaiting();
        runRunning();
        if (tick % 10 == 0) {
            updateBars();
        }
        if (tick % 200 == 0) {
            persist(false);
        }
    }

    private void countdownSecond() {
        for (ZoneTask t : tasks) {
            if (t.state != ZoneTask.State.COUNTDOWN) {
                continue;
            }
            t.countdownLeft--;
            if (t.countdownLeft <= 0) {
                t.countdownDone = true;
                t.state = ZoneTask.State.WAITING;
                t.stateDirty = true;
            } else if (plugin.config().warningSeconds().contains(t.countdownLeft)) {
                t.warnPlayers(t.countdownLeft);
            }
        }
    }

    private void startWaiting() {
        int running = 0;
        for (ZoneTask t : tasks) {
            if (t.state == ZoneTask.State.RUNNING) {
                running++;
            }
        }
        int max = plugin.config().maxConcurrentTasks();
        Set<String> busyKeys = new HashSet<>();
        List<ZoneTask> toStart = new ArrayList<>();
        for (ZoneTask t : tasks) {
            String key = t.conflictKey();
            if (t.state == ZoneTask.State.WAITING && running < max && !busyKeys.contains(key)) {
                toStart.add(t);
                running++;
            }
            busyKeys.add(key);
        }
        for (ZoneTask t : toStart) {
            t.state = ZoneTask.State.RUNNING;
            t.startedAt = System.currentTimeMillis();
            t.stateDirty = true;
            try {
                t.begin();
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "task " + t.id, e);
                t.fail("reason.error");
            }
            if (t.failure != null) {
                finish(t, null);
            } else {
                notifyStart(t);
            }
        }
    }

    private void runRunning() {
        List<ZoneTask> running = new ArrayList<>();
        for (ZoneTask t : tasks) {
            if (t.state == ZoneTask.State.RUNNING) {
                running.add(t);
            }
        }
        if (running.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + plugin.config().maxNanosPerTick();
        int budget = Math.max(64, plugin.config().blocksPerTick() / running.size());
        int n = running.size();
        for (int i = 0; i < n; i++) {
            ZoneTask t = running.get((rr + i) % n);
            boolean done;
            try {
                done = t.step(deadline, budget);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "task " + t.id, e);
                t.fail("reason.error");
                done = true;
            }
            if (done) {
                finish(t, null);
            }
            if (System.nanoTime() > deadline) {
                break;
            }
        }
        rr++;
    }

    /**
     * 結束任務。
     *
     * @param reason null = 正常完成或失敗（看 failure）；"interrupted"、"cancelled"、"deleted" = 被中止
     */
    private void finish(ZoneTask t, String reason) {
        if (!tasks.remove(t)) {
            return;
        }
        try {
            t.stop();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "stop " + t.id, e);
        }
        deletedFiles.add(t.id);
        Messages m = plugin.messages();
        Placeholders ph = placeholders(t);
        String key;
        if (reason != null) {
            key = "task-" + reason;
        } else if (t.failure != null) {
            key = "task-failed";
            ph.with("reason", m.raw(t.failure));
        } else {
            key = "task-done";
            long secs = Math.max(0, (System.currentTimeMillis() - t.startedAt) / 1000);
            ph.with("seconds", secs);
        }
        notifyRequester(t, key, ph);
        if (t.bar != null) {
            String barKey = reason != null ? "bossbar.cancelled" : t.failure != null ? "bossbar.failed" : "bossbar.done";
            t.bar.name(m.component(barKey, ph));
            t.bar.progress(t.failure == null && reason == null ? 1f : t.bar.progress());
            fading.put(t.bar, System.currentTimeMillis() + 3000L);
            fadingViewers.put(t.bar, new HashSet<>(t.viewers));
            t.bar = null;
        }
        // 下一個排隊中的任務
        Bukkit.getScheduler().runTask(plugin, this::startWaiting);
    }

    // ---------- 通知 ----------

    public Placeholders placeholders(ZoneTask t) {
        Messages m = plugin.messages();
        String target = t.placementName == null
                ? m.raw("format.sample", Placeholders.of("sample", t.sampleName))
                : m.raw("format.target", Placeholders.of("sample", t.sampleName, "placement", t.placementName));
        Placeholders ph = Placeholders.of("sample", t.sampleName,
                "placement", t.placementName == null ? "" : t.placementName,
                "target", target,
                "action", m.raw("action." + t.kind.name().toLowerCase(java.util.Locale.ROOT)),
                "percent", t.state == ZoneTask.State.RUNNING ? Math.round(t.progress() * 100f) : 0,
                "seconds", t.countdownLeft,
                "position", position(t));
        return ph.with("state", stateText(t, ph));
    }

    private void notifyStart(ZoneTask t) {
        Placeholders ph = placeholders(t);
        if (t.auto) {
            plugin.messages().log("log.auto-start", ph);
            return;
        }
        if (t.console) {
            plugin.messages().send(Bukkit.getConsoleSender(), "task-started", ph);
        }
    }

    private void notifyRequester(ZoneTask t, String key, Placeholders ph) {
        if (t.auto) {
            plugin.messages().log("log." + key, ph);
            return;
        }
        if (t.requester != null) {
            Player p = Bukkit.getPlayer(t.requester);
            if (p != null) {
                plugin.messages().send(p, key, ph);
            }
        } else {
            plugin.messages().send(Bukkit.getConsoleSender(), key, ph);
        }
    }

    /** 後台與指令方塊：輸出一次當下的狀態。 */
    public void printStatus(CommandSender to, ZoneTask t) {
        plugin.messages().send(to, "task-status", placeholders(t));
    }

    private String stateText(ZoneTask t, Placeholders ph) {
        return switch (t.state) {
            case RUNNING -> plugin.messages().raw("state.running", ph);
            case WAITING -> plugin.messages().raw("state.waiting", ph);
            case COUNTDOWN -> plugin.messages().raw("state.countdown", ph);
        };
    }

    // ---------- Boss 血條 ----------

    /** 讓玩家看到某個任務的 Boss 血條（/zr schedule）。 */
    public void addViewer(ZoneTask t, Player p) {
        t.viewers.add(p.getUniqueId());
        showBar(t);
    }

    private void showBar(ZoneTask t) {
        if (t.viewers.isEmpty()) {
            return;
        }
        if (t.bar == null) {
            t.bar = BossBar.bossBar(Component.empty(), 0f, plugin.config().bossBarColor(),
                    plugin.config().bossBarOverlay());
        }
        refreshBar(t);
        for (UUID id : t.viewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.showBossBar(t.bar);
            }
        }
    }

    private void refreshBar(ZoneTask t) {
        Placeholders ph = placeholders(t);
        String key = switch (t.state) {
            case RUNNING -> "bossbar.progress";
            case WAITING -> "bossbar.waiting";
            case COUNTDOWN -> "bossbar.countdown";
        };
        t.bar.name(plugin.messages().component(key, ph));
        float progress = t.state == ZoneTask.State.COUNTDOWN
                ? Math.max(0f, Math.min(1f, t.countdownLeft / (float) Math.max(1, plugin.config().countdownSeconds())))
                : t.state == ZoneTask.State.RUNNING ? t.progress() : 0f;
        t.bar.progress(Math.max(0f, Math.min(1f, progress)));
    }

    private void updateBars() {
        for (ZoneTask t : tasks) {
            if (t.bar != null) {
                refreshBar(t);
            }
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<BossBar, Long>> it = fading.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BossBar, Long> e = it.next();
            if (e.getValue() <= now) {
                hideBar(e.getKey(), fadingViewers.remove(e.getKey()));
                it.remove();
            }
        }
    }

    private void hideBar(BossBar bar, Set<UUID> viewers) {
        if (viewers == null) {
            return;
        }
        for (UUID id : viewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.hideBossBar(bar);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        for (ZoneTask t : tasks) {
            if (!id.equals(t.requester)) {
                t.viewers.remove(id);
            }
        }
        for (Set<UUID> v : fadingViewers.values()) {
            v.remove(id);
        }
    }

    /** 重新進服的發起者再次看到血條。 */
    public void rejoin(Player p) {
        for (ZoneTask t : tasks) {
            if (t.viewers.contains(p.getUniqueId())) {
                showBar(t);
            }
        }
    }

    // ---------- 存檔與重啟接續 ----------

    /** 寫入 data/tasks（每 10 秒有變動時非同步寫入；關服時同步）。 */
    public void persist(boolean sync) {
        Map<File, String> writes = new HashMap<>();
        for (ZoneTask t : tasks) {
            if (t.state == ZoneTask.State.RUNNING || t.stateDirty) {
                YamlConfiguration y = new YamlConfiguration();
                y.set("order", t.createdAt);
                t.save(y);
                writes.put(new File(tasksDir, t.id + ".yml"), y.saveToString());
                t.stateDirty = false;
            }
        }
        List<File> deletes = new ArrayList<>();
        for (String id : deletedFiles) {
            deletes.add(new File(tasksDir, id + ".yml"));
        }
        deletedFiles.clear();
        if (writes.isEmpty() && deletes.isEmpty()) {
            return;
        }
        Runnable job = () -> {
            for (File f : deletes) {
                f.delete();
            }
            for (Map.Entry<File, String> e : writes.entrySet()) {
                try {
                    ZoneStore.writeAtomic(e.getKey(), e.getValue());
                } catch (IOException ex) {
                    plugin.getLogger().log(Level.WARNING, "task state " + e.getKey().getName(), ex);
                }
            }
        };
        if (sync) {
            job.run();
        } else {
            plugin.ioExecutor().execute(job);
        }
    }

    /** 啟動時讀回上次未完成的任務，依原順序接續。 */
    public void restore() {
        File[] files = tasksDir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null || files.length == 0) {
            return;
        }
        List<YamlConfiguration> list = new ArrayList<>();
        for (File f : files) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            if (y.isString("id")) {
                list.add(y);
            } else {
                f.delete();
            }
        }
        list.sort((a, b) -> Long.compare(a.getLong("order"), b.getLong("order")));
        int restored = 0;
        for (YamlConfiguration y : list) {
            ZoneTask t = restoreOne(y);
            String id = y.getString("id");
            if (t == null) {
                deletedFiles.add(id);
                continue;
            }
            tasks.add(t);
            if (t.countdownDone || !t.needsCountdown() || plugin.config().countdownSeconds() <= 0) {
                t.countdownDone = true;
                t.state = ZoneTask.State.WAITING;
            } else {
                t.state = ZoneTask.State.COUNTDOWN;
                t.countdownLeft = plugin.config().countdownSeconds();
            }
            t.stateDirty = true;
            restored++;
        }
        if (restored > 0) {
            plugin.messages().log("log.tasks-restored", Placeholders.of("count", restored));
        }
    }

    private ZoneTask restoreOne(ConfigurationSection y) {
        String id = y.getString("id");
        String sample = y.getString("sample");
        String placement = y.getString("placement");
        if (id == null || sample == null || plugin.store().get(sample) == null) {
            return null;
        }
        UUID requester = null;
        try {
            requester = y.isString("requester") ? UUID.fromString(y.getString("requester")) : null;
        } catch (IllegalArgumentException ignored) {
            // 無效的 UUID
        }
        boolean console = y.getBoolean("console");
        boolean auto = y.getBoolean("auto");
        long created = y.getLong("created-at", System.currentTimeMillis());
        ZoneTask.Kind kind;
        try {
            kind = ZoneTask.Kind.valueOf(y.getString("kind", ""));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (kind == ZoneTask.Kind.CAPTURE) {
            return new CaptureTask(plugin, id, sample, requester, console, created);
        }
        if (placement == null || plugin.store().get(sample).placement(placement) == null) {
            return null;
        }
        PlaceTask t = new PlaceTask(plugin, id, kind, sample, placement, requester, console, auto, created);
        t.restore(y);
        return t;
    }

    /** 關服：寫入目前狀態並釋放資源（任務留待下次啟動接續）。 */
    public void shutdown() {
        if (ticker != null) {
            ticker.cancel();
        }
        persist(true);
        for (ZoneTask t : tasks) {
            try {
                t.stop();
            } catch (RuntimeException ignored) {
                // 關服中
            }
            if (t.bar != null) {
                hideBar(t.bar, t.viewers);
            }
        }
        for (Map.Entry<BossBar, Set<UUID>> e : fadingViewers.entrySet()) {
            hideBar(e.getKey(), e.getValue());
        }
        tasks.clear();
        fading.clear();
        fadingViewers.clear();
    }
}
