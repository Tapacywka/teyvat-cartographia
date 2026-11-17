package net.tapacywka.teyvatcartographia.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Windows file dialogs refuse straight quotes in a title ("Xaero's" showed "INVALID TITLE WITH QUOTES"). */
class FileDialogsTest {
    @Test
    void straightQuotesBecomeTypographic() {
        String title = FileDialogs.text("Import from Xaero's Minimap \"waypoints\"");
        assertFalse(title.contains("'") || title.contains("\""), title);
        assertEquals("Import from Xaero’s Minimap ”waypoints”", title);
    }

    @Test
    void otherTextStaysAsItIs() {
        assertEquals("Импорт меток", FileDialogs.text("Импорт меток"));
    }
}
