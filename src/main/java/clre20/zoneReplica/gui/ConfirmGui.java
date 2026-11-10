package clre20.zoneReplica.gui;

import clre20.zoneReplica.ZoneReplica;
import clre20.zoneReplica.util.Placeholders;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 4.6 確認介面（3 列）：格位 4 顯示操作內容，11 / 13 / 15 為按鈕。
 */
public final class ConfirmGui extends Gui {

    public record Button(int slot, Material material, String key, Consumer<Player> action) {
    }

    private final String titleKey;
    private final Placeholders ph;
    private final List<Component> infoLines;
    private final List<Button> buttons;

    public ConfirmGui(ZoneReplica plugin, Player viewer, String titleKey, Placeholders ph,
                      List<Component> infoLines, List<Button> buttons) {
        super(plugin, viewer);
        this.titleKey = titleKey;
        this.ph = ph;
        this.infoLines = infoLines == null ? new ArrayList<>() : infoLines;
        this.buttons = buttons;
    }

    @Override
    protected int rows() {
        return 3;
    }

    @Override
    protected Component title() {
        return plugin.messages().component(titleKey, ph);
    }

    @Override
    protected void build() {
        set(4, Items.of(plugin, Material.BOOK, "gui.confirm.info", ph, infoLines));
        for (Button b : buttons) {
            set(b.slot(), Items.of(plugin, b.material(), b.key(), ph), b.action());
        }
    }
}
