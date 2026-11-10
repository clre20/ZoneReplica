package clre20.zoneReplica.selection;

import clre20.zoneReplica.ZoneReplica;
import clre20.zoneReplica.config.PluginConfig;
import clre20.zoneReplica.model.Cuboid;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 視覺輔助外框：只有編輯中的玩家看得到，不會存進世界。
 * 發光外框以 12 條 Block Display 繪製；粒子模式每 10 tick 沿著邊線產生粒子。
 */
public final class OutlineManager {

    /** 一個要顯示的範圍。 */
    public record Box(String world, Cuboid cuboid, Color color) {
    }

    private static final float THICKNESS = 0.06f;
    private static final int MAX_PARTICLES_PER_EDGE = 48;

    private final ZoneReplica plugin;
    /** 玩家 UUID → 目前顯示的範圍。 */
    private final Map<UUID, List<Box>> boxes = new HashMap<>();
    /** 玩家 UUID → 為他產生的外框實體 UUID（不持有實體物件）。 */
    private final Map<UUID, List<UUID>> displays = new HashMap<>();
    private BukkitTask particleTask;

    public OutlineManager(ZoneReplica plugin) {
        this.plugin = plugin;
    }

    public void start() {
        particleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::drawParticles, 10L, 10L);
    }

    /** 顯示（取代）玩家看到的外框。 */
    public void show(Player player, List<Box> list) {
        UUID id = player.getUniqueId();
        List<Box> clean = new ArrayList<>();
        for (Box b : list) {
            if (b != null && b.cuboid() != null && b.world() != null) {
                clean.add(b);
            }
        }
        if (Objects.equals(boxes.get(id), clean)) {
            return;
        }
        clear(player.getUniqueId());
        if (clean.isEmpty()) {
            return;
        }
        boxes.put(id, clean);
        if (plugin.config().outlineMode() == PluginConfig.OutlineMode.DISPLAY) {
            List<UUID> spawned = new ArrayList<>();
            for (Box b : clean) {
                spawnDisplays(player, b, spawned);
            }
            displays.put(id, spawned);
        }
    }

    public void clear(UUID playerId) {
        boxes.remove(playerId);
        List<UUID> list = displays.remove(playerId);
        if (list != null) {
            for (UUID e : list) {
                Entity entity = Bukkit.getEntity(e);
                if (entity != null) {
                    entity.remove();
                }
            }
        }
    }

    public void shutdown() {
        if (particleTask != null) {
            particleTask.cancel();
        }
        for (UUID id : new ArrayList<>(boxes.keySet())) {
            clear(id);
        }
        boxes.clear();
        displays.clear();
    }

    private void spawnDisplays(Player player, Box b, List<UUID> out) {
        World world = Bukkit.getWorld(b.world());
        if (world == null) {
            return;
        }
        Cuboid c = b.cuboid();
        int ox = c.minX();
        int oy = c.minY();
        int oz = c.minZ();
        if (!world.isChunkLoaded(ox >> 4, oz >> 4)) {
            return;
        }
        float lx = c.sizeX();
        float ly = c.sizeY();
        float lz = c.sizeZ();
        float t = THICKNESS;
        float h = t / 2f;
        Location base = new Location(world, ox, oy, oz);
        // 沿 X 的 4 條邊
        for (float[] yz : new float[][]{{0, 0}, {ly, 0}, {0, lz}, {ly, lz}}) {
            spawnEdge(player, base, b.color(), new Vector3f(-h, yz[0] - h, yz[1] - h), new Vector3f(lx + t, t, t), out);
        }
        // 沿 Y 的 4 條邊
        for (float[] xz : new float[][]{{0, 0}, {lx, 0}, {0, lz}, {lx, lz}}) {
            spawnEdge(player, base, b.color(), new Vector3f(xz[0] - h, -h, xz[1] - h), new Vector3f(t, ly + t, t), out);
        }
        // 沿 Z 的 4 條邊
        for (float[] xy : new float[][]{{0, 0}, {lx, 0}, {0, ly}, {lx, ly}}) {
            spawnEdge(player, base, b.color(), new Vector3f(xy[0] - h, xy[1] - h, -h), new Vector3f(t, t, lz + t), out);
        }
    }

    private void spawnEdge(Player player, Location base, Color color, Vector3f translation, Vector3f scale,
                           List<UUID> out) {
        BlockDisplay d = base.getWorld().spawn(base, BlockDisplay.class, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.getPersistentDataContainer().set(plugin.outlineKey(), PersistentDataType.BYTE, (byte) 1);
            e.setBlock(Material.WHITE_CONCRETE.createBlockData());
            e.setTransformation(new Transformation(translation, new AxisAngle4f(), scale, new AxisAngle4f()));
            e.setGlowing(true);
            e.setGlowColorOverride(color);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setViewRange(4f);
            e.setShadowRadius(0f);
        });
        player.showEntity(plugin, d);
        out.add(d.getUniqueId());
    }

    private void drawParticles() {
        if (plugin.config().outlineMode() != PluginConfig.OutlineMode.PARTICLE || boxes.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, List<Box>> e : boxes.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) {
                continue;
            }
            for (Box b : e.getValue()) {
                if (!p.getWorld().getName().equals(b.world())) {
                    continue;
                }
                drawBox(p, b);
            }
        }
    }

    private static void drawBox(Player p, Box b) {
        Cuboid c = b.cuboid();
        double x0 = c.minX();
        double y0 = c.minY();
        double z0 = c.minZ();
        double x1 = c.maxX() + 1;
        double y1 = c.maxY() + 1;
        double z1 = c.maxZ() + 1;
        Particle.DustOptions dust = new Particle.DustOptions(b.color(), 1f);
        double[][] edges = {
                {x0, y0, z0, x1, y0, z0}, {x0, y1, z0, x1, y1, z0}, {x0, y0, z1, x1, y0, z1}, {x0, y1, z1, x1, y1, z1},
                {x0, y0, z0, x0, y1, z0}, {x1, y0, z0, x1, y1, z0}, {x0, y0, z1, x0, y1, z1}, {x1, y0, z1, x1, y1, z1},
                {x0, y0, z0, x0, y0, z1}, {x1, y0, z0, x1, y0, z1}, {x0, y1, z0, x0, y1, z1}, {x1, y1, z0, x1, y1, z1}
        };
        Location eye = p.getLocation();
        for (double[] e : edges) {
            double len = Math.max(Math.abs(e[3] - e[0]), Math.max(Math.abs(e[4] - e[1]), Math.abs(e[5] - e[2])));
            int n = (int) Math.min(MAX_PARTICLES_PER_EDGE, Math.max(2, Math.ceil(len * 2)));
            for (int i = 0; i <= n; i++) {
                double f = i / (double) n;
                double x = e[0] + (e[3] - e[0]) * f;
                double y = e[1] + (e[4] - e[1]) * f;
                double z = e[2] + (e[5] - e[2]) * f;
                if (Math.abs(x - eye.getX()) > 96 || Math.abs(z - eye.getZ()) > 96) {
                    continue;
                }
                p.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, dust);
            }
        }
    }
}
