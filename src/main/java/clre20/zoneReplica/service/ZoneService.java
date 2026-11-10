package clre20.zoneReplica.service;

import clre20.zoneReplica.ZoneReplica;
import clre20.zoneReplica.config.Messages;
import clre20.zoneReplica.gui.ConfirmGui;
import clre20.zoneReplica.gui.Sessions;
import clre20.zoneReplica.model.Cuboid;
import clre20.zoneReplica.model.Placement;
import clre20.zoneReplica.model.Sample;
import clre20.zoneReplica.task.TaskManager;
import clre20.zoneReplica.task.ZoneTask;
import clre20.zoneReplica.util.Placeholders;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 指令與介面共用的操作：覆蓋、復原、刪除、儲存。
 * 玩家操作一律先開啟確認介面；後台與指令方塊依參數直接執行。
 */
public final class ZoneService {

    private final ZoneReplica plugin;

    public ZoneService(ZoneReplica plugin) {
        this.plugin = plugin;
    }

    private Messages msg() {
        return plugin.messages();
    }

    public Placeholders ph(Sample s, Placement p) {
        Placeholders ph = Placeholders.of("sample", s.name());
        if (p != null) {
            ph.with("placement", p.name());
            ph.with("target", msg().raw("format.target", Placeholders.of("sample", s.name(), "placement", p.name())));
        } else {
            ph.with("placement", "");
            ph.with("target", msg().raw("format.sample", Placeholders.of("sample", s.name())));
        }
        return ph;
    }

    /** 缺少項目的顯示文字（以「、」分隔）。 */
    public String missingText(List<String> keys) {
        List<String> names = new ArrayList<>();
        for (String k : keys) {
            names.add(msg().raw(k));
        }
        return String.join(msg().raw("format.separator"), names);
    }

    /** 範圍是否超出世界高度；世界未載入時不檢查。回傳錯誤訊息的佔位字，沒問題回傳 null。 */
    public Placeholders heightProblem(String world, Cuboid box) {
        World w = world == null ? null : Bukkit.getWorld(world);
        if (w == null || box == null) {
            return null;
        }
        if (box.minY() < w.getMinHeight() || box.maxY() >= w.getMaxHeight()) {
            return Placeholders.of("min", w.getMinHeight(), "max", w.getMaxHeight() - 1);
        }
        return null;
    }

    /** 發出重疊警告。 */
    public void warnOverlaps(CommandSender to, List<String> overlaps) {
        for (String o : overlaps) {
            msg().send(to, "overlap-warning", Placeholders.of("target", o));
        }
    }

    // ---------- 覆蓋 ----------

    /** 檢查場地能否覆蓋；不行時回傳訊息鍵。 */
    private String checkPlacement(Sample s, Placement p) {
        if (!plugin.store().hasSnapshot(s)) {
            return "no-snapshot";
        }
        if (!p.missing().isEmpty()) {
            return "placement-incomplete";
        }
        if (p.world() == null || Bukkit.getWorld(p.world()) == null) {
            return "world-not-loaded";
        }
        if (heightProblem(p.world(), p.target(s)) != null) {
            return "out-of-world";
        }
        return null;
    }

    /**
     * 覆蓋一個或多個場地。
     *
     * @param back 玩家按「取消」時回到的介面（可為 null）
     */
    public void update(CommandSender sender, Sample s, List<Placement> list, TaskManager.ConflictMode consoleMode,
                       Runnable back) {
        List<Placement> valid = new ArrayList<>();
        for (Placement p : list) {
            String err = checkPlacement(s, p);
            if (err != null) {
                msg().send(sender, err, ph(s, p).with("world", String.valueOf(p.world()))
                        .with("missing", missingText(p.missing())));
            } else {
                valid.add(p);
            }
        }
        if (valid.isEmpty()) {
            if (list.isEmpty()) {
                msg().send(sender, "no-placements", ph(s, null));
            }
            return;
        }
        if (sender instanceof Player player) {
            openConfirm(player, ZoneTask.Kind.UPDATE, s, valid, back);
        } else {
            submitAll(sender, s, valid, ZoneTask.Kind.UPDATE, consoleMode);
        }
    }

