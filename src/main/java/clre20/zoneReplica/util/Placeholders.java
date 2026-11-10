package clre20.zoneReplica.util;

import java.util.HashMap;
import java.util.Map;

/**
 * 佔位字建構器：Placeholders.of("sample", name, "placement", p)。
 */
public final class Placeholders {

    private final Map<String, String> map;

    private Placeholders(Map<String, String> map) {
        this.map = map;
    }

    public static Placeholders of(Object... keyValues) {
        Map<String, String> m = new HashMap<>(Math.max(4, keyValues.length));
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            m.put(String.valueOf(keyValues[i]), String.valueOf(keyValues[i + 1]));
        }
        return new Placeholders(m);
    }

    public static Placeholders empty() {
        return new Placeholders(new HashMap<>(4));
    }

    public Placeholders with(String key, Object value) {
        map.put(key, String.valueOf(value));
        return this;
    }

    public Placeholders withAll(Placeholders other) {
        if (other != null) {
            map.putAll(other.map);
        }
        return this;
    }

    public Map<String, String> map() {
        return map;
    }
}
