package net.tapacywka.teyvatcartographia.gui.panel;

import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceKey;
import net.tapacywka.teyvatcartographia.gui.Ui;
import net.tapacywka.teyvatcartographia.gui.Ui.Grid;
import net.tapacywka.teyvatcartographia.gui.WorldMapScreen;
import net.tapacywka.teyvatcartographia.map.data.MapDataManager;
import net.tapacywka.teyvatcartographia.render.Draw;
import net.tapacywka.teyvatcartographia.render.Icon;
import net.tapacywka.teyvatcartographia.render.Theme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Dark two-column grid of mapped dimensions (reference: fullscreen_regions.png "Map" list): the vanilla ones first in
 * a fixed order, then a thin line and the modded ones by name, whatever dimension the player is in.
 */
public final class DimensionPanel extends SidePanel {
    private static final List<ResourceKey<Level>> VANILLA = List.of(Level.OVERWORLD, Level.NETHER, Level.END);

    private final List<ResourceKey<Level>> dims = new ArrayList<>();
    /** Number of vanilla dimensions at the start of {@link #dims}. */
    private final int vanilla;

    public DimensionPanel(WorldMapScreen screen) {
        super(screen);
        List<ResourceKey<Level>> known = MapDataManager.get().knownDimensions();
        for (ResourceKey<Level> d : VANILLA) if (known.contains(d)) dims.add(d);
        vanilla = dims.size();
        List<ResourceKey<Level>> modded = new ArrayList<>();
        for (ResourceKey<Level> d : known) if (!VANILLA.contains(d)) modded.add(d);
        modded.sort(Comparator.comparing((ResourceKey<Level> d) -> WorldMapScreen.dimensionName(d).getString(), String.CASE_INSENSITIVE_ORDER));
        dims.addAll(modded);
    }

    @Override
    protected boolean dark() {
        return true;
    }

    @Override
    protected Component title() {
        return Component.translatable("teyvatcartographia.dimensions.title");
    }

    @Override
    protected Icon titleIcon() {
        return Icon.EARTH;
    }

    private float tileW() {
        return (contentW() - Grid.GAP) / 2f;
    }

    /** Whether there is a line between the vanilla and the modded dimensions. */
    private boolean split() {
        return vanilla > 0 && vanilla < dims.size();
    }

    /** Top of the line between the groups: below the last vanilla row, one gap down. */
    private float lineY() {
        return contentY() + ((vanilla + 1) / 2) * (Grid.TILE_H + Grid.GAP);
    }

    private float tileX(int i) {
        int k = i < vanilla ? i : i - vanilla;
        return contentX() + (k % 2) * (tileW() + Grid.GAP);
    }

    private float tileY(int i) {
        if (i < vanilla || !split()) return contentY() + (i / 2) * (Grid.TILE_H + Grid.GAP);
        return lineY() + 1 + Grid.GAP + ((i - vanilla) / 2) * (Grid.TILE_H + Grid.GAP);
    }

    @Override
    protected void renderBody(GuiGraphics g, int mx, int my, float bx, float by, float bw) {
        for (int i = 0; i < dims.size(); i++) {
            Ui.gridTile(g, tileX(i), tileY(i), tileW(), WorldMapScreen.dimensionName(dims.get(i)), dims.get(i) == screen.viewedDimension(), mx, my);
        }
        if (split()) Draw.rect(g, contentX(), lineY(), contentX() + contentW(), lineY() + 1, Theme.PANEL_LINE);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (closeClicked(mx, my)) return true;
        for (int i = 0; i < dims.size(); i++) {
            if (Draw.inRect(mx, my, tileX(i), tileY(i), tileW(), Grid.TILE_H)) {
                screen.viewDimension(dims.get(i));
                return true;
            }
        }
        return contains(mx, my);
    }
}
