package clre20.zoneReplica.command;

import clre20.zoneReplica.ZoneReplica;
import clre20.zoneReplica.config.Messages;
import clre20.zoneReplica.gui.CreateSampleGui;
import clre20.zoneReplica.gui.EditSampleGui;
import clre20.zoneReplica.gui.ListGui;
import clre20.zoneReplica.gui.PlacementGui;
import clre20.zoneReplica.model.Cuboid;
import clre20.zoneReplica.model.Placement;
import clre20.zoneReplica.model.Sample;
import clre20.zoneReplica.storage.ZoneStore;
import clre20.zoneReplica.task.TaskManager;
import clre20.zoneReplica.task.ZoneTask;
import clre20.zoneReplica.util.Placeholders;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /zonereplica（別名 /zr）。
 */
public final class ZrCommand implements TabExecutor {

    /** 子指令 → 權限節點（順序即幫助的顯示順序）。 */
    private static final Map<String, String> SUBCOMMANDS = new LinkedHashMap<>();

    static {
        SUBCOMMANDS.put("version", "zonereplica.version");
        SUBCOMMANDS.put("help", "zonereplica.help");
        SUBCOMMANDS.put("create", "zonereplica.create");
        SUBCOMMANDS.put("place", "zonereplica.place");
        SUBCOMMANDS.put("update", "zonereplica.update");
        SUBCOMMANDS.put("edit", "zonereplica.edit");
        SUBCOMMANDS.put("del", "zonereplica.delete");
        SUBCOMMANDS.put("undo", "zonereplica.undo");
        SUBCOMMANDS.put("cancel", "zonereplica.cancel");
        SUBCOMMANDS.put("schedule", "zonereplica.schedule");
        SUBCOMMANDS.put("list", "zonereplica.list");
        SUBCOMMANDS.put("reload", "zonereplica.reload");
    }

    private static final String FLAG_QUEUE = "-queue";
    private static final String FLAG_FORCE = "-force";
    private static final String FLAG_CONFIRM = "-confirm";

    private final ZoneReplica plugin;

    public ZrCommand(ZoneReplica plugin) {
        this.plugin = plugin;
    }

