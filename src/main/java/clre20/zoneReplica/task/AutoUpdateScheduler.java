package clre20.zoneReplica.task;

import clre20.zoneReplica.ZoneReplica;
import clre20.zoneReplica.model.Placement;
import clre20.zoneReplica.model.Sample;
import clre20.zoneReplica.util.Placeholders;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 依伺服器時間觸發自動更新。倒數提醒提前開始，讓覆蓋剛好在設定的時刻開始。
 * 伺服器關機期間錯過的時刻不補跑。
 */
public final class AutoUpdateScheduler {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final ZoneReplica plugin;
    private BukkitTask task;
    private long lastSecond;

    public AutoUpdateScheduler(ZoneReplica plugin) {
        this.plugin = plugin;
    }

    public void start() {
        lastSecond = System.currentTimeMillis() / 1000L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::check, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
    }

    private void check() {
        long now = System.currentTimeMillis() / 1000L;
        if (now <= lastSecond) {
            return;
        }
        // 伺服器卡頓時補檢查跳過的秒數（最多 2 分鐘）
        long from = Math.max(lastSecond + 1, now - 120);
        lastSecond = now;
        int lead = plugin.config().countdownSeconds();
        ZoneId zone = ZoneId.systemDefault();
        for (long sec = from; sec <= now; sec++) {
            LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochSecond(sec + lead), zone);
            if (t.getSecond() != 0) {
                continue;
            }
            fire(t);
        }
    }

    private void fire(LocalDateTime at) {
        String hhmm = at.format(HH_MM);
        for (Sample s : plugin.store().all()) {
            for (Placement p : s.placements()) {
                if (!p.autoUpdate().firesAt(at.getDayOfWeek(), hhmm)) {
                    continue;
                }
                Placeholders ph = Placeholders.of("sample", s.name(), "placement", p.name(), "time", hhmm);
                if (!plugin.store().hasSnapshot(s)) {
                    plugin.messages().log("log.auto-no-snapshot", ph);
                    continue;
                }
                if (p.world() == null || Bukkit.getWorld(p.world()) == null) {
                    plugin.messages().log("log.auto-world-not-loaded", ph.with("world", String.valueOf(p.world())));
                    continue;
                }
                ZoneTask t = plugin.tasks().newUpdate(s.name(), p.name(), null, true);
                plugin.tasks().submit(t, TaskManager.ConflictMode.QUEUE);
                plugin.messages().log("log.auto-scheduled", ph);
            }
        }
    }
}
