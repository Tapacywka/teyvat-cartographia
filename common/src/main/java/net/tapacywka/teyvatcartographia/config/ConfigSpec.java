package net.tapacywka.teyvatcartographia.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The mod's own settings file (since 0.5.0, a Fabric mod): the part of NeoForge's ModConfigSpec the mod used, with
 * the same names, kept in {@code config/teyvatcartographia-client.json} (sections as nested objects). The
 * {@code teyvatcartographia-client.toml} NeoForge wrote up to 0.4 is read once when there is no JSON file yet, so the
 * players' settings carry over. NeoForge's config could not be used: on Fabric it needs Forge Config API Port, whose
 * registry crashed the game on NeoForge through Sinytra Connector (26.10.2026).
 * <p>
 * Values are what was set (like NeoForge, {@code set} does not check them); a value read from the file that is out of
 * its range or of the wrong kind is replaced by the default.
 */
public final class ConfigSpec {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Map<String, ConfigValue<?>> values;
    private Path file;

    private ConfigSpec(Map<String, ConfigValue<?>> values) {
        this.values = values;
        for (ConfigValue<?> v : values.values()) v.spec = this;
    }

    /** Reads the file (or the old TOML of NeoForge once, or the defaults) and from now on saves there. */
    public void load(Path file, Path oldToml) {
        this.file = file;
        try {
            if (Files.exists(file)) {
                JsonObject root = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
                for (Map.Entry<String, ConfigValue<?>> e : values.entrySet()) {
                    JsonElement j = find(root, e.getKey());
                    if (j != null) e.getValue().read(j);
                }
            } else if (Files.exists(oldToml)) {
                Map<String, Object> old = readToml(Files.readAllLines(oldToml, StandardCharsets.UTF_8));
                for (Map.Entry<String, ConfigValue<?>> e : values.entrySet()) {
                    Object o = old.get(e.getKey());
                    if (o != null) e.getValue().read(GSON.toJsonTree(o));
                }
                TeyvatCartographia.LOGGER.info("Settings taken over from {}", oldToml.getFileName());
            }
        } catch (IOException | RuntimeException ex) {
            TeyvatCartographia.LOGGER.warn("The settings file {} could not be read; the defaults are used", file, ex);
        }
        save();
    }

    public void save() {
        if (file == null) return;
        JsonObject root = new JsonObject();
        for (Map.Entry<String, ConfigValue<?>> e : values.entrySet()) {
            String[] path = e.getKey().split("\\.");
            JsonObject o = root;
            for (int i = 0; i < path.length - 1; i++) {
                if (!o.has(path[i])) o.add(path[i], new JsonObject());
                o = o.getAsJsonObject(path[i]);
            }
            o.add(path[path.length - 1], e.getValue().write());
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            TeyvatCartographia.LOGGER.warn("The settings could not be saved to {}", file, ex);
        }
    }