    /** 復原最近一次覆蓋。 */
    public void undo(CommandSender sender, Sample s, Placement p, TaskManager.ConflictMode consoleMode,
                     boolean confirmed, Runnable back) {
        boolean isPlayer = sender instanceof Player;
        if (!isPlayer && !confirmed) {
            msg().send(sender, "confirm-required", ph(s, p).with("flag", "-confirm"));
            return;
        }
        UUID id = isPlayer ? ((Player) sender).getUniqueId() : null;
        String sn = s.name();
        String pn = p.name();
        CompletableFuture.supplyAsync(() -> !plugin.store().backupSeqs(sn, pn).isEmpty(), plugin.ioExecutor())
                .thenAccept(has -> Bukkit.getScheduler().runTask(plugin, () -> {
                    CommandSender to = id == null ? sender : Bukkit.getPlayer(id);
                    Sample cur = plugin.store().get(sn);
                    Placement cp = cur == null ? null : cur.placement(pn);
                    if (to == null || cp == null) {
                        return;
                    }
                    if (!has) {
                        msg().send(to, "no-backup", ph(cur, cp));
                        return;
                    }
                    if (to instanceof Player player) {
                        openConfirm(player, ZoneTask.Kind.UNDO, cur, List.of(cp), back);
                    } else {
                        submitAll(to, cur, List.of(cp), ZoneTask.Kind.UNDO, consoleMode);
                    }
                }));
    }

    private void openConfirm(Player player, ZoneTask.Kind kind, Sample s, List<Placement> list, Runnable back) {
        boolean conflict = false;
        for (Placement p : list) {
            if (plugin.tasks().hasConflict(conflictKey(s, p))) {
                conflict = true;
                break;
            }
        }
        Placeholders ph = list.size() == 1 ? ph(s, list.get(0)) : ph(s, null);
        ph.with("action", msg().raw("action." + kind.name().toLowerCase(java.util.Locale.ROOT)))
                .with("count", list.size());
        List<Component> info = new ArrayList<>();
        for (Placement p : list) {
            Cuboid t = p.target(s);
            info.addAll(msg().lore("gui.confirm.target-line", ph(s, p)
                    .with("world", String.valueOf(p.world()))
                    .with("range", t == null ? "-" : t.toString())
                    .with("blocks", t == null ? 0 : t.volume())));
        }
        if (conflict) {
            info.addAll(msg().lore("gui.confirm.conflict-line", ph));
        }
        Runnable cancel = () -> {
            if (back != null) {
                back.run();
            } else {
                player.closeInventory();
            }
        };
        List<ConfirmGui.Button> buttons = new ArrayList<>();
        if (conflict) {
            buttons.add(new ConfirmGui.Button(11, Material.BARRIER, "gui.confirm.reject", p -> {
                msg().send(p, "task-rejected", ph);
                cancel.run();
            }));
            buttons.add(new ConfirmGui.Button(13, Material.CLOCK, "gui.confirm.queue", p -> {
                p.closeInventory();
                submitAll(p, s, list, kind, TaskManager.ConflictMode.QUEUE);
            }));
            buttons.add(new ConfirmGui.Button(15, Material.TNT, "gui.confirm.force", p -> {
                p.closeInventory();
                submitAll(p, s, list, kind, TaskManager.ConflictMode.FORCE);
            }));
        } else {
            buttons.add(new ConfirmGui.Button(11, Material.RED_CONCRETE, "gui.confirm.cancel", p -> cancel.run()));
            buttons.add(new ConfirmGui.Button(15, Material.LIME_CONCRETE, "gui.confirm.confirm", p -> {
                p.closeInventory();
                submitAll(p, s, list, kind, TaskManager.ConflictMode.REJECT);
            }));
        }
        new ConfirmGui(plugin, player, "gui.confirm.title", ph, info, buttons).open(player);
    }

    private static String conflictKey(Sample s, Placement p) {
        return "p:" + s.key() + "/" + Sample.key(p.name());
    }

    private void submitAll(CommandSender sender, Sample s, List<Placement> list, ZoneTask.Kind kind,
                           TaskManager.ConflictMode mode) {
        TaskManager tm = plugin.tasks();
        for (Placement p : list) {
            ZoneTask task = kind == ZoneTask.Kind.UPDATE
                    ? tm.newUpdate(s.name(), p.name(), sender, false)
                    : tm.newUndo(s.name(), p.name(), sender);
            TaskManager.SubmitResult r = tm.submit(task, mode);
            Placeholders ph = tm.placeholders(task);
            switch (r) {
                case REJECTED -> msg().send(sender, "task-busy", ph);
                case STARTED -> {
                    // 後台的「開始」訊息由任務開始時統一送出
                    if (sender instanceof Player) {
                        msg().send(sender, "task-submitted", ph);
                    }
                }
                case QUEUED -> msg().send(sender, task.state() == ZoneTask.State.COUNTDOWN
                        ? "task-countdown" : "task-queued", ph);
            }
            if (r != TaskManager.SubmitResult.REJECTED && !(sender instanceof Player)) {
                tm.printStatus(sender, task);
            }
        }
    }

