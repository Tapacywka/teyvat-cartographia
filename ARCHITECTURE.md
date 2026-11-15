# How Teyvat Cartographia is built

A map for the client only: it reads the chunks the game has loaded, draws them into pictures of 256 x 256 blocks
(regions), keeps those on disk, and shows them on a minimap and a world map, with pins, a path to the tracked pin and a
marker in the world. This page is the overview a contributor needs before changing it; the details are in the Javadoc
of each class.

## Branches and modules

* `main` is Minecraft 1.21.1, `mc26` is every 26.x game (26.1.2, 26.2, 26.3, chosen with `-Pmc=26.2` and so on). A fix
  is made on `main` and brought to `mc26`; what differs between games lives on `mc26` in
  `common/src/<game or shared folder>/java/.../version/` (`Game`, `Gpu`, dialogs, keys), so the rest of the code stays
  the same on both.
* `common/` holds the whole mod and its unit tests. It is compiled against the game alone, so a loader's API used there
  fails the build.
* `fabric/` and `neoforge/` hold only the glue of each loader: events, the HUD layer, key bindings, the settings screen
  in the mod list. Common code asks the loader through `platform/Platform`, set by each entry point before anything
  else (`client/ClientStart`).
* The settings and their screen come from Actually Amazing Config Screen, a mod of its own (`config/MapConfig`).

## The map, from a chunk to the disk

1. **Choosing chunks** (`MapDataManager`, main thread, every tick): loaded chunks around the player that are new, changed
   or not seen for a while go into a queue. Each tick takes a few of them (the setting Chunks per tick), and never more
   than 48 are on their way at once.
2. **Taking a chunk** (main thread): the chunk and its eight neighbours as the level holds them now
   (`ChunkScanner.around`), and what the map already knows of the neighbours' edges. A chunk object keeps its data once
   taken, so the worker never asks the level, which may have unloaded a neighbour meanwhile.
3. **Reading and shading** (a worker, `TeyvatCartographia-Shader`, two threads): the top of every column, its colour from
   the block textures and biome tints, water depth, light (`ChunkScanner`), then the shading against the neighbours
   (`ChunkShader`). A chunk that changed under the reader is given back and read again on a later sweep.
4. **Applying** (main thread, `MapDataManager.applyResults`): the result goes into its region (`TileStore`, `MapTile`).
   Every result carries the generation it was taken in; entering another world or dimension raises the generation, so
   results of the one before are dropped, never applied to the wrong map.
5. **Saving and reading regions** (`TeyvatCartographia-IO` writes, `TeyvatCartographia-IO-Read` reads): a region is
   written through a temporary file moved into place (`MapStorage.save`, `replace`), so it is never half written. A read
   of a region whose write is still queued goes behind that write (`IoOrder`). Leaving a world waits for the writes.

Beside it, each handing finished work to the main thread: the cave layers (`CaveScanner`, on the same workers, bands of
16 blocks while the player is underground), far away zooms (`LodStore`, thumbnails of regions read on the IO threads),
the outline of the explored map (`TileStore`'s `TeyvatCartographia-Edges`), the land not explored yet worked
out from the world's generator (`FarLand`, `TeyvatCartographia-FarLand-*`, off by default), the biome index of the map
search (`BiomeIndex`, `TeyvatCartographia-Search`) and the import of Xaero's World Map (`XaeroImport`).

## Threads: who may touch what

| Thread | May touch | Hands its work over through |
|---|---|---|
| Main (client tick, frames) | The level, the game's GUI and GPU, every map store, pins, navigation | |
| `TeyvatCartographia-Shader` | Only the chunk objects it was given and its own arrays | `MapDataManager.results`, a queue the main thread drains |
| `TeyvatCartographia-IO`, `-IO-Read` | Only the files of a map folder and the copies it was given | the store's `loaded` queue, read on the main thread |
| `TeyvatCartographia-Edges`, `-FarLand-*`, `-Search` | Only their own data and pure rules | queues read by the main thread |

Pictures may be made on any of these threads, but only the main thread uploads them to the GPU or frees them there.
Work that can grow on the main thread (the path search, laying out a long path) runs in slices of at most a millisecond
a tick.

## Drawing

Every HUD picture (the minimap, the lines under it, the marker of the tracked pin) is drawn into a cached layer
(`render/CachedLayer`) and drawn again only when what it shows changes: everything it depends on is in its cache key.
Code that runs every frame allocates nothing. The world map (`gui/WorldMapScreen`) draws the regions' textures through
`render/MapLayerRenderer`; its side panels are in `gui/panel`. The beam and the path's stars are drawn in the world after
the level (`hud/WorldMarkerLayer`, `hud/PathStars`).

## Files

Everything of a world is under `<game folder>/teyvatcartographia/<world>/`:

| File | What it holds |
|---|---|
| `markers.json` | The player's pins and the tracked pin. One that cannot be read is copied to `markers.json.broken` first. |
| `waystones.json` | The stones of Waystones seen but not activated. |
| `<dimension>/dimension.txt` | The dimension of the folder; only folders with it are maps. |
| `<dimension>/format.txt` | The version of the regions' format; regions of an older one are deleted once and mapped again. |
| `<dimension>/r.<x>.<z>.png`, `.dat` | A region: its picture, and its heights, flags, chunk states and biomes (compressed). |
| `<dimension>/r.<x>.<z>.lod.png` | The region's thumbnail for far zooms. |
| `<dimension>/index.txt`, `chunks.idx` | How many and which chunks of each region are on the map, so the outline needs no region read. |
| `<dimension>/biomes.idx` | The biome of every chunk, for the map search. |
| `<dimension>/caves/b<band>/`, `far/s<step>/` | Cave layers and the land worked out from the generator, regions as above. |

A region that cannot be read is renamed to `<name>.broken` before the region is mapped anew in its place, so nothing
writes over the only copy; an error of the game itself (out of memory) is not taken for damage.

## Rules apart from the game, and tests

What decides something (the path search, pin visibility, radar rules, zoom steps, the outline's shape, the far land's
answers, the settings' defaults) lives in classes that take plain data, not the level or the screen, so the unit tests
in `common/src/test/java` run them without a game: `./gradlew test`. A change of such a rule comes with a test. GitHub
runs the tests and builds both JARs on every pull request.