    private Messages msg() {
        return plugin.messages();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] rawArgs) {
        if (rawArgs.length == 0) {
            if (!sender.hasPermission("zonereplica.use")) {
                msg().send(sender, "no-permission");
                return true;
            }
            plugin.updateNotifier().showVersion(sender);
            return true;
        }
        String sub = rawArgs[0].toLowerCase(Locale.ROOT);
        if (sub.equals("delete")) {
            sub = "del";
        }
        String perm = SUBCOMMANDS.get(sub);
        if (perm == null) {
            msg().send(sender, "unknown-subcommand", Placeholders.of("label", label));
            return true;
        }
        if (!sender.hasPermission(perm)) {
            msg().send(sender, "no-permission");
            return true;
        }
        // 分離參數與旗標
        List<String> args = new ArrayList<>();
        boolean queue = false;
        boolean force = false;
        boolean confirm = false;
        for (int i = 1; i < rawArgs.length; i++) {
            String a = rawArgs[i];
            switch (a.toLowerCase(Locale.ROOT)) {
                case FLAG_QUEUE -> queue = true;
                case FLAG_FORCE -> force = true;
                case FLAG_CONFIRM -> confirm = true;
                default -> args.add(a);
            }
        }
        TaskManager.ConflictMode mode = force ? TaskManager.ConflictMode.FORCE
                : queue ? TaskManager.ConflictMode.QUEUE : TaskManager.ConflictMode.REJECT;

        switch (sub) {
            case "version" -> plugin.updateNotifier().showVersion(sender);
            case "help" -> help(sender, label);
            case "create" -> create(sender, args, label);
            case "place" -> place(sender, args, label);
            case "update" -> update(sender, args, label, mode);
            case "edit" -> edit(sender, args, label);
            case "del" -> delete(sender, args, label, confirm);
            case "undo" -> undo(sender, args, label, mode, confirm);
            case "cancel" -> cancel(sender, args, label);
            case "schedule" -> schedule(sender, args);
            case "list" -> list(sender);
            case "reload" -> reload(sender);
            default -> msg().send(sender, "unknown-subcommand", Placeholders.of("label", label));
        }
        return true;
    }

    // ---------- 子指令 ----------

    private void help(CommandSender sender, String label) {
        msg().send(sender, "help-header", Placeholders.of("version", plugin.getPluginMeta().getVersion()));
        if (sender.hasPermission("zonereplica.use")) {
            msg().send(sender, "help.root", Placeholders.of("label", label));
        }
        for (Map.Entry<String, String> e : SUBCOMMANDS.entrySet()) {
            if (sender.hasPermission(e.getValue())) {
                msg().send(sender, "help." + e.getKey(), Placeholders.of("label", label));
            }
        }
        msg().send(sender, "help.flags", Placeholders.of("label", label));
    }

    private void usage(CommandSender sender, String label, String key) {
        msg().send(sender, "usage", Placeholders.of("usage", msg().raw("usage-of." + key, Placeholders.of("label", label))));
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player p) {
            return p;
        }
        msg().send(sender, "player-only");
        return null;
    }

    private Sample sample(CommandSender sender, String name) {
        Sample s = plugin.store().get(name);
        if (s == null) {
            msg().send(sender, "sample-not-found", Placeholders.of("sample", name));
        }
        return s;
    }

    private Placement placement(CommandSender sender, Sample s, String name) {
        Placement p = s.placement(name);
        if (p == null) {
            msg().send(sender, "placement-not-found", Placeholders.of("sample", s.name(), "placement", name,
                    "target", msg().raw("format.target", Placeholders.of("sample", s.name(), "placement", name))));
        }
        return p;
    }

    private void create(CommandSender sender, List<String> args, String label) {
        Player p = requirePlayer(sender);
        if (p == null) {
            return;
        }
        if (args.size() != 1) {
            usage(sender, label, "create");
            return;
        }
        String name = args.get(0);
        if (!ZoneStore.validName(name)) {
            msg().send(p, "invalid-name");
            return;
        }
        if (plugin.store().get(name) != null) {
            msg().send(p, "sample-exists", Placeholders.of("sample", name));
            return;
        }
        CreateSampleGui.open(plugin, p, name);
    }

    private void place(CommandSender sender, List<String> args, String label) {
        Player p = requirePlayer(sender);
        if (p == null) {
            return;
        }
        if (args.size() != 2) {
            usage(sender, label, "place");
            return;
        }
        Sample s = sample(p, args.get(0));
        if (s == null) {
            return;
        }
        String name = args.get(1);
        if (!ZoneStore.validName(name)) {
            msg().send(p, "invalid-name");
            return;
        }
        if (s.placement(name) != null) {
            msg().send(p, "placement-exists", plugin.service().ph(s, s.placement(name)));
            return;
        }
        PlacementGui.openNew(plugin, p, s, name, null);
    }

    private void update(CommandSender sender, List<String> args, String label, TaskManager.ConflictMode mode) {
        if (args.isEmpty() || args.size() > 2) {
            usage(sender, label, "update");
            return;
        }
        Sample s = sample(sender, args.get(0));
        if (s == null) {
            return;
        }
        List<Placement> list;
        if (args.size() == 2) {
            Placement p = placement(sender, s, args.get(1));
            if (p == null) {
                return;
            }
            list = List.of(p);
        } else {
            list = new ArrayList<>(s.placements());
        }
        plugin.service().update(sender, s, list, mode, null);
    }

    private void edit(CommandSender sender, List<String> args, String label) {
        Player p = requirePlayer(sender);
        if (p == null) {
            return;
        }
        if (args.isEmpty() || args.size() > 2) {
            usage(sender, label, "edit");
            return;
        }
        Sample s = sample(p, args.get(0));
        if (s == null) {
            return;
        }
        if (args.size() == 1) {
            EditSampleGui.open(plugin, p, s);
            return;
        }
        Placement pl = placement(p, s, args.get(1));
        if (pl != null) {
            PlacementGui.openExisting(plugin, p, s, pl, null);
        }
    }

    private void delete(CommandSender sender, List<String> args, String label, boolean confirm) {
        if (args.isEmpty() || args.size() > 2) {
            usage(sender, label, "del");
            return;
        }
        Sample s = sample(sender, args.get(0));
        if (s == null) {
            return;
        }
        if (args.size() == 1) {
            plugin.service().deleteSample(sender, s, confirm, null);
            return;
        }
        Placement pl = placement(sender, s, args.get(1));
        if (pl != null) {
            plugin.service().deletePlacement(sender, s, pl, confirm, null);
        }
    }

    private void undo(CommandSender sender, List<String> args, String label, TaskManager.ConflictMode mode,
                      boolean confirm) {
        if (args.size() != 2) {
            usage(sender, label, "undo");
            return;
        }
        Sample s = sample(sender, args.get(0));
        if (s == null) {
            return;
        }
        Placement pl = placement(sender, s, args.get(1));
        if (pl != null) {
            plugin.service().undo(sender, s, pl, mode, confirm, null);
        }
    }

    private void cancel(CommandSender sender, List<String> args, String label) {
        if (args.isEmpty() || args.size() > 2) {
            usage(sender, label, "cancel");
            return;
        }
        Sample s = sample(sender, args.get(0));
        if (s == null) {
            return;
        }
        Placement pl = null;
        if (args.size() == 2) {
            pl = placement(sender, s, args.get(1));
            if (pl == null) {
                return;
            }
        }
        Placeholders ph = plugin.service().ph(s, pl);
        int n = pl == null
                ? plugin.tasks().cancel(s.key(), null, "cancelled")
                : plugin.tasks().cancel(s.key(), Sample.key(pl.name()), "cancelled");
        if (n == 0) {
            msg().send(sender, "cancel-none", ph);
        } else {
            msg().send(sender, "cancel-done", ph.with("count", n));
        }
    }

    private void schedule(CommandSender sender, List<String> args) {
        TaskManager tm = plugin.tasks();
        if (args.isEmpty()) {
            List<ZoneTask> all = tm.all();
            if (all.isEmpty()) {
                msg().send(sender, "schedule-empty");
                return;
            }
            msg().send(sender, "schedule-header", Placeholders.of("count", all.size()));
            for (ZoneTask t : all) {
                tm.printStatus(sender, t);
            }
            return;
        }
        Sample s = sample(sender, args.get(0));
        if (s == null) {
            return;
        }
        Placement pl = null;
        if (args.size() >= 2) {
            pl = placement(sender, s, args.get(1));
            if (pl == null) {
                return;
            }
        }
        List<ZoneTask> found = tm.find(s.key(), pl == null ? null : Sample.key(pl.name()));
        if (pl == null) {
            // 只指定樣本：列出它底下的所有任務（含記錄快照）
            if (found.isEmpty()) {
                msg().send(sender, "schedule-idle", plugin.service().ph(s, null));
                return;
            }
            for (ZoneTask t : found) {
                tm.printStatus(sender, t);
                if (sender instanceof Player p) {
                    tm.addViewer(t, p);
                }
            }
            return;
        }
        if (found.isEmpty()) {
            msg().send(sender, "schedule-idle", plugin.service().ph(s, pl));
            return;
        }
        for (ZoneTask t : found) {
            tm.printStatus(sender, t);
            if (sender instanceof Player p) {
                tm.addViewer(t, p);
            }
        }
    }

    private void list(CommandSender sender) {
        if (sender instanceof Player p) {
            new ListGui(plugin, p).open(p);
            return;
        }
        if (plugin.store().all().isEmpty()) {
            msg().send(sender, "list-empty");
            return;
        }
        msg().send(sender, "list-header", Placeholders.of("count", plugin.store().all().size()));
        for (Sample s : plugin.store().all()) {
            Cuboid r = s.region();
            msg().send(sender, "list-entry", Placeholders.of("sample", s.name(),
                    "world", String.valueOf(s.world()),
                    "size", r == null ? "-" : r.sizeText(),
                    "placements", s.placements().size(),
                    "snapshot", s.snapshotTime() == null ? msg().raw("gui.state.none") : s.snapshotTime()));
        }
    }

    private void reload(CommandSender sender) {
        plugin.loadSettings();
        msg().send(sender, "reload-done");
    }

    // ---------- Tab 補全 ----------

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (Map.Entry<String, String> e : SUBCOMMANDS.entrySet()) {
                if (sender.hasPermission(e.getValue())) {
                    out.add(e.getKey());
                }
            }
            return filter(out, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("delete")) {
            sub = "del";
        }
        String perm = SUBCOMMANDS.get(sub);
        if (perm == null || !sender.hasPermission(perm)) {
            return out;
        }
        // 已輸入的位置參數（不含旗標）
        List<String> positional = new ArrayList<>();
        for (int i = 1; i < args.length - 1; i++) {
            if (!args[i].startsWith("-")) {
                positional.add(args[i]);
            }
        }
        String current = args[args.length - 1];
        int pos = positional.size();
        switch (sub) {
            case "create" -> {
                // 新名稱，不補全
            }
            case "place" -> {
                if (pos == 0) {
                    out.addAll(plugin.store().names());
                }
            }
            case "edit", "schedule", "cancel" -> {
                if (pos == 0) {
                    out.addAll(plugin.store().names());
                } else if (pos == 1) {
                    out.addAll(placements(positional.get(0)));
                }
            }
            case "update" -> {
                if (pos == 0) {
                    out.addAll(plugin.store().names());
                } else if (pos == 1) {
                    out.addAll(placements(positional.get(0)));
                }
                if (pos >= 1) {
                    flags(out, args, FLAG_QUEUE, FLAG_FORCE);
                }
            }
            case "undo" -> {
                if (pos == 0) {
                    out.addAll(plugin.store().names());
                } else if (pos == 1) {
                    out.addAll(placements(positional.get(0)));
                }
                if (pos >= 2) {
                    flags(out, args, FLAG_CONFIRM, FLAG_QUEUE, FLAG_FORCE);
                }
            }
            case "del" -> {
                if (pos == 0) {
                    out.addAll(plugin.store().names());
                } else if (pos == 1) {
                    out.addAll(placements(positional.get(0)));
                }
                if (pos >= 1) {
                    flags(out, args, FLAG_CONFIRM);
                }
            }
            default -> {
            }
        }
        return filter(out, current);
    }

    private List<String> placements(String sampleName) {
        Sample s = plugin.store().get(sampleName);
        List<String> out = new ArrayList<>();
        if (s != null) {
            for (Placement p : s.placements()) {
                out.add(p.name());
            }
        }
        return out;
    }

    private static void flags(List<String> out, String[] args, String... flags) {
        for (String f : flags) {
            boolean used = false;
            for (int i = 1; i < args.length - 1; i++) {
                if (args[i].equalsIgnoreCase(f)) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                out.add(f);
            }
        }
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }
}
