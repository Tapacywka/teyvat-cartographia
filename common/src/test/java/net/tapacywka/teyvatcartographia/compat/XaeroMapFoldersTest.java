package net.tapacywka.teyvatcartographia.compat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The names of Xaero's World Map folders as the import shows them. */
class XaeroMapFoldersTest {
    @Test
    void aServerShowsItsAddressAWorldItsName() {
        assertEquals("play.example.org", XaeroMapFolders.shownWorld("Multiplayer_play.example.org"));
        assertEquals("203.0.113.7", XaeroMapFolders.shownWorld("Multiplayer_203.0.113.7"));
        assertEquals("play.example.net:25570", XaeroMapFolders.shownWorld("Multiplayer_play.example.net§25570"));
        assertEquals("My_World", XaeroMapFolders.shownWorld("My%us%World"));
    }

    @Test
    void aMapTakesTheNameXaeroGaveIt() {
        List<String> config = List.of("MWName:mw$123456789:Map 1", "caveModeType:1");
        assertEquals("Map 1", XaeroMapFolders.mapName(config, "mw$123456789"));
        assertEquals("default", XaeroMapFolders.mapName(config, "mw$default"));
        assertEquals("12345", XaeroMapFolders.mapName(List.of(), "mw$12345"));
    }
}
