package net.tapacywka.teyvatcartographia.render;

import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A theme's palette for a dimension: its file name gives the dimension, its "blocks" the colours. */
class MapPaletteFileTest {
    @Test
    void theFileNameIsTheDimension() {
        assertEquals(ResourceLocation.fromNamespaceAndPath("aether", "the_aether"), ThemeLoader.dimensionOf("aether/the_aether.json"));
        assertEquals(ResourceLocation.fromNamespaceAndPath("mod", "deep/caves"), ThemeLoader.dimensionOf("mod/deep/caves.json"));
        assertNull(ThemeLoader.dimensionOf("the_aether.json"), "no namespace folder");
        assertNull(ThemeLoader.dimensionOf("aether/notes.txt"));
    }

    @Test
    void goodEntriesAreTakenBadOnesLeftOut() {
        Map<ResourceLocation, Integer> blocks = ThemeLoader.paletteBlocks(JsonParser.parseString("""
                { "blocks": { "aether:holystone": "#FFC6C4BB", "aether:quicksoil": "E0D3A4", "Bad Id!": "#FF000000",
                              "aether:icestone": "not a colour" } }""").getAsJsonObject(), "test");
        assertEquals(2, blocks.size());
        assertEquals(0xFFC6C4BB, blocks.get(ResourceLocation.parse("aether:holystone")));
        assertEquals(0xFFE0D3A4, blocks.get(ResourceLocation.parse("aether:quicksoil")), "#RRGGBB is opaque");
    }
}
