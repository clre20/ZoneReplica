package clre20.zoneReplica;

import clre20.zoneReplica.command.ZrCommand;
import clre20.zoneReplica.config.Messages;
import clre20.zoneReplica.config.PluginConfig;
import clre20.zoneReplica.gui.ChatInput;
import clre20.zoneReplica.gui.GuiListener;
import clre20.zoneReplica.gui.Sessions;
import clre20.zoneReplica.selection.OutlineManager;
import clre20.zoneReplica.selection.SelectionManager;
import clre20.zoneReplica.service.ZoneService;
import clre20.zoneReplica.storage.ZoneStore;
import clre20.zoneReplica.task.AutoUpdateScheduler;
import clre20.zoneReplica.task.TaskManager;
import clre20.zoneReplica.update.UpdateChecker;
import clre20.zoneReplica.update.UpdateNotifier;
import clre20.zoneReplica.util.Placeholders;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class ZoneReplica extends JavaPlugin implements Listener {

    private final PluginConfig config = new PluginConfig();
    private final Messages messages = new Messages();
    /** 檔案讀寫專用的單一執行緒（快照、備份、設定存檔）。 */
    private ExecutorService ioExecutor;
    /** 網路請求專用的單一執行緒（檢查更新、查詢玩家名稱）。 */
    private ExecutorService netExecutor;
    private ZoneStore store;
    private ZoneService service;
    private TaskManager tasks;
    private AutoUpdateScheduler scheduler;
    private UpdateChecker updateChecker;
    private UpdateNotifier updateNotifier;
    private OutlineManager outlines;
    private SelectionManager selections;
    private ChatInput chatInput;
    private Sessions sessions;
    private NamespacedKey toolKey;
    private NamespacedKey outlineKey;
    private BukkitTask saveTask;

    @Override
    public void onEnable() {
        ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ZoneReplica-IO");
            t.setDaemon(true);
            return t;
        });
        netExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ZoneReplica-Net");
            t.setDaemon(true);
            return t;
        });
        toolKey = new NamespacedKey(this, "selection_tool");
        outlineKey = new NamespacedKey(this, "outline");

        loadSettings();

        store = new ZoneStore(this, ioExecutor);
        store.loadAll();
        service = new ZoneService(this);

        updateChecker = new UpdateChecker(this, netExecutor);
        updateNotifier = new UpdateNotifier(this);
        outlines = new OutlineManager(this);
        selections = new SelectionManager(this);
        chatInput = new ChatInput(this);
        sessions = new Sessions(this);
        tasks = new TaskManager(this);
        scheduler = new AutoUpdateScheduler(this);

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(updateNotifier, this);
        Bukkit.getPluginManager().registerEvents(tasks, this);
        Bukkit.getPluginManager().registerEvents(selections, this);
        Bukkit.getPluginManager().registerEvents(chatInput, this);
        Bukkit.getPluginManager().registerEvents(new GuiListener(this), this);
        Bukkit.getPluginManager().registerEvents(sessions, this);

        PluginCommand command = getCommand("zonereplica");
        if (command != null) {
            ZrCommand zr = new ZrCommand(this);
            command.setExecutor(zr);
            command.setTabCompleter(zr);
        }

        tasks.restore();
        tasks.start();
        scheduler.start();
        outlines.start();
        selections.start();
        chatInput.start();
        sessions.start();
        saveTask = Bukkit.getScheduler().runTaskTimer(this, store::flushAsync, 600L, 600L);

        messages.log("log.enabled", Placeholders.of(
                "version", getPluginMeta().getVersion(), "samples", store.all().size()));
        updateNotifier.checkOnStartup();
    }

    @Override
    public void onDisable() {
        if (saveTask != null) {
            saveTask.cancel();
        }
        if (scheduler != null) {
            scheduler.stop();
        }
        if (tasks != null) {
            tasks.shutdown();
        }
        if (selections != null) {
            selections.shutdown();
        }
        if (outlines != null) {
            outlines.shutdown();
        }
        if (chatInput != null) {
            chatInput.shutdown();
        }
        if (sessions != null) {
            sessions.shutdown();
        }
        if (store != null) {
            store.flushSync();
        }
        shutdownExecutor(ioExecutor);
        shutdownExecutor(netExecutor);
    }

    private static void shutdownExecutor(ExecutorService ex) {
        if (ex == null) {
            return;
        }
        ex.shutdown();
        try {
            if (!ex.awaitTermination(30, TimeUnit.SECONDS)) {
                ex.shutdownNow();
            }
        } catch (InterruptedException e) {
            ex.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 讀取（或重新讀取）config.yml 與 messages.yml，缺少的項目自動補齊。 */
    public void loadSettings() {
        if (!getDataFolder().isDirectory()) {
            getDataFolder().mkdirs();
        }
        config.load(this);
        messages.load(this, config.prefix());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        tasks.rejoin(event.getPlayer());
    }

    public PluginConfig config() { return config; }
    public Messages messages() { return messages; }
    public ZoneStore store() { return store; }
    public ZoneService service() { return service; }
    public TaskManager tasks() { return tasks; }
    public UpdateChecker updateChecker() { return updateChecker; }
    public UpdateNotifier updateNotifier() { return updateNotifier; }
    public OutlineManager outlines() { return outlines; }
    public SelectionManager selections() { return selections; }
    public ChatInput chatInput() { return chatInput; }
    public Sessions sessions() { return sessions; }
    public ExecutorService ioExecutor() { return ioExecutor; }
    public ExecutorService netExecutor() { return netExecutor; }
    public NamespacedKey toolKey() { return toolKey; }
    public NamespacedKey outlineKey() { return outlineKey; }
}
