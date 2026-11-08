package net.tapacywka.teyvatcartographia.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.tapacywka.teyvatcartographia.TeyvatCartographia;
import net.tapacywka.teyvatcartographia.compat.XaeroMapFolders;
import net.tapacywka.teyvatcartographia.gui.Ui.Grid;
import net.tapacywka.teyvatcartographia.map.data.ImportTime;
import net.tapacywka.teyvatcartographia.map.data.MapDataManager;
import net.tapacywka.teyvatcartographia.map.data.XaeroImport;
import net.tapacywka.teyvatcartographia.render.Draw;
import net.tapacywka.teyvatcartographia.render.DrawText;
import net.tapacywka.teyvatcartographia.render.Icon;
import net.tapacywka.teyvatcartographia.render.Theme;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The import of the explored Overworld of Xaero's World Map into this mod's map of it, from any dimension (user request
 * 03.11.2026, opened from Settings > Compatibility > Xaero's Minimap; docs/XAERO_MAP_IMPORT.md): Xaero's folders of the
 * Overworld with a switch each (the current world's on), about how long the import of those chosen takes, Import, a bar
 * and the time left while it runs (user request 08.11.2026, {@link ImportTime}), then what it wrote. The map is closed
 * meanwhile ({@link MapDataManager#pauseForImport}) and opens again with the imported land.
 */
public final class XaeroImportScreen extends Screen {
    private static final int W = 300;
    private static final int ROW = 28;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.systemDefault());
    private static final String K = "teyvatcartographia.xaero_import.";

    private final Screen parent;
    private final List<XaeroMapFolders.Source> sources;
    private final boolean[] chosen;
    private XaeroImport running;
    private Thread thread;
    private boolean finished;

    public XaeroImportScreen(Screen parent) {
        super(Component.translatable(K + "title"));
        this.parent = parent;
        Minecraft mc = Minecraft.getInstance();
        this.sources = mc.level == null ? List.of() : XaeroMapFolders.sources();
        this.chosen = new boolean[sources.size()];
        for (int i = 0; i < chosen.length; i++) chosen[i] = sources.get(i).current();
        if (chosen.length > 0 && !anyChosen()) chosen[0] = true;
    }

    private boolean anyChosen() {
        for (boolean b : chosen) if (b) return true;
        return false;
    }

    private int rows() {
        return Math.max(1, Math.min(sources.size(), 6));
    }

    private float panelH() {
        return Grid.HEADER_H + Grid.PAD + 14 + rows() * ROW + 2 * 12 + Grid.PAD + Grid.ACTION_H + Grid.PAD;
    }

    private float x0() {
        return (width - W) / 2f;
    }

    private float y0() {
        return Math.max(Grid.MARGIN, (height - panelH()) / 2f);
    }

    private float listTop() {
        return y0() + Grid.HEADER_H + Grid.PAD + 14;
    }

    private float buttonsY() {
        return y0() + panelH() - Grid.PAD - Grid.ACTION_H;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        float x = x0(), y = y0(), w = W, h = panelH();
        Ui.panel(g, x, y, w, h, true, title, Icon.IMPORT, mx, my);
        float bx = x + Grid.PAD, bw = w - 2 * Grid.PAD;
        Component dim = Component.translatable(K + "dimension", WorldMapScreen.dimensionName(Level.OVERWORLD));
        DrawText.text(g, DrawText.fit(dim.getString(), (int) bw), bx, y + Grid.HEADER_H + Grid.PAD, Theme.TEXT_MUTED, false);
        float ly = listTop();
        if (sources.isEmpty()) {
            DrawText.text(g, DrawText.fit(Component.translatable(K + "none").getString(), (int) bw), bx, DrawText.textY(ly, ROW), Theme.CREAM, false);
        }
        for (int i = 0; i < Math.min(sources.size(), rows()); i++) {
            XaeroMapFolders.Source s = sources.get(i);
            float ry = ly + i * ROW;
            String name = s.map().isEmpty() ? s.world() : s.world() + " / " + s.map();
            DrawText.text(g, DrawText.fit(name, (int) (bw - Grid.TOGGLE_W - Grid.GAP)), bx, ry + 2, Theme.WHITE, false);
            String sub = Component.translatable(K + "regions", s.regions(), DATE.format(s.newest().toInstant())).getString();
            DrawText.text(g, DrawText.fit(sub, (int) (bw - Grid.TOGGLE_W - Grid.GAP)), bx, ry + 2 + DrawText.LINE + 2, Theme.TEXT_MUTED, false);
            if (running == null) Ui.toggle(g, x + w - Grid.PAD - Grid.TOGGLE_W, ry + (ROW - Grid.TOGGLE_H) / 2f, chosen[i], mx, my);
        }
        float ny = ly + rows() * ROW + 2;
        if (running == null) {
            DrawText.text(g, DrawText.fit(Component.translatable(K + "keeps").getString(), (int) bw), bx, ny, Theme.TEXT_MUTED, false);
            // About how long it takes, for the folders chosen (user request 08.11.2026).
            double seconds = estimate();
            if (seconds > 0) {
                Component line = Component.translatable(K + "estimate", time(seconds));
                DrawText.text(g, DrawText.fit(line.getString(), (int) bw), bx, ny + 12, Theme.CREAM, false);
            }
        } else {
            // Progress: a bar over the regions, the count and about how long is left under it.
            int total = Math.max(1, running.total.get()), done = running.done.get();
            Draw.rect(g, bx, ny, bx + bw, ny + 6, Theme.SCROLL_TRACK);
            Draw.rect(g, bx, ny, bx + bw * done / total, ny + 6, Theme.GOLD);
            Component line = finished
                    ? Component.translatable(K + "done", running.chunks.get(), running.broken.get())
                    : Component.translatable(K + "progress", done, running.total.get(), time(left()));
            DrawText.text(g, DrawText.fit(line.getString(), (int) bw), bx, ny + 10, Theme.CREAM, false);
        }
        float half = (bw - Grid.GAP) / 2f, by = buttonsY();
        if (running == null) {
            Ui.actionButton(g, bx, by, half, Component.translatable(K + "start"), Icon.IMPORT, anyChosen(), mx, my);
            Ui.actionButton(g, bx + half + Grid.GAP, by, half, Component.translatable("gui.back"), null, true, mx, my);
        } else if (!finished) {
            Ui.actionButton(g, bx, by, bw, Component.translatable(K + "stop"), null, true, mx, my);
        } else {
            Ui.actionButton(g, bx, by, bw, Component.translatable("gui.done"), null, true, mx, my);
        }
        DrawText.room(title, w - 2 * Grid.PAD - Grid.ICON_L - Grid.ICON - 3 * Grid.GAP);
    }

    /** About how many seconds the import of the folders chosen takes (0 with none chosen). */
    private double estimate() {
        int regions = 0;
        long bytes = 0;
        for (int i = 0; i < sources.size(); i++) {
            if (!chosen[i]) continue;
            regions += sources.get(i).regions();
            bytes += sources.get(i).bytes();
        }
        return regions == 0 ? 0 : ImportTime.seconds(regions, bytes, XaeroImport.threads(), 1);
    }

    /** The time left as last worked out, and when (renewed once a second, so the number does not flicker). */
    private double left;
    private long leftAt;

    private double left() {
        long now = System.nanoTime();
        if (leftAt == 0 || now - leftAt > 1_000_000_000L) {
            double l = running.secondsLeft();
            left = l < 0 ? estimate() : l; // the regions still being listed: as estimated before
            leftAt = now;
        }
        return left;
    }

    /** "40 s", "2 min", "1 h 5 min": the time rounded as {@link ImportTime#rounded} says. */
    private static Component time(double seconds) {
        int[] t = ImportTime.rounded(seconds);
        if (t[0] > 0) return Component.translatable(K + "hours", t[0], t[1]);
        if (t[1] > 0) return Component.translatable(K + "minutes", t[1]);
        return Component.translatable(K + "seconds", t[2]);
    }

    @Override
    public void tick() {
        if (running != null && !finished && thread != null && !thread.isAlive()) {
            finished = true;
            MapDataManager.get().resumeAfterImport();
            TeyvatCartographia.LOGGER.info("Teyvat Cartographia: {} chunks imported from Xaero's World Map ({} regions, {} broken)",
                    running.chunks.get(), running.done.get(), running.broken.get());
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        float x = x0(), w = W, bx = x + Grid.PAD, bw = w - 2 * Grid.PAD, half = (bw - Grid.GAP) / 2f, by = buttonsY();
        if (running == null) {
            if (Ui.closeHit(x, y0(), w, mx, my)) {
                onClose();
                return true;
            }
            for (int i = 0; i < Math.min(sources.size(), rows()); i++) {
                if (Ui.toggleHit(x + w - Grid.PAD - Grid.TOGGLE_W, listTop() + i * ROW + (ROW - Grid.TOGGLE_H) / 2f, mx, my)) {
                    chosen[i] = !chosen[i];
                    return true;
                }
            }
            if (Ui.actionHit(bx, by, half, mx, my) && anyChosen()) {
                start();
                return true;
            }
            if (Ui.actionHit(bx + half + Grid.GAP, by, half, mx, my)) {
                onClose();
                return true;
            }
        } else if (Ui.actionHit(bx, by, bw, mx, my)) {
            if (finished) onClose();
            else running.cancel.set(true);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private void start() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        List<Path> dirs = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) if (chosen[i]) dirs.add(sources.get(i).dir());
        Path target = MapDataManager.get().dimDir(Level.OVERWORLD);
        MapDataManager.get().pauseForImport();
        running = new XaeroImport(mc.level, dirs, target);
        thread = new Thread(running::run, "Teyvat Cartographia Xaero import");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return running == null || finished;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void removed() {
        // Left while it runs (the game closes, say): stopped, the map opened again.
        if (running != null && !finished) {
            running.cancel.set(true);
            try {
                thread.join(10_000); // it stops after the region it writes
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            MapDataManager.get().resumeAfterImport();
        }
    }
}
