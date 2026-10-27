package net.tapacywka.teyvatcartographia.gui.panel;

import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceKey;
import net.tapacywka.teyvatcartographia.compat.ModCompat;
import net.tapacywka.teyvatcartographia.gui.ChipField;
import net.tapacywka.teyvatcartographia.gui.Cursors;
import net.tapacywka.teyvatcartographia.gui.FileDialogs;
import net.tapacywka.teyvatcartographia.gui.MapSearch;
import net.tapacywka.teyvatcartographia.gui.PinTransfer;
import net.tapacywka.teyvatcartographia.gui.SmoothScroll;
import net.tapacywka.teyvatcartographia.gui.Ui;
import net.tapacywka.teyvatcartographia.gui.Ui.Grid;
import net.tapacywka.teyvatcartographia.gui.WorldMapScreen;
import net.tapacywka.teyvatcartographia.map.data.BiomeIndex;
import net.tapacywka.teyvatcartographia.marker.AutoMarkers;
import net.tapacywka.teyvatcartographia.marker.Marker;
import net.tapacywka.teyvatcartographia.marker.MarkerStore;
import net.tapacywka.teyvatcartographia.nav.Navigation;
import net.tapacywka.teyvatcartographia.render.DistanceText;
import net.tapacywka.teyvatcartographia.render.Draw;
import net.tapacywka.teyvatcartographia.render.DrawText;
import net.tapacywka.teyvatcartographia.render.EdgeFade;
import net.tapacywka.teyvatcartographia.render.Icon;
import net.tapacywka.teyvatcartographia.render.MarkerRenderer;
import net.tapacywka.teyvatcartographia.render.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Dark list of saved markers in the viewed dimension; click to fly the map to a marker. Above the list a search
 * field ({@link MapSearch}): while it holds text, the list shows the matching pins of every dimension and the matching
 * explored biomes under two small headings; a click on a biome flies to its nearest place, each further click to the
 * next one. Rows fill a block centred vertically in the panel (shorter lists start at the block's top); longer lists
 * scroll by rows with the mouse wheel and show a thin scroll bar, like the pick list on the map.
 * At the bottom: Export and Import ({@link PinTransfer}); with Xaero's Minimap in the game Import opens a choice between
 * our file and Xaero's Minimap above the buttons (it stays open after a pick), without it our file at once; the outcome
 * is shown for a while in a popup left of the panel, pointing at the button that was used.
 */
public final class MarkerListPanel extends SidePanel {
    /** Row: 2 text lines (9 + 2 + 9) with 4 units padding top and bottom = 28 = one tile. */
    private static final float ROW_H = Grid.TILE_H;
    private static final float ROW_STEP = ROW_H + Grid.GAP;
    /** Heading or note line of the search results. */
    private static final float LINE_H = 16;
    private static final float SYMBOL = 16;
    /** Side of a biome's colour swatch in the row's leading square. */
    private static final float SWATCH = 12;
    private static final int MAX_ROWS = 10;
    private static final float BAR_W = 2;

    private enum Kind { PIN, BIOME, HEADING, NOTE }

    /** One line of the list, its texts fitted to the row width (built with the list, not every frame). */
    private record Row(Kind kind, Marker marker, MapSearch.BiomeHit biome, String name, String sub, Component right, int rightColor) {
        float height() {
            return clickable() ? ROW_H : LINE_H;
        }

        boolean clickable() {
            return kind == Kind.PIN || kind == Kind.BIOME;
        }
    }

    /** How far the rows are scrolled, without steps. */
    private final SmoothScroll scroller = new SmoothScroll();
    /** Height of the dissolving edges of the rows, GUI units. */
    private static final float FADE = 10;
    private List<Marker> items = List.of();
    private List<Row> rows = List.of();
    private float rowsWidth = -1;
    private final MapSearch search;
    private final ChipField field;

    public MarkerListPanel(WorldMapScreen screen) {
        super(screen);
        search = screen.search();
        Component hint = Component.translatable("teyvatcartographia.search.hint");
        field = new ChipField(ChipField.Style.DARK, Icon.SEARCH, hint, hint, 32);
        field.setValue(search.query());
        field.setResponder(q -> {
            search.setQuery(q);
            scroller.jump(0);
        });
    }

    /** Puts the cursor into the search field (Ctrl+F on the map). */
    public void focusSearch() {
        field.focus();
    }

    @Override
    protected boolean dark() {
        return true;
    }

    @Override
    protected Component title() {
        return Component.translatable("teyvatcartographia.list.title", MarkerStore.count(), MarkerStore.MAX_MARKERS);
    }

    @Override
    protected Icon titleIcon() {
        return Icon.LIST;
    }

    // The sorted list is rebuilt when pins change, when another dimension is viewed, and twice a second for the
    // distance order, not every frame; the search results when the search reports a change (it checks ten times a
    // second).
    private int listMarkers = Integer.MIN_VALUE, listAuto, listSearch = Integer.MIN_VALUE;
    private ResourceKey<Level> listDim;
    private long listNanos;
    private boolean listSearching;

    /** Returns true if the list was rebuilt. */
    private boolean refresh() {
        long now = System.nanoTime();
        search.update(); // cheap: it checks the results ten times a second
        boolean searching = search.active();
        if (searching) {
            if (listSearching && search.version() == listSearch) return false;
            listSearching = true;
            listSearch = search.version();
            return true;
        }
        ResourceKey<Level> dim = screen.viewedDimension();
        if (!listSearching && MarkerStore.version() == listMarkers && AutoMarkers.version() == listAuto && dim == listDim
                && now - listNanos < 500_000_000L) return false;
        listSearching = false;
        listMarkers = MarkerStore.version();
        listAuto = AutoMarkers.version();
        listDim = dim;
        listNanos = now;
        LocalPlayer p = Minecraft.getInstance().player;
        List<Marker> list = new ArrayList<>(screen.visibleMarkers(true));
        if (p != null) list.sort(Comparator.comparingDouble(m -> Navigation.distance(p, m.centerX(), m.y, m.centerZ())));
        items = list;
        return true;
    }

    /**
     * Builds the rows for the panel width {@code bw}: first which rows there are (their heights follow from that), so
     * the texts are fitted once, to the width that is left beside the scroll bar if the list scrolls.
     */
    private void buildRows(float bw) {
        List<Object> plan = new ArrayList<>(); // a Marker, a BiomeHit, or the Kind and key of a heading or note
        if (!search.active()) {
            plan.addAll(items);
        } else {
            if (!search.pins().isEmpty()) {
                plan.add(new Line(Kind.HEADING, "teyvatcartographia.search.pins"));
                plan.addAll(search.pins());
            }
            if (!search.biomes().isEmpty() || search.searchingBiomes()) plan.add(new Line(Kind.HEADING, "teyvatcartographia.search.biomes"));
            plan.addAll(search.biomes());
            if (search.searchingBiomes()) plan.add(new Line(Kind.NOTE, "teyvatcartographia.search.reading"));
            if (plan.isEmpty()) plan.add(new Line(Kind.NOTE, "teyvatcartographia.search.none"));
        }
        float total = -Grid.GAP;
        for (Object o : plan) total += (o instanceof Line ? LINE_H : ROW_H) + Grid.GAP;
        float rw = total > blockHeight() + 0.01f ? bw - BAR_W - Grid.GAP : bw;
        List<Row> out = new ArrayList<>(plan.size());
        boolean everywhere = search.active();
        for (Object o : plan) {
            if (o instanceof Marker m) out.add(pinRow(m, rw, everywhere));
            else if (o instanceof MapSearch.BiomeHit h) out.add(biomeRow(h, rw));
            else if (o instanceof Line l) out.add(line(l.kind, l.key, rw));
        }
        rows = out;
        rowsWidth = rw;
    }

    /** A heading or note of the search results. */
    private record Line(Kind kind, String key) {}

    private static Row line(Kind kind, String key, float rw) {
        return new Row(kind, null, null, DrawText.fit(Component.translatable(key).getString(), (int) rw), null, null, 0);
    }

    /**
     * A row's texts: the name over the whole first line; on the second line the first of {@code subs} that fits
     * (longest first) and, right-aligned, the distance in the player's dimension, else the other dimension's name.
     * {@code y} is {@link Integer#MIN_VALUE} for a place without a height (a horizontal distance).
     */
    private static Row row(Marker m, MapSearch.BiomeHit h, String name, ResourceKey<Level> dim, double x, int y, double z,
                           boolean showDimension, float rw, String... subs) {
        LocalPlayer p = Minecraft.getInstance().player;
        Component right = null;
        int color = Theme.GOLD;
        if (p != null && p.level().dimension() == dim) {
            right = DistanceText.blocks(Math.round(Navigation.distance(p, x, y, z)));
        } else if (showDimension) {
            right = WorldMapScreen.dimensionName(dim);
            color = Theme.TEXT_MUTED;
        }
        int rightW = right == null ? 0 : DrawText.width(right) + Grid.GAP;
        // The right-hand text must leave the second line a few letters (about 4).
        if (right != null) DrawText.room(right, rw - ROW_H - Grid.PAD - Grid.GAP - 24);
        int lineW = (int) (rw - ROW_H - Grid.PAD);
        return new Row(m != null ? Kind.PIN : Kind.BIOME, m, h, DrawText.fit(name, lineW), DrawText.fitFirst(lineW - rightW, subs), right, color);
    }

    private static Row pinRow(Marker m, float rw, boolean showDimension) {
        String coords = m.x + ", " + m.z;
        return row(m, null, m.name, m.dimension, m.centerX(), m.y, m.centerZ(), showDimension, rw, coords);
    }

    private static Row biomeRow(MapSearch.BiomeHit h, float rw) {
        BiomeIndex.Place p = h.shown();
        // On a narrow panel the counter alone ("2 of 5").
        return row(null, h, h.name().getString(), h.dimension, p.x(), Integer.MIN_VALUE, p.z(), true, rw,
                Component.translatable("teyvatcartographia.search.place", h.shownNumber(), h.places.size()).getString(),
                Component.translatable("teyvatcartographia.search.place_short", h.shownNumber(), h.places.size()).getString());
    }

    private float listBottom() {
        return actionsTop() - Grid.PAD;
    }

    // ------------------------------------------------------------------ search field

    private float fieldY() {
        return contentY();
    }

    private float listAreaTop() {
        return fieldY() + Grid.CHIP_H + Grid.PAD;
    }

    private void renderField(GuiGraphics g, int mx, int my, float bx, float bw) {
        field.render(g, bx, fieldY(), bw, mx, my);
    }

    // ------------------------------------------------------------------ export and import

    /** True while the import choice (our file or Xaero's Minimap) is open above the button row. */
    private boolean importMenu;

    /** The row of the Export and Import buttons at the bottom. */
    private float transferRowY() {
        return bottomActionY();
    }

    private float halfWidth() {
        return (contentW() - Grid.GAP) / 2f;
    }

    /** Top of the n-th import choice above the button row (0 = the lowest: our own file, 1: Xaero's Minimap). */
    private float choiceY(int n) {
        return transferRowY() - (n + 1) * (Grid.ACTION_H + Grid.GAP);
    }

    private float actionsTop() {
        return importMenu ? choiceY(1) : transferRowY();
    }


    private void renderTransfer(GuiGraphics g, int mx, int my, float bx, float bw) {
        boolean free = !FileDialogs.isOpen();
        float row = transferRowY(), half = halfWidth();
        Ui.actionButton(g, bx, row, half, Component.translatable("teyvatcartographia.transfer.export"), Icon.EXPORT, free, mx, my);
        Ui.actionButton(g, bx + half + Grid.GAP, row, half, Component.translatable("teyvatcartographia.transfer.import"), Icon.IMPORT, free, mx, my);
        if (importMenu) {
            Ui.actionButton(g, bx, choiceY(0), bw, Component.translatable("teyvatcartographia.transfer.import.file"), null, free, mx, my);
            Ui.actionButton(g, bx, choiceY(1), bw, Component.translatable("teyvatcartographia.transfer.import.xaero"), null, free, mx, my);
        }
        // The outcome of the last export or import: a popup over the map left of the panel, pointing at its button
        // (at Import when the choice was closed since).
        PinTransfer.Outcome o = PinTransfer.message();
        if (o != null) Ui.popupLeftOf(g, o.title(), o.lines(), x - 1, sourceY(o.source()) + Grid.ACTION_H / 2f, y + h - Grid.MARGIN);
    }

    private float sourceY(PinTransfer.Source source) {
        return switch (source) {
            case EXPORT -> transferRowY();
            case FILE -> importMenu ? choiceY(0) : transferRowY();
            case XAERO -> importMenu ? choiceY(1) : transferRowY();
        };
    }

    /** Returns true if a transfer button took the click. */
    private boolean transferClicked(double mx, double my) {
        if (FileDialogs.isOpen()) return false;
        float bx = contentX(), bw = contentW(), row = transferRowY(), half = halfWidth();
        if (Ui.actionHit(bx, row, half, mx, my)) {
            importMenu = false;
            PinTransfer.export();
            return true;
        }
        if (Ui.actionHit(bx + half + Grid.GAP, row, half, mx, my)) {
            // The choice of a source only with Xaero's Minimap in the game (or its data); else our own file at once.
            if (ModCompat.xaero()) importMenu = !importMenu;
            else PinTransfer.importFile();
            return true;
        }
        // The choice stays open after a pick, so the outcome's popup can point at the button that was used.
        if (importMenu && Ui.actionHit(bx, choiceY(0), bw, mx, my)) {
            PinTransfer.importFile();
            return true;
        }
        if (importMenu && Ui.actionHit(bx, choiceY(1), bw, mx, my)) {
            PinTransfer.importXaero();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ list layout

    /** Full rows that fit at once (at most {@link #MAX_ROWS}): the height of the block. */
    private int capacity() {
        return Math.max(1, Math.min(MAX_ROWS, (int) ((listBottom() - listAreaTop() + Grid.GAP) / ROW_STEP)));
    }

    private float blockHeight() {
        return capacity() * ROW_STEP - Grid.GAP;
    }

    /** Top of the row block: a block of {@link #capacity()} rows centred in the area under the search field. */
    private float listTop() {
        return Draw.snap(listAreaTop() + (listBottom() - listAreaTop() - blockHeight()) / 2f);
    }

    /** Height of all the rows with the gaps between them. */
    private float total() {
        float total = -Grid.GAP;
        for (Row r : rows) total += r.height() + Grid.GAP;
        return Math.max(0, total);
    }

    /** The furthest scroll: the last row on the block's bottom edge. */
    private float maxScroll() {
        return Math.max(0, total() - blockHeight());
    }

    private boolean scrollable() {
        return maxScroll() > 0.01f;
    }

    /** Row width: narrower by the scroll bar when the list scrolls. */
    private float rowWidth(float bw) {
        return scrollable() ? bw - BAR_W - Grid.GAP : bw;
    }

    @Override
    protected void renderBody(GuiGraphics g, int mx, int my, float bx, float by, float bw) {
        boolean rebuilt = refresh();
        renderField(g, mx, my, bx, bw);
        renderTransfer(g, mx, my, bx, bw);
        // Again when the list changed, or when the room for it did (the import choice, the window size).
        if (rebuilt || rowWidth(bw) != rowsWidth) buildRows(bw);
        float rw = rowsWidth;
        float s = scroller.update(maxScroll());
        if (rows.isEmpty()) {
            DrawText.text(g, Component.translatable("teyvatcartographia.list.empty"), bx, DrawText.textY(listTop(), Grid.ROW), Theme.TEXT_MUTED, false);
            return;
        }
        float top = listTop(), h = blockHeight(), ry = top - s;
        // Rows cut by the block's edges too, dissolving there like the settings list's own.
        EdgeFade.capture(g);
        g.enableScissor((int) bx, (int) top, (int) Math.ceil(bx + bw), (int) Math.ceil(top + h));
        int hoverY = my >= top && my < top + h ? my : -10_000;
        for (Row r : rows) {
            if (ry + r.height() > top && ry < top + h) renderRow(g, r, bx, ry, rw, mx, hoverY);
            ry += r.height() + Grid.GAP;
        }
        g.disableScissor();
        EdgeFade.draw(g, bx, bx + rw, top, top + h, FADE, Math.min(1, s / FADE), Math.min(1, (scroller.max() - s) / FADE));
        EdgeFade.release();
        if (scrollable()) {
            // Track over the block of rows, thumb over the visible part (as in the map's pick list).
            float barX = bx + bw - BAR_W;
            Draw.rect(g, barX, top, barX + BAR_W, top + h, Theme.SCROLL_TRACK);
            float th = Math.max(8, h * h / total());
            float ty = top + (h - th) * scroller.fraction();
            Draw.rect(g, barX, ty, barX + BAR_W, ty + th, Theme.SCROLL_THUMB);
        }
    }

    private void renderRow(GuiGraphics g, Row r, float bx, float ry, float rw, int mx, int my) {
        if (!r.clickable()) {
            // Headings and notes: one muted line, set on the row grid's baseline.
            DrawText.text(g, r.name(), bx + (r.kind() == Kind.NOTE ? Grid.GAP : 0), DrawText.textY(ry, LINE_H), Theme.TEXT_MUTED, false);
            return;
        }
        boolean hover = Draw.inRect(mx, my, bx, ry, rw, ROW_H);
        if (hover) Cursors.want(Cursors.Shape.HAND);
        Draw.rect(g, bx, ry, bx + rw, ry + ROW_H, hover ? Theme.PANEL_DARK_TILE_HOVER : Theme.PANEL_DARK_TILE);
        float cx = bx + ROW_H / 2f, cy = ry + ROW_H / 2f;
        if (r.kind() == Kind.PIN) {
            Marker m = r.marker();
            if (Navigation.isTracking(m)) Draw.rect(g, bx, ry, bx + 3, ry + ROW_H, Theme.worldMarkerPulse());
            // Symbol centred in the row's leading 28 x 28 square; text starts right after it.
            MarkerRenderer.drawCentered(g, m.type, cx, cy, SYMBOL, 1f);
        } else {
            // The biome's colour on the map: a square in the rectangular style, a disc in the rounded one.
            int c = r.biome().color == 0 ? Theme.TEXT_MUTED : r.biome().color;
            if (Theme.Style.squareUi) Draw.rect(g, cx - SWATCH / 2f, cy - SWATCH / 2f, cx + SWATCH / 2f, cy + SWATCH / 2f, c);
            else Draw.circle(g, cx, cy, SWATCH / 2f, c);
        }
        float tx = bx + ROW_H;
        DrawText.text(g, r.name(), tx, ry + 4, Theme.CREAM, false);
        DrawText.text(g, r.sub(), tx, ry + 4 + DrawText.LINE + 2, Theme.TEXT_MUTED, false);
        if (r.right() != null) DrawText.textRight(g, r.right(), bx + rw - Grid.PAD, ry + 4 + DrawText.LINE + 2, r.rightColor(), false);
    }

    /** The row under the mouse, or null. */
    private Row rowAt(double mx, double my) {
        float rw = rowWidth(contentW()), top = listTop(), ry = top - scroller.pos();
        if (my < top || my >= top + blockHeight()) return null;
        for (Row r : rows) {
            if (r.clickable() && Draw.inRect(mx, my, contentX(), ry, rw, ROW_H)) return r;
            ry += r.height() + Grid.GAP;
        }
        return null;
    }

    private void open(Row r) {
        if (r.kind() == Kind.PIN) {
            screen.showMarker(r.marker());
        } else {
            BiomeIndex.Place p = search.next(r.biome());
            screen.showPlace(r.biome().dimension, p.x(), p.z());
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (closeClicked(mx, my)) return true;
        if (field.click(mx, my, button)) return true;
        if (transferClicked(mx, my)) return true;
        Row r = rowAt(mx, my);
        if (r != null) {
            open(r);
            return true;
        }
        return contains(mx, my);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        if (!contains(mx, my)) return false;
        scroller.wheel(amount, ROW_STEP);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        return field.keyPressed(key, scan, mods, this::openFirst); // Enter opens the first result
    }

    private void openFirst() {
        for (Row r : rows) {
            if (r.clickable()) {
                open(r);
                return;
            }
        }
    }

    @Override
    public boolean charTyped(char c, int mods) {
        return field.charTyped(c, mods);
    }

    @Override
    public boolean capturesKeyboard() {
        return field.focused();
    }
}
