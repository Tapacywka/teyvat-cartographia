package net.tapacywka.teyvatcartographia.gui;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every input of the world map's picture redraws it, and a remembered key equals the one it came from. */
class MapPictureKeyTest {
    private static Object changed(Field f) {
        Class<?> t = f.getType();
        if (t == int.class) return 7;
        if (t == float.class) return 0.5f;
        if (t == double.class) return 0.25;
        if (t == Object.class) return new Object();
        throw new AssertionError("a key field of a type the test cannot change: " + f);
    }

    private static Field[] fields() {
        Field[] all = MapPicture.Key.class.getDeclaredFields();
        int n = 0;
        for (Field f : all) if (!Modifier.isStatic(f.getModifiers())) all[n++] = f;
        Field[] out = java.util.Arrays.copyOf(all, n);
        for (Field f : out) f.setAccessible(true);
        return out;
    }

    @Test
    void everyFieldCounts() throws IllegalAccessException {
        Field[] fields = fields();
        assertEquals(15, fields.length, "a field added to the key: check differs and remember take it");
        for (Field f : fields) {
            MapPicture.Key drawn = new MapPicture.Key(), now = new MapPicture.Key();
            assertFalse(drawn.differs(now), "fresh keys are equal");
            f.set(now, changed(f));
            assertTrue(drawn.differs(now), f.getName() + " changed but the picture would not redraw");
            drawn.remember(now);
            assertFalse(drawn.differs(now), f.getName() + " is not remembered, so the picture would redraw every frame");
        }
    }

    @Test
    void equalButNotSameObjectDiffers() {
        MapPicture.Key a = new MapPicture.Key(), b = new MapPicture.Key();
        a.store = new String("x");
        b.store = new String("x");
        assertTrue(a.differs(b), "stores are compared by identity");
    }
}
