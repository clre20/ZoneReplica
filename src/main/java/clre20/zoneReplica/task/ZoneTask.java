package clre20.zoneReplica.task;

import clre20.zoneReplica.ZoneReplica;
import clre20.zoneReplica.model.Sample;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 一個分批執行的工作：記錄快照、覆蓋或復原。
 * 只記錄名稱與 UUID，不持有 Player、World 等物件。
 */
public abstract class ZoneTask {

    public enum Kind { CAPTURE, UPDATE, UNDO }

    public enum State { COUNTDOWN, WAITING, RUNNING }

    protected final ZoneReplica plugin;
    final String id;
    final Kind kind;
    final String sampleName;
    final String placementName;
    final UUID requester;
    final boolean console;
    final boolean auto;
    final long createdAt;
    final Set<UUID> viewers = new LinkedHashSet<>();

    State state = State.WAITING;
    int countdownLeft;
    boolean countdownDone;
    long startedAt;
    BossBar bar;
    /** 失敗原因（messages.yml 的 reason.* 鍵），null 代表成功。 */
    String failure;
    /** 需要寫入 data/tasks 的狀態變更。 */
    boolean stateDirty = true;

    protected ZoneTask(ZoneReplica plugin, String id, Kind kind, String sampleName, String placementName,
                       UUID requester, boolean console, boolean auto, long createdAt) {
        this.plugin = plugin;
        this.id = id;
        this.kind = kind;
        this.sampleName = sampleName;
        this.placementName = placementName;
        this.requester = requester;
        this.console = console;
        this.auto = auto;
        this.createdAt = createdAt;
        if (requester != null) {
            viewers.add(requester);
        }
    }

    public String id() { return id; }
    public Kind kind() { return kind; }
    public String sampleName() { return sampleName; }
    public String placementName() { return placementName; }
    public State state() { return state; }
    public int countdownLeft() { return countdownLeft; }

    public String sampleKey() {
        return Sample.key(sampleName);
    }

    /** 衝突判斷用的鍵：同一個場地（或同一個樣本的記錄）同時只能有一個任務執行。 */
    public String conflictKey() {
        return placementName == null ? "s:" + sampleKey() : "p:" + sampleKey() + "/" + Sample.key(placementName);
    }

    public boolean matches(String sampleKey, String placementKey) {
        if (!sampleKey().equals(sampleKey)) {
            return false;
        }
        return placementKey == null || (placementName != null && Sample.key(placementName).equals(placementKey));
    }

    /** 開始執行（取得執行名額後，在主執行緒呼叫一次）。 */
    abstract void begin();

    /**
     * 執行一批工作。
     *
     * @return 工作完成（成功或失敗，失敗時設定 failure）
     */
    abstract boolean step(long deadlineNanos, int budget);

    /** 0~1 的進度。 */
    public abstract float progress();

    /** 中止並釋放資源（區塊票證等）；已完成的部分保留。 */
    abstract void stop();

    /** 開始覆蓋前是否需要倒數（自動更新或送走玩家）。 */
    abstract boolean needsCountdown();

    /** 倒數提醒與開始時的處理。 */
    void warnPlayers(int secondsLeft) {
    }

    /** 被中斷時，可以沿用的已完成備份序號；沒有則 -1。 */
    int completedBackupSeq() {
        return -1;
    }

    void save(ConfigurationSection s) {
        s.set("id", id);
        s.set("kind", kind.name());
        s.set("sample", sampleName);
        s.set("placement", placementName);
        s.set("requester", requester == null ? null : requester.toString());
        s.set("console", console);
        s.set("auto", auto);
        s.set("created-at", createdAt);
        s.set("countdown-done", countdownDone);
    }

    protected void fail(String reasonKey) {
        if (failure == null) {
            failure = reasonKey;
        }
    }

    protected void markDirty() {
        stateDirty = true;
    }
}