    // ---------- 記錄快照 ----------

    public void capture(CommandSender sender, Sample s) {
        ZoneTask task = plugin.tasks().newCapture(s.name(), sender);
        plugin.tasks().submit(task, TaskManager.ConflictMode.FORCE);
        msg().send(sender, "capture-started", ph(s, null));
    }

    // ---------- 刪除 ----------

    public void deleteSample(CommandSender sender, Sample s, boolean confirmed, Runnable back) {
        if (!(sender instanceof Player player)) {
            if (!confirmed) {
                msg().send(sender, "confirm-required", ph(s, null).with("flag", "-confirm"));
                return;
            }
            doDeleteSample(sender, s);
            return;
        }
        boolean busy = !plugin.tasks().find(s.key(), null).isEmpty();
        Placeholders ph = ph(s, null).with("count", s.placements().size())
                .with("action", msg().raw("action.delete"));
        List<Component> info = new ArrayList<>(msg().lore("gui.confirm.delete-sample-line", ph));
        openDelete(player, ph, info, busy, () -> doDeleteSample(player, s), back);
    }

    public void deletePlacement(CommandSender sender, Sample s, Placement p, boolean confirmed, Runnable back) {
        if (!(sender instanceof Player player)) {
            if (!confirmed) {
                msg().send(sender, "confirm-required", ph(s, p).with("flag", "-confirm"));
                return;
            }
            doDeletePlacement(sender, s, p);
            return;
        }
        boolean busy = !plugin.tasks().find(s.key(), Sample.key(p.name())).isEmpty();
        Placeholders ph = ph(s, p).with("action", msg().raw("action.delete"));
        List<Component> info = new ArrayList<>(msg().lore("gui.confirm.delete-placement-line", ph));
        openDelete(player, ph, info, busy, () -> doDeletePlacement(player, s, p), back);
    }

    private void openDelete(Player player, Placeholders ph, List<Component> info, boolean busy, Runnable action,
                            Runnable back) {
        if (busy) {
            info.addAll(msg().lore("gui.confirm.busy-line", ph));
        }
        List<ConfirmGui.Button> buttons = new ArrayList<>();
        buttons.add(new ConfirmGui.Button(11, Material.RED_CONCRETE, "gui.confirm.cancel", p -> {
            if (back != null) {
                back.run();
            } else {
                p.closeInventory();
            }
        }));
        buttons.add(new ConfirmGui.Button(15, busy ? Material.TNT : Material.LAVA_BUCKET,
                busy ? "gui.confirm.abort-delete" : "gui.confirm.delete", p -> {
            p.closeInventory();
            action.run();
        }));
        new ConfirmGui(plugin, player, "gui.confirm.title", ph, info, buttons).open(player);
    }

    private void doDeleteSample(CommandSender sender, Sample s) {
        Sample cur = plugin.store().get(s.name());
        if (cur == null) {
            msg().send(sender, "sample-not-found", ph(s, null));
            return;
        }
        int count = cur.placements().size();
        plugin.tasks().cancel(cur.key(), null, "deleted");
        plugin.store().deleteSample(cur);
        plugin.sessions().forgetSample(cur.name());
        msg().send(sender, "sample-deleted", ph(cur, null).with("count", count));
    }

    private void doDeletePlacement(CommandSender sender, Sample s, Placement p) {
        Sample cur = plugin.store().get(s.name());
        Placement cp = cur == null ? null : cur.placement(p.name());
        if (cp == null) {
            msg().send(sender, "placement-not-found", ph(s, p));
            return;
        }
        plugin.tasks().cancel(cur.key(), Sample.key(cp.name()), "deleted");
        plugin.store().deletePlacement(cur, cp);
        plugin.store().flushAsync();
        plugin.sessions().forgetPlacement(cur.name(), cp.name());
        msg().send(sender, "placement-deleted", ph(cur, cp));
    }

    // ---------- 儲存 ----------

