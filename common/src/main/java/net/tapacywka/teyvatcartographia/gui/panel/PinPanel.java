package net.tapacywka.teyvatcartographia.gui.panel;

import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceKey;
import net.tapacywka.teyvatcartographia.gui.ChipField;
import net.tapacywka.teyvatcartographia.gui.Cursors;
import net.tapacywka.teyvatcartographia.gui.SmoothScroll;
import net.tapacywka.teyvatcartographia.gui.Ui;
import net.tapacywka.teyvatcartographia.gui.Ui.Grid;
import net.tapacywka.teyvatcartographia.gui.WorldMapScreen;
import net.tapacywka.teyvatcartographia.marker.Marker;
import net.tapacywka.teyvatcartographia.marker.MarkerStore;
import net.tapacywka.teyvatcartographia.marker.MarkerType;
import net.tapacywka.teyvatcartographia.nav.Navigation;
import net.tapacywka.teyvatcartographia.render.Draw;
import net.tapacywka.teyvatcartographia.render.DrawText;
import net.tapacywka.teyvatcartographia.render.Icon;
import net.tapacywka.teyvatcartographia.render.MarkerRenderer;
import net.tapacywka.teyvatcartographia.render.Theme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The pin panel (reference: fullscreen_custom_markers.png). One panel for everything a pin needs:
 * <ul>
 *   <li>new pin: name, coordinates, type grid, total pins, "Confirm";</li>
 *   <li>existing pin (opened by clicking it): the same editor plus "Track", delete in the total-pins bar;</li>
 *   <li>read-only view (automatic points): coordinates and "Track" only.</li>
 * </ul>
 * The type grid has two sections, "Custom" and "Interactive", both for every player (the interactive pins were for
 * operators only, shared by the server, until 24.10.2026).
 */
public final class PinPanel extends SidePanel {
    private static final List<MarkerType.Category> SECTIONS = List.of(MarkerType.Category.EXPLORATION, MarkerType.Category.INTERACTION);
    private static final java.util.Map<MarkerType.Category, List<MarkerType>> SECTION_TYPES = new java.util.EnumMap<>(MarkerType.Category.class);
    static {
        for (MarkerType.Category c : SECTIONS) SECTION_TYPES.put(c, Arrays.stream(MarkerType.values()).filter(t -> t.category == c).toList());
    }
    private static final int CELL = Grid.SLOT + Grid.GAP;
    /** Section header: one text line above the section's tiles. */
    private static final int SECTION_HEAD = DrawText.LINE + Grid.GAP;

    private enum Action { TRACK, CONFIRM }

    private final Marker editing;
    private final ResourceKey<Level> dimension;
    private final int px, py, pz;
    private MarkerType type;
    private final ChipField name;

    /** Opens an existing marker (custom pins are editable, automatic points are read-only). */
    public PinPanel(WorldMapScreen screen, Marker marker) {
        this(screen, marker, marker.dimension, marker.x, marker.y, marker.z);
    }

    /** Creates a new pin at the given block. */
    public PinPanel(WorldMapScreen screen, ResourceKey<Level> dimension, int x, int y, int z) {
        this(screen, null, dimension, x, y, z);
    }

    private PinPanel(WorldMapScreen screen, Marker editing, ResourceKey<Level> dimension, int x, int y, int z) {
        super(screen);
        this.editing = editing;
        this.dimension = dimension;
        this.px = x;
        this.py = y;
        this.pz = z;
        this.type = editing != null && editing.type.custom ? editing.type : MarkerType.DEMON; // Genshin pre-selects the first type
        name = new ChipField(ChipField.Style.LIGHT, Icon.PENCIL, Component.translatable("teyvatcartographia.pin.name"),
                Component.translatable("teyvatcartographia.pin.name_hint"), 48);
        name.setValue(editing != null ? editing.name : defaultName());
    }

    /** Read-only view: automatic points. */
    private boolean readOnly() {
        return editing != null && editing.automatic;
    }

