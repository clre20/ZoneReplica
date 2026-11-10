package clre20.zoneReplica.task;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 以非同步方式載入區塊並加上插件區塊票證，處理完畢後釋放。
 * 只記錄座標鍵值，不持有 Chunk 物件。
 */
final class ChunkGate {

    private enum State { LOADING, READY, FAILED }

    private final Plugin plugin;
    private final String worldName;
    private final Map<Long, State> states = new HashMap<>();

    ChunkGate(Plugin plugin, World world) {
        this.plugin = plugin;
        this.worldName = world.getName();
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    /**
     * 確認區塊已載入（需要時連實體也已載入）；尚未就緒時發出非同步載入並回傳 false。
     *
     * @throws IllegalStateException 區塊載入失敗
     */
    boolean ensure(World world, int cx, int cz, boolean needEntities) {
        long k = key(cx, cz);
        State st = states.get(k);
        if (st == null) {
            states.put(k, State.LOADING);
            world.getChunkAtAsync(cx, cz).whenComplete((chunk, err) -> {
                if (!states.containsKey(k)) {
                    return; // 已釋放
                }
                if (err != null || chunk == null) {
                    states.put(k, State.FAILED);
                } else {
                    chunk.addPluginChunkTicket(plugin);
                    states.put(k, State.READY);
                }
            });
            return false;
        }
        if (st == State.FAILED) {
            throw new IllegalStateException("chunk " + cx + "," + cz);
        }
        if (st == State.LOADING || !world.isChunkLoaded(cx, cz)) {
            return false;
        }
        if (needEntities) {
            Chunk chunk = world.getChunkAt(cx, cz);
            return chunk.isEntitiesLoaded();
        }
        return true;
    }

    /** 預先載入（不等待）。 */
    void prefetch(World world, int cx, int cz) {
        try {
            ensure(world, cx, cz, false);
        } catch (IllegalStateException ignored) {
            // 實際處理時才回報
        }
    }

    void release(World world, int cx, int cz) {
        State st = states.remove(key(cx, cz));
        if (st == State.READY && world != null && world.isChunkLoaded(cx, cz)) {
            world.getChunkAt(cx, cz).removePluginChunkTicket(plugin);
        }
    }

    void releaseAll(World world) {
        Iterator<Map.Entry<Long, State>> it = states.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, State> e = it.next();
            it.remove();
            if (e.getValue() == State.READY && world != null) {
                int cx = (int) (e.getKey() >> 32);
                int cz = (int) (long) e.getKey();
                if (world.isChunkLoaded(cx, cz)) {
                    world.getChunkAt(cx, cz).removePluginChunkTicket(plugin);
                }
            }
        }
    }

    String worldName() {
        return worldName;
    }
}
