package net.tapacywka.teyvatcartographia.gui;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Windows file dialogs refuse straight quotes in a title ("Xaero's" showed "INVALID TITLE WITH QUOTES"). */
class FileDialogsTest {
    private static String text(String s) throws ReflectiveOperationException {
        Method m = FileDialogs.class.getDeclaredMethod("text", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, s);
    }

    @Test
    void straightQuotesBecomeTypographic() throws Exception {
        String title = text("Import from Xaero's Minimap \"waypoints\"");
        assertFalse(title.contains("'") || title.contains("\""), title);
        assertEquals("Import from Xaero’s Minimap ”waypoints”", title);
    }

    @Test
    void otherTextStaysAsItIs() throws Exception {
        assertEquals("Импорт меток", text("Импорт меток"));
    }
}