    /** 建立新樣本並開始記錄快照。 */
    public boolean createSample(Player p, Sessions.SampleDraft d) {
        Sample tmp = draftToSample(d);
        List<String> missing = tmp.missing();
        if (!missing.isEmpty()) {
            msg().send(p, "missing-options", Placeholders.of("missing", missingText(missing)));
            return false;
        }
        if (plugin.store().get(d.name) != null) {
            msg().send(p, "sample-exists", Placeholders.of("sample", d.name));
            return false;
        }
        if (tmp.region().volume() > plugin.config().maxVolume()) {
            msg().send(p, "selection-too-large", Placeholders.of("volume", tmp.region().volume(),
                    "max", plugin.config().maxVolume()));
            return false;
        }
        plugin.store().put(tmp);
        plugin.store().flushAsync();
        warnOverlaps(p, plugin.store().overlaps(tmp.world(), tmp.region(), tmp.key(), null, null));
        msg().send(p, "sample-created", ph(tmp, null));
        capture(p, tmp);
        return true;
    }

    /** 儲存樣本編輯；範圍或標記設定改變時（或 forceCapture）重新記錄快照。 */
    public boolean saveSample(Player p, Sessions.SampleDraft d, boolean forceCapture) {
        Sample s = plugin.store().get(d.name);
        if (s == null) {
            msg().send(p, "sample-not-found", Placeholders.of("sample", d.name));
            return false;
        }
        Sample tmp = draftToSample(d);
        List<String> missing = tmp.missing();
        if (!missing.isEmpty()) {
            msg().send(p, "missing-options", Placeholders.of("missing", missingText(missing)));
            return false;
        }
        if (tmp.region().volume() > plugin.config().maxVolume()) {
            msg().send(p, "selection-too-large", Placeholders.of("volume", tmp.region().volume(),
                    "max", plugin.config().maxVolume()));
            return false;
        }
        Cuboid oldRegion = s.region();
        boolean regionChanged = !Objects.equals(s.a(), d.a) || !Objects.equals(s.b(), d.b)
                || !Objects.equals(s.world(), d.world);
        boolean sizeChanged = oldRegion == null || oldRegion.sizeX() != tmp.region().sizeX()
                || oldRegion.sizeY() != tmp.region().sizeY() || oldRegion.sizeZ() != tmp.region().sizeZ();
        boolean markersChanged = !Objects.equals(s.removeMarkers(), d.removeMarkers);
        s.world(d.world);
        s.a(d.a);
        s.b(d.b);
        s.removeMarkers(d.removeMarkers);
        plugin.store().markDirty(s);
        plugin.store().flushAsync();
        if (sizeChanged && !s.placements().isEmpty()) {
            msg().send(p, "size-changed-warning", ph(s, null).with("count", s.placements().size()));
        }
        List<String> overlaps = new ArrayList<>(plugin.store().overlaps(s.world(), s.region(), s.key(), null, null));
        for (Placement pl : s.placements()) {
            overlaps.addAll(plugin.store().overlaps(pl.world(), pl.target(s), null,
                    s.key() + "/" + Sample.key(pl.name()), null));
        }
        warnOverlaps(p, overlaps.stream().distinct().toList());
        msg().send(p, "sample-saved", ph(s, null));
        if (regionChanged || markersChanged || forceCapture || !plugin.store().hasSnapshot(s)) {
            capture(p, s);
        }
        return true;
    }

    /** 儲存場地設定。 */
    public boolean savePlacement(Player player, Sessions.PlacementDraft d) {
        Sample s = plugin.store().get(d.sampleName);
        if (s == null) {
            msg().send(player, "sample-not-found", Placeholders.of("sample", d.sampleName));
            return false;
        }
        Placement p = d.placement;
        List<String> missing = p.missing();
        if (!missing.isEmpty()) {
            msg().send(player, "missing-options", Placeholders.of("missing", missingText(missing)));
            return false;
        }
        if (d.isNew && s.placement(p.name()) != null) {
            msg().send(player, "placement-exists", ph(s, p));
            return false;
        }
        Placeholders height = heightProblem(p.world(), p.target(s));
        if (height != null) {
            msg().send(player, "selection-out-of-world", height);
            return false;
        }
        s.putPlacement(p.copy());
        plugin.store().markDirty(s);
        plugin.store().flushAsync();
        warnOverlaps(player, plugin.store().overlaps(p.world(), p.target(s), null,
                s.key() + "/" + Sample.key(p.name()), null));
        msg().send(player, "placement-saved", ph(s, p));
        return true;
    }

    public static Sample draftToSample(Sessions.SampleDraft d) {
        Sample s = new Sample(d.name);
        s.world(d.world);
        s.a(d.a);
        s.b(d.b);
        s.removeMarkers(d.removeMarkers);
        return s;
    }
}
