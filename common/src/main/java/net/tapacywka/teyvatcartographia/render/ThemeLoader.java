package net.tapacywka.teyvatcartographia.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.tapacywka.teyvatcartographia.map.data.MapDataManager;
import net.tapacywka.teyvatcartographia.map.data.MapPalette;
import net.tapacywka.teyvatcartographia.map.data.TextureColors;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds the available themes and applies the selected one ({@link MapConfig#THEME}) on every resource reload
 * (F3+T, resource pack changes) and when the player picks another theme ({@link #applyNow}).
 * <p>
 * A theme is a folder {@code assets/teyvatcartographia/themes/<id>/} with
 * <ul>
 *   <li>{@code theme.json}: {@code { "name": "<translation key or text>" }}; its presence makes the theme selectable;</li>
 *   <li>{@code colors.json}: {@code { "colors": { "panel_dark": "#F2272F3A", ... } }} ({@code #AARRGGBB} or
 *       {@code #RRGGBB}; keys are the colour fields of {@link Theme} in lower case);</li>
 *   <li>{@code icons/64/<name>.png} (64 x 64 each, see {@link IconAtlas}) and optionally {@code fog.png};</li>
 *   <li>{@code dimensions/<namespace>/<path>.json}: {@code { "blocks": { "<block id>": "#AARRGGBB", ... } }}, the map
 *       colours of blocks in that dimension ({@link MapPalette#dimension}), for a modded world drawn its own way.</li>
 * </ul>
 * Colours not given by the theme keep the built-in defaults; missing icons and fog fall back to the built-in
 * {@code default} theme ({@link Theme#DEFAULT}). Every resource pack providing a theme's {@code colors.json} is
 * merged (lowest priority first), so a pack may change a single colour of any theme.
 * <p>
 * Only enabled resource packs are seen, so a pack's theme becomes selectable once the pack is enabled. A selected theme
 * whose pack was disabled falls back to the default theme. The shapes of the minimap and the interface are not part of
 * a theme: they are settings of their own ({@link Theme.Style}), applied here too.
 */
public final class ThemeLoader extends SimplePreparableReloadListener<ThemeLoader.Prepared> {
    /** {@code legacy}: the setting named a theme of 0.1.x (see {@link Theme#LEGACY}), replaced by the default one. */
    record Prepared(List<String> ids, Map<String, Component> names, String id, Map<String, Integer> colors, boolean legacy,
                    Map<ResourceLocation, Map<ResourceLocation, Integer>> palettes) {}

    private static List<String> available = List.of(Theme.DEFAULT);
    private static Map<String, Component> names = Map.of();

    /** Ids of the selectable themes, the default one first. */
    public static List<String> available() {
        return available;
    }

    public static Component displayName(String id) {
        Component c = names.get(id);
        return c != null ? c : Component.literal(id);
    }

    /** Re-reads and applies the theme now (after the player changed it in the settings). */
    public static void applyNow() {
        ResourceManager rm = Minecraft.getInstance().getResourceManager();
        ThemeLoader loader = new ThemeLoader();
        loader.apply(loader.prepare(rm, InactiveProfiler.INSTANCE), rm, InactiveProfiler.INSTANCE);
    }

    @Override
    protected Prepared prepare(ResourceManager rm, ProfilerFiller profiler) {
        List<String> ids = new ArrayList<>();
        Map<String, Component> titles = new HashMap<>();
        Map<ResourceLocation, Resource> found = rm.listResources("themes", l -> l.getPath().endsWith("/theme.json"));
        for (Map.Entry<ResourceLocation, Resource> e : found.entrySet()) {
            ResourceLocation loc = e.getKey();
            if (!loc.getNamespace().equals(TeyvatCartographia.MOD_ID)) continue;
            String[] parts = loc.getPath().split("/");
            if (parts.length != 3) continue; // themes/<id>/theme.json
            String id = parts[1];
            try (Reader r = e.getValue().openAsReader()) {
                JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
                String name = root.has("name") ? root.get("name").getAsString() : id;
                titles.put(id, Component.translatableWithFallback(name, name));
                ids.add(id);
            } catch (Exception ex) {
                TeyvatCartographia.LOGGER.warn("Could not read theme {}", loc, ex);
            }
        }
        Collections.sort(ids);
        ids.remove(Theme.DEFAULT);
        ids.add(0, Theme.DEFAULT);

        String id = MapConfig.get(MapConfig.THEME);
        // The Minecraft theme and the Genshin Impact pack of 0.1.x are the default theme now (their shapes are settings).
        boolean legacy = Theme.LEGACY.contains(id) && !ids.contains(id);
        if (!ids.contains(id)) {
            if (!id.equals(Theme.DEFAULT) && !legacy) TeyvatCartographia.LOGGER.warn("Theme '{}' not found, using the default theme", id);
            id = Theme.DEFAULT;
        }
        return new Prepared(List.copyOf(ids), titles, id, readColors(rm, id), legacy, readPalettes(rm, id));
    }

    private static Map<String, Integer> readColors(ResourceManager rm, String themeId) {
        Map<String, Integer> colors = new HashMap<>();
        ResourceLocation loc = Theme.asset(themeId, "colors.json");
        for (Resource res : rm.getResourceStack(loc)) {
            try (Reader r = res.openAsReader()) {
                JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
                JsonObject obj = root.has("colors") ? root.getAsJsonObject("colors") : root;
                for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                    String key = e.getKey().toLowerCase(Locale.ROOT);
                    if (!Theme.isKey(key)) {
                        TeyvatCartographia.LOGGER.warn("Theme {} ({}): unknown colour '{}'", loc, res.sourcePackId(), e.getKey());
                        continue;
                    }
                    Integer argb = parse(e.getValue().getAsString());
                    if (argb == null) {
                        TeyvatCartographia.LOGGER.warn("Theme {} ({}): bad colour '{}' for '{}'", loc, res.sourcePackId(), e.getValue(), e.getKey());
                    } else {
                        colors.put(key, argb);
                    }
                }
            } catch (Exception ex) {
                TeyvatCartographia.LOGGER.warn("Could not read theme colours {} from {}", loc, res.sourcePackId(), ex);
            }
        }
        return colors;
    }

    /**
     * The palettes per dimension: {@code themes/<theme>/dimensions/<namespace>/<path>.json} gives the blocks of the
     * dimension {@code <namespace>:<path>} their colours. The default theme's palettes first, the selected theme's
     * replace them dimension by dimension; every resource pack providing a file is merged (lowest priority first).
     */
    private static Map<ResourceLocation, Map<ResourceLocation, Integer>> readPalettes(ResourceManager rm, String themeId) {
        Map<ResourceLocation, Map<ResourceLocation, Integer>> out = new HashMap<>();
        for (String theme : themeId.equals(Theme.DEFAULT) ? List.of(Theme.DEFAULT) : List.of(Theme.DEFAULT, themeId)) {
            String folder = "themes/" + theme + "/dimensions/";
            Map<ResourceLocation, List<Resource>> found = rm.listResourceStacks(folder.substring(0, folder.length() - 1),
                    l -> l.getPath().endsWith(".json"));
            for (Map.Entry<ResourceLocation, List<Resource>> e : found.entrySet()) {
                ResourceLocation loc = e.getKey();
                if (!loc.getNamespace().equals(TeyvatCartographia.MOD_ID)) continue;
                ResourceLocation dimension = dimensionOf(loc.getPath().substring(folder.length()));
                if (dimension == null) continue;
                Map<ResourceLocation, Integer> blocks = new HashMap<>();
                for (Resource res : e.getValue()) {
                    try (Reader r = res.openAsReader()) {
                        blocks.putAll(paletteBlocks(JsonParser.parseReader(r).getAsJsonObject(), loc + " (" + res.sourcePackId() + ")"));
                    } catch (Exception ex) {
                        TeyvatCartographia.LOGGER.warn("Could not read the map palette {} from {}", loc, res.sourcePackId(), ex);
                    }
                }
                out.put(dimension, blocks);
            }
        }
        return out;
    }

    /** {@code aether/the_aether.json} as the dimension {@code aether:the_aether}; null for another path. */
    static ResourceLocation dimensionOf(String file) {
        int slash = file.indexOf('/');
        if (slash <= 0 || !file.endsWith(".json")) return null;
        return ResourceLocation.tryBuild(file.substring(0, slash), file.substring(slash + 1, file.length() - ".json".length()));
    }

    /** The {@code "blocks"} of a palette file: block id to colour; bad ids and colours are left out (and logged). */
    static Map<ResourceLocation, Integer> paletteBlocks(JsonObject root, String where) {
        Map<ResourceLocation, Integer> blocks = new HashMap<>();
        if (!root.has("blocks")) return blocks;
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("blocks").entrySet()) {
            ResourceLocation block = ResourceLocation.tryParse(e.getKey());
            Integer argb = e.getValue().isJsonPrimitive() ? parse(e.getValue().getAsString()) : null;
            if (block == null || argb == null) {
                TeyvatCartographia.LOGGER.warn("Map palette {}: bad entry '{}': {}", where, e.getKey(), e.getValue());
            } else {
                blocks.put(block, argb);
            }
        }
        return blocks;
    }

    @Override
    protected void apply(Prepared p, ResourceManager rm, ProfilerFiller profiler) {
        available = p.ids();
        names = p.names();
        Theme.id = p.id();
        if (p.legacy()) {
            // Saved once, so the old id is gone from the settings file (the shapes keep their defaults).
            MapConfig.THEME.set(Theme.DEFAULT);
            MapConfig.save();
        }
        Theme.Style.update();
        Theme.apply(p.colors());
        IconAtlas.invalidate();
        FogTexture.invalidate();
        // The palettes per dimension, for the blocks of the mods in the game (others are left out); the chunks around
        // the player are mapped again in the new colours.
        Map<ResourceKey<Level>, Map<Block, Integer>> palettes = new HashMap<>();
        for (Map.Entry<ResourceLocation, Map<ResourceLocation, Integer>> e : p.palettes().entrySet()) {
            Map<Block, Integer> blocks = new HashMap<>();
            e.getValue().forEach((id, argb) -> BuiltInRegistries.BLOCK.getOptional(id).ifPresent(b -> blocks.put(b, argb)));
            if (!blocks.isEmpty()) palettes.put(ResourceKey.create(Registries.DIMENSION, e.getKey()), Map.copyOf(blocks));
        }
        MapPalette.setDimensions(palettes);
        TextureColors.reloaded();
        MapDataManager.get().remapLoaded();
        TeyvatCartographia.LOGGER.info("Teyvat Cartographia: theme '{}' applied", p.id());
    }

    static Integer parse(String s) {
        String hex = s.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        else if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2);
        if (hex.length() != 6 && hex.length() != 8) return null;
        try {
            long v = Long.parseLong(hex, 16);
            return hex.length() == 6 ? (int) (0xFF000000L | v) : (int) v;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
