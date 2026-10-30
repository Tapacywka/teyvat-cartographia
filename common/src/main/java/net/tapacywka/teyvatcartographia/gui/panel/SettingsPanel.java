package net.tapacywka.teyvatcartographia.gui.panel;

import net.tapacywka.teyvatcartographia.version.Game;

import net.tapacywka.teyvatcartographia.config.ConfigSpec;
import net.tapacywka.teyvatcartographia.config.MapConfig;
import net.tapacywka.teyvatcartographia.gui.TeyvatConfigScreen;
import net.tapacywka.teyvatcartographia.gui.Ui;
import net.tapacywka.teyvatcartographia.gui.Ui.Grid;
import net.tapacywka.teyvatcartographia.gui.WorldMapScreen;
import net.tapacywka.teyvatcartographia.render.DrawText;
import net.tapacywka.teyvatcartographia.render.Icon;
import net.tapacywka.teyvatcartographia.render.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.BooleanSupplier;

/** Dark "Map Settings" panel with section headers and switches (reference: fullscreen_settings.png). */
public final class SettingsPanel extends SidePanel {
    private record Row(Component label, Component tooltip, boolean section, BooleanSupplier get, Runnable toggle) {}

    private static Row section(String key) {
        return new Row(Component.translatable(key), null, true, null, null);
    }

    /** {@code tooltipKey}: the matching full-settings option, whose description is reused as tooltip. */
    private static Row option(String key, String tooltipKey, ConfigSpec.BooleanValue v) {
        return new Row(Component.translatable(key), Component.translatable("teyvatcartographia.config." + tooltipKey + ".tooltip"), false, () -> MapConfig.get(v), () -> {
            v.set(!MapConfig.get(v));
            MapConfig.save();
        });
    }

    /**
     * A kind of pins on the world map: on while the kind and its map switch are both on; turning it on turns on both
     * (the tooltip is the kind's own).
     */
    private static Row pins(String key, MapConfig.PinKind kind) {
        MapConfig.PinOptions o = MapConfig.pins(kind);
        return new Row(Component.translatable(key), Component.translatable(key + ".tooltip"), false, o::onMap, () -> {
            boolean on = !o.onMap();
            o.map.set(on);
            if (on) o.enabled.set(true);
            MapConfig.save();
        });
    }

    /**
     * The path to the tracked pin on the world map, for every kind at once: on while any kind shows it there; turning
     * it off turns off the map switch of every kind, turning it on turns on the path and its map switch of every kind.
     */
    private static Row path() {
        return new Row(Component.translatable("teyvatcartographia.settings.path"), Component.translatable("teyvatcartographia.settings.path.tooltip"),
                false, SettingsPanel::anyPathOnMap, () -> {
                    boolean on = !anyPathOnMap();
                    for (MapConfig.PinKind kind : MapConfig.PinKind.values()) {
                        MapConfig.PinOptions o = MapConfig.pins(kind);
                        o.pathMap.set(on);
                        if (on) o.path.set(true);
                    }
                    MapConfig.save();
                });
    }

    /** Plants on the map (the list of which ones is on the full settings screen, which its tooltip names); the chunks around are mapped again. */
    private static Row plants() {
        return new Row(Component.translatable("teyvatcartographia.settings.plants"), Component.translatable("teyvatcartographia.settings.plants.tooltip"),
                false, () -> MapConfig.get(MapConfig.MAP_PLANTS), () -> {
                    MapConfig.MAP_PLANTS.set(!MapConfig.get(MapConfig.MAP_PLANTS));
                    MapConfig.save();
                    net.tapacywka.teyvatcartographia.map.data.MapDataManager.get().remapLoaded();
                });
    }

    private static boolean anyPathOnMap() {
        for (MapConfig.PinKind kind : MapConfig.PinKind.values()) if (MapConfig.pins(kind).pathOnMap()) return true;
        return false;
    }

    /** Quick switches for what the map shows; everything else is on the full settings screen. */
    private final List<Row> rows = List.of(
            section("teyvatcartographia.settings.section.display"),
            pins("teyvatcartographia.settings.custom_pins", MapConfig.PinKind.CUSTOM),
            pins("teyvatcartographia.settings.interactive_pins", MapConfig.PinKind.INTERACTIVE),
            pins("teyvatcartographia.settings.system_pins", MapConfig.PinKind.SYSTEM),
            path(),
            plants(),
            option("teyvatcartographia.settings.labels", "map_labels", MapConfig.MAP_SHOW_LABELS),
            option("teyvatcartographia.settings.grid", "map_grid", MapConfig.MAP_SHOW_GRID));

    public SettingsPanel(WorldMapScreen screen) {
        super(screen);
    }

    @Override
    protected boolean dark() {
        return true;
    }

    @Override
    protected Component title() {
        return Component.translatable("teyvatcartographia.settings.title");
    }

    @Override
    protected Icon titleIcon() {
        return Icon.GEAR;
    }

    private float rowY(int i) {
        return contentY() + i * Grid.ROW;
    }

    private float toggleX() {
        return contentX() + contentW() - Grid.TOGGLE_W;
    }

    private float toggleY(int i) {
        return rowY(i) + (Grid.ROW - Grid.TOGGLE_H) / 2f;
    }

    @Override
    protected void renderBody(GuiGraphicsExtractor g, int mx, int my, float bx, float by, float bw) {
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            float ty = DrawText.textY(rowY(i), Grid.ROW);
            if (r.section) {
                DrawText.room(r.label, bw);
                DrawText.text(g, r.label, bx, ty, Theme.GOLD, false);
            } else {
                DrawText.text(g, DrawText.fit(r.label.getString(), (int) (bw - Grid.TOGGLE_W - Grid.GAP)), bx, ty, Theme.WHITE, false);
                Ui.toggle(g, toggleX(), toggleY(i), r.get.getAsBoolean(), mx, my);
            }
        }
        Ui.actionButton(g, bx, bottomActionY(), bw, Component.translatable("teyvatcartographia.settings.all"), Icon.GEAR, true, mx, my);
        // Tooltip for the option whose label is under the cursor.
        if (mx >= bx && mx < toggleX() - Grid.GAP) {
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                if (r.tooltip != null && my >= rowY(i) && my < rowY(i) + Grid.ROW) {
                    Ui.wrappedTooltip(g, r.label, r.tooltip, mx, my, screen.width, screen.height);
                    break;
                }
            }
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (closeClicked(mx, my)) return true;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (!r.section && Ui.toggleHit(toggleX(), toggleY(i), mx, my)) {
                r.toggle.run();
                return true;
            }
        }
        if (Ui.actionHit(contentX(), bottomActionY(), contentW(), mx, my)) {
            Game.setScreen(new TeyvatConfigScreen(screen));
            return true;
        }
        return contains(mx, my);
    }
}