    private static JsonElement find(JsonObject root, String key) {
        JsonElement j = root;
        for (String part : key.split("\\.")) {
            if (j == null || !j.isJsonObject()) return null;
            j = j.getAsJsonObject().get(part);
        }
        return j;
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

    public static final class Builder {
        private final Deque<String> path = new ArrayDeque<>();
        private final Map<String, ConfigValue<?>> values = new LinkedHashMap<>();

        public Builder push(String name) {
            path.addLast(name);
            return this;
        }

        public Builder pop() {
            path.removeLast();
            return this;
        }

        /** Kept from NeoForge's builder for the reader of MapConfig; JSON has no comments. */
        public Builder comment(String... comment) {
            return this;
        }

        /** Kept from NeoForge's builder; the settings screen names its rows itself. */
        public Builder translation(String key) {
            return this;
        }

        private <V extends ConfigValue<?>> V add(String name, V v) {
            List<String> full = new ArrayList<>(path);
            full.add(name);
            v.path = List.copyOf(full);
            values.put(String.join(".", full), v);
            return v;
        }

        public BooleanValue define(String name, boolean def) {
            return add(name, new BooleanValue(def));
        }

        public <T> ConfigValue<T> define(String name, T def, Predicate<Object> valid) {
            return add(name, new ConfigValue<>(def, valid));
        }

        public IntValue defineInRange(String name, int def, int min, int max) {
            return add(name, new IntValue(def, min, max));
        }

        public DoubleValue defineInRange(String name, double def, double min, double max) {
            return add(name, new DoubleValue(def, min, max));
        }

        public <E extends Enum<E>> EnumValue<E> defineEnum(String name, E def) {
            return add(name, new EnumValue<>(def, Arrays.asList(def.getDeclaringClass().getEnumConstants())));
        }

        public <E extends Enum<E>> EnumValue<E> defineEnum(String name, E def, Collection<E> acceptable) {
            return add(name, new EnumValue<>(def, acceptable));
        }

        public <T> ConfigValue<List<? extends T>> defineListAllowEmpty(String name, List<? extends T> def,
                                                                      Supplier<T> newElement, Predicate<Object> element) {
            return add(name, new ListValue<>(def, element));
        }

        public ConfigSpec build() {
            return new ConfigSpec(values);
        }
    }

    public static class ConfigValue<T> implements Supplier<T> {
        private final T def;
        private final Predicate<Object> valid;
        List<String> path = List.of();
        private T value;
        ConfigSpec spec;

        ConfigValue(T def, Predicate<Object> valid) {
            this.def = def;
            this.valid = valid;
            this.value = def;
        }

        @Override
        public T get() {
            return value;
        }

        public T getDefault() {
            return def;
        }

        public void set(T v) {
            value = v;
        }

        /** The section names and the value's own name. */
        public List<String> getPath() {
            return path;
        }

        /** Whether a value would be kept when read from the file (its kind and range). */
        public Predicate<Object> getSpec() {
            return valid;
        }

        public void save() {
            if (spec != null) spec.save();
        }

        JsonElement write() {
            return GSON.toJsonTree(value);
        }

        /** A value from the file: kept when valid, else the default. */
        void read(JsonElement j) {
            Object o = parse(j);
            set(o != null && valid.test(o) ? cast(o) : def);
        }

        Object parse(JsonElement j) {
            if (!j.isJsonPrimitive()) return null;
            JsonPrimitive p = j.getAsJsonPrimitive();
            if (def instanceof Boolean) return p.isBoolean() ? p.getAsBoolean() : null;
            return p.isString() ? p.getAsString() : null;
        }

        @SuppressWarnings("unchecked")
        T cast(Object o) {
            return (T) o;
        }
    }

    public static final class BooleanValue extends ConfigValue<Boolean> {
        BooleanValue(boolean def) {
            super(def, o -> o instanceof Boolean);
        }
    }

    public static final class IntValue extends ConfigValue<Integer> {
        IntValue(int def, int min, int max) {
            super(def, o -> o instanceof Integer i && i >= min && i <= max);
        }

        @Override
        Object parse(JsonElement j) {
            if (!j.isJsonPrimitive() || !j.getAsJsonPrimitive().isNumber()) return null;
            double d = j.getAsDouble();
            return d == Math.rint(d) && Math.abs(d) <= Integer.MAX_VALUE ? (Object) (int) d : null;
        }
    }

    public static final class DoubleValue extends ConfigValue<Double> {
        DoubleValue(double def, double min, double max) {
            super(def, o -> o instanceof Double d && d >= min && d <= max);
        }

        @Override
        Object parse(JsonElement j) {
            return j.isJsonPrimitive() && j.getAsJsonPrimitive().isNumber() ? (Object) j.getAsDouble() : null;
        }
    }

    public static final class EnumValue<E extends Enum<E>> extends ConfigValue<E> {
        EnumValue(E def, Collection<E> acceptable) {
            super(def, o -> acceptable.contains(o));
        }

        @Override
        JsonElement write() {
            return new JsonPrimitive(get().name());
        }

        @Override
        Object parse(JsonElement j) {
            if (!j.isJsonPrimitive() || !j.getAsJsonPrimitive().isString()) return null;
            for (E e : getDefault().getDeclaringClass().getEnumConstants()) {
                if (e.name().equalsIgnoreCase(j.getAsString())) return e;
            }
            return null;
        }
    }

    static final class ListValue<T> extends ConfigValue<List<? extends T>> {
        ListValue(List<? extends T> def, Predicate<Object> element) {
            super(def, o -> o instanceof List<?> l && l.stream().allMatch(element));
        }

        @Override
        JsonElement write() {
            JsonArray a = new JsonArray();
            for (T t : get()) a.add(String.valueOf(t));
            return a;
        }

        @Override
        Object parse(JsonElement j) {
            if (!j.isJsonArray()) return null;
            List<Object> l = new ArrayList<>();
            for (JsonElement e : j.getAsJsonArray()) {
                if (!e.isJsonPrimitive()) return null;
                l.add(e.getAsString());
            }
            return List.copyOf(l);
        }
    }
}