    private static String defaultName() {
        return Component.translatable("teyvatcartographia.marker.default_name", MarkerStore.count() + 1).getString();
    }

    @Override
    protected boolean dark() {
        return false;
    }

    @Override
    protected Component title() {
        // Like the automatic points: the header shows the pin's (current) name and its selected type icon.
        if (readOnly()) return Component.literal(editing.name);
        String n = name.value().trim();
        return Component.literal(n.isEmpty() ? (editing != null ? editing.name : defaultName()) : n);
    }

    @Override
    protected Icon titleIcon() {
        return readOnly() ? editing.type.icon : type.icon;
    }

    // ------------------------------------------------------------------ actions available

    /** Bottom-up stack of action buttons. */
    private List<Action> actions() {
        List<Action> a = new ArrayList<>(2);
        if (!readOnly()) a.add(Action.CONFIRM);
        if (editing != null) a.add(Action.TRACK); // a new pin can only be tracked once it exists
        return a;
    }

    private float actionY(int indexFromBottom) {
        return bottomActionY() - indexFromBottom * (Grid.ACTION_H + Grid.GAP);
    }

    // ------------------------------------------------------------------ layout (all on the grid)

    private float nameY() {
        return contentY();
    }

    /** The name chip is not shown for automatic points (their name is the title). */
    private float coordsY() {
        return readOnly() ? contentY() : nameY() + Grid.CHIP_H + Grid.GAP;
    }

    private float gridY() {
        return coordsY() + Grid.CHIP_H + Grid.PAD;
    }

    private List<MarkerType.Category> sections() {
        return SECTIONS;
    }

    private List<MarkerType> types(int section) {
        return SECTION_TYPES.get(sections().get(section));
    }

    /** The total bar: the pins against their limit. */
    private Component totalText() {
        return Component.translatable("teyvatcartographia.pin.total", MarkerStore.count(), MarkerStore.MAX_MARKERS);
    }

    /** Room for a new pin. */
    private boolean room() {
        return MarkerStore.count() < MarkerStore.MAX_MARKERS;
    }

    private int columns() {
        return Math.max(3, (int) ((contentW() + Grid.GAP) / CELL));
    }

    /** Grid is centred horizontally in the content area. */
    private float gridX() {
        int cols = columns();
        return contentX() + (contentW() - (cols * CELL - Grid.GAP)) / 2f;
    }

    /** Top of section {@code k} (its header line), scrolled; sections are stacked with PAD between them. */
    private float sectionY(int k) {
        float y = gridY() - gridScroll;
        int cols = columns();
        for (int j = 0; j < k; j++) {
            int rows = (types(j).size() + cols - 1) / cols;
            y += SECTION_HEAD + rows * CELL - Grid.GAP + Grid.PAD;
        }
        return y;
    }

    private float slotX(int i) {
        return gridX() + (i % columns()) * CELL;
    }

    private float slotY(int k, int i) {
        return sectionY(k) + SECTION_HEAD + (i / columns()) * CELL;
    }

    private float totalY() {
        return actionY(actions().size() - 1) - Grid.GAP - Grid.CHIP_H; // same gap as between the action buttons
    }

    private float trashX() {
        return contentX() + contentW() - Grid.TOOL;
    }

    /** Height of the type grid (all visible sections, headers included). */
    private float gridHeight() {
        int last = sections().size() - 1, cols = columns();
        int rows = (types(last).size() + cols - 1) / cols;
        return sectionY(last) + gridScroll - gridY() + SECTION_HEAD + rows * CELL - Grid.GAP;
    }

    // ------------------------------------------------------------------ grid scrolling (short screens, B20)

    /**
     * How far the type grid is scrolled down (as drawn this frame; eased, without steps, like the other lists); whether
     * it was drawn yet (its first scroll shows the selected type).
     */
    private final SmoothScroll gridScroller = new SmoothScroll();
    private float gridScroll;
    private boolean gridShown;

    /** Bottom of the room for the type grid: above the total bar. */
    private float gridViewBottom() {
        return totalY() - Grid.PAD;
    }

