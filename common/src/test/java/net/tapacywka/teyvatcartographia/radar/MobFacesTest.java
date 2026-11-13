package net.tapacywka.teyvatcartographia.radar;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobFacesTest {
    @Test
    void everyPartLiesInItsTextureAndOnItsFace() {
        for (Map.Entry<String, MobFaces.Face> e : MobFaces.all().entrySet()) {
            MobFaces.Face f = e.getValue();
            int[] p = f.parts();
            assertTrue(p.length > 0 && p.length % MobFaces.PART == 0, e.getKey());
            assertEquals(0, p[0], e.getKey() + ": the front is of the first texture");
            int w = 0, h = 0;
            for (int k = 0; k < p.length; k += MobFaces.PART) {
                String part = e.getKey() + " part " + k / MobFaces.PART;
                int t = p[k];
                assertTrue(t >= 0 && t < f.textures().length, part + " names a texture of the face");
                assertTrue(p[k + 1] >= 0 && p[k + 2] >= 0 && p[k + 3] > 0 && p[k + 4] > 0, part);
                assertTrue(p[k + 1] + p[k + 3] <= f.texW()[t] && p[k + 2] + p[k + 4] <= f.texH()[t], part + " lies in its texture");
                assertTrue(p[k + 5] >= 0 && p[k + 6] >= 0, part + " lies on the face");
                // where it lies is in halves of a pixel
                w = Math.max(w, (p[k + 5] + 2 * p[k + 3] + 1) / 2);
                h = Math.max(h, (p[k + 6] + 2 * p[k + 4] + 1) / 2);
            }
            assertEquals(w, f.w(), e.getKey() + " width holds every part");
            assertEquals(h, f.h(), e.getKey() + " height holds every part");
        }
    }

    /**
     * The textures are the game's own (on the test's classpath): each there, of the size the table says or a whole
     * number of times finer (the ghasts' since 26.1: models of 64 pixels, textures of 128).
     */
    @Test
    void everyTextureIsTheGamesOfItsSize() throws Exception {
        for (Map.Entry<String, MobFaces.Face> e : MobFaces.all().entrySet()) {
            MobFaces.Face f = e.getValue();
            for (int t = 0; t < f.textures().length; t++) {
                String path = "/assets/minecraft/" + (f.textures()[t].startsWith(MobFaces.LAYER)
                        ? MobFaces.layerPath(f.textures()[0], f.textures()[t]) : f.textures()[t]);
                try (InputStream in = MobFacesTest.class.getResourceAsStream(path)) {
                    assertNotNull(in, e.getKey() + ": " + path);
                    BufferedImage image = ImageIO.read(in);
                    int times = image.getWidth() / f.texW()[t];
                    assertTrue(times >= 1 && image.getWidth() == times * f.texW()[t], e.getKey() + " " + path + " width " + image.getWidth());
                    assertEquals(times * f.texH()[t], image.getHeight(), e.getKey() + " " + path + " height");
                }
            }
        }
    }

    @Test
    void mobsWithoutAFaceStayDots() {
        assertNull(MobFaces.of("minecraft:slime"));
        assertNull(MobFaces.of("minecraft:squid"));
        assertNull(MobFaces.of("the_aether:aerbunny"));
        assertNotNull(MobFaces.of("minecraft:zombie"));
    }

    /** The user's look of 13.11.2026: the snow golem wears its pumpkin (a block, never its renderer's texture). */
    @Test
    void theSnowGolemWearsItsPumpkin() {
        MobFaces.Face golem = MobFaces.of("minecraft:snow_golem");
        assertTrue(golem.fixed());
        assertEquals("textures/block/carved_pumpkin.png", golem.textures()[0]);
        assertFalse(MobFaces.of("minecraft:snow_golem_sheared").fixed());
        // The bogged's moss and the enderman's eyes from a second texture; the rabbit's ears above its head (26.1's model).
        assertEquals(2, MobFaces.of("minecraft:bogged").textures().length);
        assertEquals(2, MobFaces.of("minecraft:enderman").textures().length);
        assertEquals(10, MobFaces.of("minecraft:rabbit").h());
    }

    /** The wolf's nose 1.5 pixels in (user report 13.11.2026), on whole screen pixels in a head of the one size. */
    @Test
    void theWolfsNoseLiesInTheMiddle() {
        MobFaces.Face wolf = MobFaces.of("minecraft:wolf");
        assertTrue(wolf.halves());
        int[] p = wolf.parts();
        assertEquals(3, p[MobFaces.PART + 5], "1.5 pixels in, in halves");
        // the world map at GUI 3: 24 pixels for the wolf's 6, the nose 6 in, 12 wide
        assertEquals(6, MobFaces.at(3, 24, wolf));
        assertEquals(18, MobFaces.at(3 + 2 * 3, 24, wolf));
        assertFalse(MobFaces.of("minecraft:cow").halves());
    }

    /** One size for every head (user choice 13.11.2026, variant B): the longer side as an 8-pixel face's on whole pixels. */
    @Test
    void everyHeadIsAsLarge() {
        assertEquals(24, MobFaces.side(8, 3));
        assertEquals(16, MobFaces.side(5.3f, 3));
        assertEquals(32, MobFaces.side(8, 4));
        assertEquals(24, MobFaces.side(5.3f, 4));
        for (String id : new String[]{"minecraft:cow", "minecraft:chicken", "minecraft:wolf", "minecraft:ghast", "minecraft:iron_golem",
                "minecraft:rabbit", "minecraft:copper_golem", "minecraft:happy_ghast"}) {
            MobFaces.Face f = MobFaces.of(id);
            int longer = Math.max(MobFaces.at(2 * f.w(), 24, f), MobFaces.at(2 * f.h(), 24, f));
            assertEquals(24, longer, id);
        }
        // the chicken's face is narrower than tall, the same height as the others
        MobFaces.Face chicken = MobFaces.of("minecraft:chicken");
        assertEquals(16, MobFaces.at(2 * chicken.w(), 24, chicken));
        // the parts lie on whole pixels, from 0 to the side
        MobFaces.Face ghast = MobFaces.of("minecraft:ghast");
        assertEquals(0, MobFaces.at(0, 16, ghast));
        assertEquals(16, MobFaces.at(32, 16, ghast));
    }

    /** The mobs of later games and the warm and cold farm animals by their model; the copper golem's eyes as it oxidizes. */
    @Test
    void newMobsAndVariantsHaveFaces() {
        for (String id : new String[]{"creaking", "copper_golem", "happy_ghast", "parched", "cow/cold", "cow/warm", "pig/cold", "chicken/cold"})
            assertNotNull(MobFaces.of("minecraft:" + id), id);
        // no face for a usual model: the warm pig and chicken keep the usual face with their own texture
        assertNull(MobFaces.of("minecraft:pig/normal"));
        assertNull(MobFaces.of("minecraft:chicken/normal"));
        MobFaces.Face golem = MobFaces.of("minecraft:copper_golem");
        assertEquals("textures/entity/copper_golem/copper_golem_eyes_weathered.png",
                MobFaces.layerPath("textures/entity/copper_golem/copper_golem_weathered.png", golem.textures()[1]));
        assertEquals("textures/entity/copper_golem/copper_golem_eyes.png",
                MobFaces.layerPath("textures/entity/copper_golem/copper_golem.png", golem.textures()[1]));
        assertEquals(13, golem.h());
        assertEquals(6, MobFaces.of("minecraft:chicken/cold").w());
        // no face is another mod's on 26.x
        assertNull(MobFaces.modOf("minecraft:cow/baby"));
        assertFalse(MobFaces.fromMod("minecraft:creaking"));
    }

    /** The babies of the Tiny Takeover (26.1): a baby face for every adult face of a mob with a baby model. */
    @Test
    void babiesHaveFaces() {
        String[] babies = {"cow", "mooshroom", "pig", "chicken", "sheep", "wolf", "cat", "ocelot", "fox", "panda", "polar_bear",
                "rabbit", "villager", "zombie_villager", "zombie", "husk", "drowned", "piglin", "zombified_piglin"};
        for (String id : babies) {
            assertNotNull(MobFaces.of("minecraft:" + id), id + " has an adult face");
            assertNotNull(MobFaces.of("minecraft:" + id + "/" + MobFaces.BABY), id + " has a baby face");
        }
        assertNull(MobFaces.of("minecraft:creeper/baby"));
        assertTrue(MobFaces.of("minecraft:wolf/baby").halves());
        assertTrue(MobFaces.of("minecraft:cat/baby").halves());
        assertEquals(8, MobFaces.of("minecraft:rabbit/baby").h());
    }

    @Test
    void aTexelIsAWholeNumberOfScreenPixels() {
        // Mobs at GUI 3: 5.3 units for the longer side, 2 pixels a texel for a face of 8, one for the ghast's 16.
        assertEquals(2, MobFaces.texel(5.3f, 3, 8));
        assertEquals(1, MobFaces.texel(5.3f, 3, 16));
        assertEquals(3, MobFaces.texel(5.3f, 3, 5));
        // Players at GUI 3 and 4 (7 units, a face of 8).
        assertEquals(3, MobFaces.texel(7, 3, 8));
        assertEquals(4, MobFaces.texel(7, 4, 8));
        assertEquals(1, MobFaces.texel(0.1f, 1, 8));
    }
}
