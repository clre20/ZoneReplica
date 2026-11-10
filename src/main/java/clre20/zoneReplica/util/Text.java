package clre20.zoneReplica.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Map;

/**
 * 文字工具：& 顏色碼、&#RRGGBB 十六進位色碼與 {key} 佔位字替換。
 */
public final class Text {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    private Text() {
    }

    /** 將含 & 色碼的字串轉為 Component。 */
    public static Component parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        return LEGACY.deserialize(raw);
    }

    /** 物品名稱與 Lore 用：預設不要斜體。 */
    public static Component item(String raw) {
        return parse(raw).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** 去除色碼後的純文字（後台記錄用）。 */
    public static String plain(String raw) {
        return PlainTextComponentSerializer.plainText().serialize(parse(raw));
    }

    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** 以 {key} 替換佔位字。 */
    public static String replace(String raw, Map<String, String> placeholders) {
        if (raw == null) {
            return "";
        }
        if (placeholders == null || placeholders.isEmpty() || raw.indexOf('{') < 0) {
            return raw;
        }
        StringBuilder out = new StringBuilder(raw.length() + 16);
        int i = 0;
        int len = raw.length();
        while (i < len) {
            char c = raw.charAt(i);
            if (c == '{') {
                int end = raw.indexOf('}', i + 1);
                if (end > i) {
                    String value = placeholders.get(raw.substring(i + 1, end));
                    if (value != null) {
                        out.append(value);
                        i = end + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