    /** On short screens (large GUI scale, small window) the grid does not fit above the total bar and scrolls. */
    private float maxGridScroll() {
        return Math.max(0, gridHeight() - (gridViewBottom() - gridY()));
    }

    private boolean inGridView(double my) {
        return my >= gridY() && my < gridViewBottom();
    }

    private static final float BAR_W = 3;

    @Override
    protected void renderBody(GuiGraphics g, int mx, int my, float bx, float by, float bw) {
        hoveredType = null;
        int maxText = (int) (bw - Grid.CHIP_H - Grid.GAP);
        boolean readOnly = readOnly();
        if (!readOnly) {
            name.render(g, bx, nameY(), bw, mx, my);
        }

        // Location chip
        float cy = coordsY();
        Ui.chip(g, bx, cy, bw, Grid.CHIP_H, readOnly ? Theme.PANEL_LIGHT_CHIP : Theme.PANEL_LIGHT_CHIP_EDIT);
        Draw.icon(g, Icon.LOCATE, bx + Grid.CHIP_H / 2f, cy + Grid.CHIP_H / 2f, Grid.ICON, Theme.CHIP_ICON);
        // Too long with the dimension (always the one on the map): the coordinates alone.
        String coords = DrawText.fitFirst(maxText,
                Component.translatable("teyvatcartographia.pin.coords", px, pz, WorldMapScreen.dimensionName(dimension)).getString(),
                Component.translatable("teyvatcartographia.pin.coords_short", px, pz).getString());
        DrawText.text(g, coords,bx + Grid.CHIP_H, DrawText.textY(cy, Grid.CHIP_H), Theme.TEXT_DARK, false);

        if (!readOnly) {
            // Type grid by section (header line, then tiles): the same tiles as in the pin list. Where it does not fit
            // above the total bar it scrolls, clipped to its room, with a thin bar in the panel's right padding.
            float max = maxGridScroll();
            if (!gridShown) {
                // Opened scrolled just enough to show the selected type (an interactive one is in the last section).
                gridShown = true;
                for (int k = 0; k < sections().size(); k++) {
                    int i = types(k).indexOf(type);
                    if (i >= 0) gridScroller.jump(slotY(k, i) + Grid.SLOT - gridViewBottom());
                }
            }
            gridScroll = gridScroller.update(max);
            float viewTop = gridY(), viewBottom = gridViewBottom();
            if (max > 0) g.enableScissor((int) Math.floor(x), (int) viewTop, (int) Math.ceil(x + w), (int) viewBottom);
            boolean hoverGrid = max == 0 || inGridView(my);
            List<MarkerType.Category> sections = sections();
            for (int k = 0; k < sections.size(); k++) {
                Component head = Component.translatable(sections.get(k).langKey());
                DrawText.room(head, bx + bw - gridX());
                DrawText.text(g, head, gridX(), sectionY(k), Theme.TEXT_DARK_SOFT, false);
                List<MarkerType> types = types(k);
                for (int i = 0; i < types.size(); i++) {
                    MarkerType t = types.get(i);
                    float sx = slotX(i), sy = slotY(k, i), s = Grid.SLOT;
                    boolean hover = hoverGrid && Draw.inRect(mx, my, sx, sy, s, s);
                    if (hover) {
                        hoveredType = t;
                        Cursors.want(Cursors.Shape.HAND);
                    }
                    MarkerRenderer.badge(g, t.icon, sx + s / 2f, sy + s / 2f, s, t == type, hover, 1f);
                }
            }
            if (max > 0) {
                g.disableScissor();
                float barX = x + w - Grid.PAD + 2, h = viewBottom - viewTop; // 2 units clear of the tiles
                float th = Math.max(8, h * h / gridHeight());
                Draw.rect(g, barX, viewTop, barX + BAR_W, viewBottom, Theme.PANEL_LIGHT_FIELD);
                float thumbY = viewTop + (h - th) * gridScroll / max;
                Draw.rect(g, barX, thumbY, barX + BAR_W, thumbY + th, Theme.TEXT_DARK);
            }
            // Total pins bar (delete button when editing)
            float ty = totalY();
            Ui.chip(g, bx, ty, bw, Grid.CHIP_H, Theme.PANEL_LIGHT_FIELD);
            float inset = Theme.Style.squareUi ? Grid.PAD : Grid.CHIP_H / 2f; // text clear of the rounded cap
            int textW = (int) (bw - inset - (editing != null ? Grid.TOOL + Grid.GAP : 0));
            DrawText.text(g, DrawText.fit(totalText().getString(), textW), bx + inset, DrawText.textY(ty, Grid.CHIP_H), Theme.PANEL_LIGHT_BAR, false);
            if (editing != null) Ui.toolButton(g, trashX(), ty, Icon.TRASH, mx, my);
        }

        // Action buttons, bottom-up
        List<Action> actions = actions();
        for (int i = 0; i < actions.size(); i++) {
            float ay = actionY(i);
            switch (actions.get(i)) {
                case CONFIRM -> Ui.actionButton(g, bx, ay, bw, Component.translatable("teyvatcartographia.pin.confirm"), Icon.CHECK,
                        editing != null || room(), mx, my);
                case TRACK -> Ui.actionButton(g, bx, ay, bw,
                        Component.translatable(Navigation.isTracking(editing) ? "teyvatcartographia.pin.untrack" : "teyvatcartographia.pin.track"), Icon.LOCATE, true, mx, my);
            }
        }
        // The name of the type under the mouse in the grid.
        if (hoveredType != null)
            Ui.tooltip(g, Component.translatable(hoveredType.langKey()).getString(), null, mx, my, screen.width, screen.height);
    }

