package net.tapacywka.teyvatcartographia.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The settings file of 0.4 and earlier (the TOML NeoForge wrote), taken over once: while there is no JSON file yet,
 * its values are written as the JSON file the settings are then read from.
 */
public final class OldToml {
    private OldToml() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Writes {@code json} from {@code toml} when only the TOML is there; nothing otherwise. */
    public static void takeOver(Path json, Path toml) {
        if (Files.exists(json) || !Files.exists(toml)) return;
        try {
            Files.writeString(json, GSON.toJson(toJson(readToml(Files.readAllLines(toml, StandardCharsets.UTF_8)))), StandardCharsets.UTF_8);
            TeyvatCartographia.LOGGER.info("Settings taken over from {}", toml.getFileName());
        } catch (IOException | RuntimeException e) {
            TeyvatCartographia.LOGGER.warn("The old settings file {} could not be read; the defaults are used", toml, e);
        }
    }

    /** The values by "section.key", as nested JSON objects. */
    static JsonObject toJson(Map<String, Object> values) {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            String[] path = e.getKey().split("\\.");
            JsonObject o = root;
            for (int i = 0; i < path.length - 1; i++) {
                if (!o.has(path[i]) || !o.get(path[i]).isJsonObject()) o.add(path[i], new JsonObject());
                o = o.getAsJsonObject(path[i]);
            }
            o.add(path[path.length - 1], GSON.toJsonTree(e.getValue()));
        }
        return root;
    }

    /**
     * The values of the TOML NeoForge wrote ("section.key" to Boolean, Long, Double, String or a list of Strings).
     * Only what NeoForge writes there: [section] headers, key = value lines, # comments, arrays on one or more lines.
     */
    static Map<String, Object> readToml(List<String> lines) {
        Map<String, Object> out = new LinkedHashMap<>();
        String section = "";
        StringBuilder pending = null;
        String pendingKey = null;
        for (String raw : lines) {
            String line = raw.strip();
            if (pending != null) {
                pending.append(' ').append(line);
                if (line.endsWith("]")) {
                    out.put(pendingKey, tomlValue(pending.toString()));
                    pending = null;
                }
                continue;
            }
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).strip();
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = (section.isEmpty() ? "" : section + ".") + line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            if (value.startsWith("[") && !value.endsWith("]")) {
                pending = new StringBuilder(value);
                pendingKey = key;
            } else {
                out.put(key, tomlValue(value));
            }
        }
        return out;
    }

    private static Object tomlValue(String v) {
        if (v.equals("true") || v.equals("false")) return Boolean.valueOf(v);
        if (v.startsWith("\"")) return unquote(v);
        if (v.startsWith("[")) {
            List<String> list = new ArrayList<>();
            String inner = v.substring(1, v.lastIndexOf(']')).strip();
            if (!inner.isEmpty()) {
                for (String part : inner.split(",")) {
                    String p = part.strip();
                    if (!p.isEmpty()) list.add(unquote(p));
                }
            }
            return list;
        }
        try {
            return v.contains(".") || v.contains("e") || v.contains("E") ? (Object) Double.valueOf(v) : (Object) Long.valueOf(v);
        } catch (NumberFormatException e) {
            return v;
        }
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
        return v.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