    private void delete() {
        MarkerStore.remove(editing);
        screen.closePanel();
    }

    /** The type tile under the mouse on this frame, for its name's tooltip; null for none. */
    private MarkerType hoveredType;

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (closeClicked(mx, my)) return true;
        if (!contains(mx, my)) {
            name.blur();
            return false;
        }
        float bx = contentX(), bw = contentW();
        List<Action> actions = actions();
        for (int i = 0; i < actions.size(); i++) {
            if (Ui.actionHit(bx, actionY(i), bw, mx, my)) {
                run(actions.get(i));
                return true;
            }
        }
        if (readOnly()) return true;
        if (name.click(mx, my, button)) return true;
        for (int k = 0; k < sections().size() && inGridView(my); k++) {
            List<MarkerType> types = types(k);
            for (int i = 0; i < types.size(); i++) {
                if (Draw.inRect(mx, my, slotX(i), slotY(k, i), Grid.SLOT, Grid.SLOT)) {
                    type = types.get(i);
                    return true;
                }
            }
        }
        if (editing != null && Draw.inRect(mx, my, trashX(), totalY(), Grid.TOOL, Grid.TOOL)) {
            delete();
            return true;
        }
        return true;
    }

    private void run(Action a) {
        switch (a) {
            case CONFIRM -> confirm();
            case TRACK -> {
                if (Navigation.isTracking(editing)) Navigation.clear();
                else Navigation.track(editing);
            }
        }
    }

    private void confirm() {
        String n = name.value().trim();
        if (n.isEmpty()) n = editing != null ? editing.name : defaultName();
        if (editing != null) {
            MarkerStore.update(editing, n, type);
            screen.closePanel();
        } else {
            Marker m = Marker.custom(n, type, dimension, px, py, pz);
            m.band = screen.viewedBand(); // a pin made on a cave layer belongs to it
            if (MarkerStore.add(m)) screen.closePanel();
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        if (!contains(mx, my)) return false;
        // Half a tile per wheel step; only the type grid scrolls, and only where it does not fit.
        if (!readOnly()) gridScroller.wheel(amount, CELL / 2f);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        return name.keyPressed(key, scan, mods, null);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        return name.charTyped(c, mods);
    }

    @Override
    public boolean capturesKeyboard() {
        return name.focused();
    }
}
