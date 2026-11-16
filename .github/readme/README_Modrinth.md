<p align="center">
  <img src="https://raw.githubusercontent.com/Tapacywka/teyvat-cartographia/main/.github/images/banner.png" alt="Teyvat Cartographia" width="100%">
</p>

<p align="center">
  <a href="https://modrinth.com/mod/teyvat-cartographia"><img src="https://img.shields.io/modrinth/dt/teyvat-cartographia?style=flat&logo=modrinth&label=modrinth&color=00AF5C" alt="Modrinth downloads"></a>
  <a href="https://www.curseforge.com/minecraft/mc-mods/teyvat-cartographia"><img src="https://img.shields.io/curseforge/dt/1764160?style=flat&logo=curseforge&label=curseforge&color=F16436" alt="CurseForge downloads"></a>
  <a href="https://github.com/Tapacywka/teyvat-cartographia/releases"><img src="https://img.shields.io/github/downloads/Tapacywka/teyvat-cartographia/total?style=flat&logo=github&label=downloads&color=4C9F70" alt="GitHub downloads"></a>
  <img src="https://img.shields.io/badge/side-client-5B8DEF?style=flat" alt="Client side">
  <a href="https://github.com/Tapacywka/teyvat-cartographia/blob/main/LICENSE"><img src="https://img.shields.io/badge/license-MIT-8A8F98?style=flat" alt="MIT license"></a>
</p>

<p align="center">
  <b>A world map and minimap in the spirit of Genshin Impact.</b><br>
  Explore, and the land you see is painted onto the map. Everything else stays under the Fog of War.
</p>

<p align="center">
  <a href="https://modrinth.com/mod/teyvat-cartographia"><img src="https://raw.githubusercontent.com/intergrav/devins-badges/v3/assets/cozy/available/modrinth_vector.svg" alt="Available on Modrinth" height="48"></a>
  <a href="https://www.curseforge.com/minecraft/mc-mods/teyvat-cartographia"><img src="https://raw.githubusercontent.com/intergrav/devins-badges/v3/assets/cozy/available/curseforge_vector.svg" alt="Available on CurseForge" height="48"></a>
  <a href="https://github.com/Tapacywka/teyvat-cartographia/releases"><img src="https://raw.githubusercontent.com/intergrav/devins-badges/v3/assets/cozy/available/github_vector.svg" alt="Available on GitHub" height="48"></a>
</p>

<h2 id="features">✨ Features</h2>

<table>
<tr>
<td width="55%"><img src="https://raw.githubusercontent.com/Tapacywka/teyvat-cartographia/main/.github/images/world-map.jpg" alt="The world map: forested islands and bays you explored, the Fog of War around them"></td>
<td><h3>🗺️ World map</h3>Press <b>M</b> for the land you explored, in the colours of its blocks, with shading and water depth. Pan, zoom from 1/32 to 32, search pins and biomes with <b>Ctrl+F</b>.</td>
</tr>
<tr>
<td width="55%"><img src="https://raw.githubusercontent.com/Tapacywka/teyvat-cartographia/main/.github/images/minimap.jpg" alt="The minimap in the corner over a jungle, with coordinates, biome and the time under it"></td>
<td><h3>🧭 Minimap</h3>Square or round, north up or turning with you, with coordinates, biome and a clock.</td>
</tr>
<tr>
<td width="55%"><img src="https://raw.githubusercontent.com/Tapacywka/teyvat-cartographia/main/.github/images/pin-search.jpg" alt="Searching pins and biomes by name in the pin list"></td>
<td><h3>📍 Pins</h3>Seven types, up to 300 per world. Right-click the map to place one, or press <b>B</b> where you stand. Find any of them by name in the pin list.</td>
</tr>
<tr>
<td width="55%"><img src="https://raw.githubusercontent.com/Tapacywka/teyvat-cartographia/main/.github/images/beam-sparkles.jpg" alt="The beam of light over a tracked pin and the path sparkles"></td>
<td><h3>✨ Tracking</h3>A golden beam of light over the pin, sparkles on the ground and a line on both maps that walks you there, around water and mountains. Or by boat, or straight through the air for elytra.</td>
</tr>
<tr>
<td width="55%"><img src="https://raw.githubusercontent.com/Tapacywka/teyvat-cartographia/main/.github/images/caves.jpg" alt="The world map on a cave layer: the cave around the player, the rest under the Fog of War"></td>
<td><h3>⛏️ Caves</h3>Underground, the map switches to the layer you are in and maps the cave around you. Pins on other layers stay, fainter, with an arrow up or down.</td>
</tr>
<tr>
<td width="55%"><img src="https://raw.githubusercontent.com/Tapacywka/teyvat-cartographia/main/.github/images/radar.jpg" alt="Mobs around the player on the world map as their faces"></td>
<td><h3>📡 Radar</h3>Other players as arrows or their skins' heads, mobs as dots or their faces, on the minimap and the world map, by the cave layer you look at. On servers only players, unless you allow mobs.</td>
</tr>
<tr>
<td width="55%"><img src="https://raw.githubusercontent.com/Tapacywka/teyvat-cartographia/main/.github/images/nether.jpg" alt="The map of The Nether: crimson forest, lava lakes and basalt under its own fog"></td>
<td><h3>🌋 Every dimension</h3>Each dimension gets its own map, The Nether and The End their own Fog of War.</td>
</tr>
<tr>
<td width="55%"><img src="https://raw.githubusercontent.com/Tapacywka/teyvat-cartographia/main/.github/images/settings-checkboxes.jpg" alt="Settings: a box for each place a kind of pin or path shows, with a live preview"></td>
<td><h3>⚙️ Settings</h3>A live preview of whatever you point at; pick where each kind of pin and path shows, box by box. English and Russian.</td>
</tr>
<tr>
<td width="55%"><img src="https://raw.githubusercontent.com/Tapacywka/teyvat-cartographia/main/.github/images/hero-world-map.jpg" alt="The world map with map edges and the world border, far land under the Fog of War"></td>
<td><h3>🧪 Experimental</h3>Off by default: Far land (the outline of unexplored land from the world's generator), soft animated map edges, the world border on the map.</td>
</tr>
</table>

The mod costs at most a few percent of your frame rate: the minimap is redrawn only when it changes, and the map is made on background threads.

<h2 id="installation">📥 Installation</h2>

| Minecraft | Loader | You also need |
|---|---|---|
| 1.21.1, 26.1.2, 26.2, 26.3 | [Fabric](https://github.com/FabricMC/fabric-loader) | [Fabric API](https://modrinth.com/mod/fabric-api) and [Actually Amazing Config Screen](https://modrinth.com/mod/actually-amazing-config-screen) |
| 1.21.1, 26.1.2, 26.2, 26.3 | [NeoForge](https://github.com/neoforged/NeoForge) | [Actually Amazing Config Screen](https://modrinth.com/mod/actually-amazing-config-screen) |

Download the file for your game and loader (`+mc1.21.1-fabric`, `+mc26.3-neoforge` and so on) and Actually Amazing Config Screen for the same game and loader (its settings screen; Prism Launcher, the Modrinth App and the CurseForge App offer it by themselves), put both into `mods/` and press **M** in a world. The mod is client-side only: it works on any server, vanilla or modded, and the server does not need it.

<h2 id="connector">🔌 Sinytra Connector</h2>

On NeoForge, take the NeoForge file. The Fabric file runs there too, through [Sinytra Connector](https://modrinth.com/mod/connector) with [Forgified Fabric API](https://modrinth.com/mod/forgified-fabric-api) and the Fabric file of Actually Amazing Config Screen (checked on 1.21.1, 26.1.2 and 26.2); [Connector Extras](https://modrinth.com/mod/connector-extras) adds its settings button to NeoForge's mod list.

<h2 id="controls">🎮 Controls</h2>

| Key | What it does |
|---|---|
| **M** | World map (**Esc** or **M** closes it) |
| **N** / **=** / **-** | Minimap on or off, zoom in, zoom out |
| **B** | New pin where you stand |
| **V** | Show the tracked pin and its path again |
| **Z** | Stop tracking |
| On the map | Drag to pan, wheel to zoom, right-click for a new pin, **Ctrl+F** to search, **Space** to come back to you |

Every key can be changed in the game's controls.

<h2 id="compatibility">🧩 Works with</h2>

Blocks of any mod are drawn in the colours of their textures, so most mods just work. Some get a little more:

| Mod | What you get | 1.21.1 | 26.1.2 | 26.2 | 26.3 |
|---|---|:-:|:-:|:-:|:-:|
| [Waystones](https://modrinth.com/mod/waystones) | Its stones on the map, trackable | ✅ | ✅ | ✅ | ✅ |
| [Xaero's Minimap](https://modrinth.com/mod/xaeros-minimap) | Import of its waypoints | ✅ | ✅ | ✅ | ✅ |
| [Xaero's World Map](https://modrinth.com/mod/xaeros-world-map) | Import of the explored map | ✅ | ✅ | ✅ | ✅ |
| [Create](https://modrinth.com/mod/create) | Its railways, stations and trains on the maps | ✅ | ➖ | ➖ | ➖ |
| [Sable](https://modrinth.com/mod/sable) | Its ships on the radar | ✅ | ➖ | ➖ | ➖ |
| [Vanilla Backport](https://modrinth.com/mod/vanillabackport) | Its mobs' faces on the radar | ✅ | ➖ | ➖ | ➖ |
| [Tiny Takeover Backport](https://modrinth.com/mod/tiny-takeover-backport) | Its mobs' faces on the radar | ✅ | ➖ | ➖ | ➖ |
| [The Aether](https://modrinth.com/mod/aether) | Its own Fog of War over a sea of clouds | ✅ | ➖ | ➖ | ➖ |
| [Mielon's The Sift](https://modrinth.com/mod/mielons-the-sift) | Its own Fog of War, the shimmer of ichor | ✅ | ➖ | ➖ | ➖ |
| [VulkanMod](https://modrinth.com/mod/vulkanmod) (Fabric) | Maps and markers drawn right under Vulkan | ✅ | ✅ | ✅ | ➖ |
| [VulkanMod-Reforged](https://modrinth.com/mod/vulkanmod-reforged) (NeoForge) | Maps and markers drawn right under Vulkan | ✅ | ➖ | ➖ | ➖ |
| [Sodium](https://modrinth.com/mod/sodium), [Iris](https://modrinth.com/mod/iris), [Sodium Extra](https://modrinth.com/mod/sodium-extra) | Work together, shaders included | ✅ | ✅ | ✅ | ✅ |
| [Mod Menu](https://modrinth.com/mod/modmenu) | The settings button in its list | ✅ | ✅ | ✅ | ✅ |
| [Sinytra Connector](https://modrinth.com/mod/connector) | The Fabric file on NeoForge | ✅ | ✅ | ✅ | ➖ |

<h3>Waystones</h3>

Every waystone, sharestone and warp plate you have seen is a point on both maps that you can track; the ones you have not activated yet are grey.

<p align="center">
  <img src="https://raw.githubusercontent.com/Tapacywka/teyvat-cartographia/main/.github/images/waystones.jpg" alt="A waystone, a sharestone and a warp plate as points on the world map" width="80%">
</p>

<h3>Xaero's Minimap and Xaero's World Map</h3>

Moving from Xaero's mods? Import their waypoints as pins from the pin list, and their explored map as yours in Settings > Compatibility (in single player, after the full resave in Xaero's map menu).

<p align="center">
  <img src="https://raw.githubusercontent.com/Tapacywka/teyvat-cartographia/main/.github/images/xaero-import.jpg" alt="The import of the explored map from Xaero's World Map, done" width="49%">
  <img src="https://raw.githubusercontent.com/Tapacywka/teyvat-cartographia/main/.github/images/xaero-map.jpg" alt="The world map after the import: the land Xaero's World Map had explored now on it" width="49%">
</p>

<h3>Create</h3>

Tracks as rails with sleepers, stations with their names, signals, and every train on the world map with its carriages, coloured by what it is doing; the carriages near you on the minimap.

<p align="center">
  <img src="https://raw.githubusercontent.com/Tapacywka/teyvat-cartographia/main/.github/images/create.jpg" alt="A railway of Create round a jungle with four stations: a running train in green, one waiting at a signal in orange, one standing in grey" width="80%">
</p>

<h3>Sable</h3>

Ships and other moving structures on the radar, drawn from their own blocks and turned as they sail; far out an icon.

<p align="center">
  <img src="https://raw.githubusercontent.com/Tapacywka/teyvat-cartographia/main/.github/images/sable.jpg" alt="A sailing ship of Sable named Sloop, drawn from its own blocks on the world map" width="80%">
</p>

<h3>Vanilla Backport and Tiny Takeover Backport</h3>

The radar shows their mobs with their own faces: the happy ghast, the copper golem and the warm and cold farm animals of Vanilla Backport, the new babies of Tiny Takeover Backport.

<p align="center">
  <img src="https://raw.githubusercontent.com/Tapacywka/teyvat-cartographia/main/.github/images/backports-vb.jpg" alt="The happy ghast, the copper golem and the warm and cold farm animals of Vanilla Backport as faces on the world map" width="49%">
  <img src="https://raw.githubusercontent.com/Tapacywka/teyvat-cartographia/main/.github/images/backports-ttb.jpg" alt="The babies of Tiny Takeover Backport as faces on the world map" width="49%">
</p>

<h3>The Aether and The Sift</h3>

Each gets a Fog of War of its own: a sea of clouds under the islands of the Aether, a pearly haze in The Sift, where ichor and ichor snow shimmer in their colours.

<p align="center">
  <img src="https://raw.githubusercontent.com/Tapacywka/teyvat-cartographia/main/.github/images/aether-map.jpg" alt="The map of the Aether over its sea of clouds" width="49%">
  <img src="https://raw.githubusercontent.com/Tapacywka/teyvat-cartographia/main/.github/images/sift.jpg" alt="Ichor shimmering in its colours on the map of The Sift" width="49%">
</p>


<h2 id="faq">❓ Questions</h2>

<details>
<summary><b>Does it show the land I have not visited?</b></summary>

No, that is the point: you map the world by exploring it. Far land (Settings > Experimental, single player) can show the outline of unexplored land if you want it.
</details>

<details>
<summary><b>Where is my map saved?</b></summary>

In `teyvatcartographia/` of your game folder, one map per world or server and per dimension. Pins are in `markers.json` there. They can also be exported to a file from the pin list.
</details>

<details>
<summary><b>Can I make my own theme?</b></summary>

Yes, as a resource pack: colours, icons and the fog live in `assets/teyvatcartographia/themes/<id>/`. Copy the built-in `default` theme from the mod's file and change what you like.
</details>

<h2 id="links">🔗 Links</h2>

- [Report a bug or suggest an idea](https://github.com/Tapacywka/teyvat-cartographia/issues/new/choose) (please attach `logs/latest.log`)
- [Changelog](https://modrinth.com/mod/teyvat-cartographia/changelog)
- [Source code](https://github.com/Tapacywka/teyvat-cartographia): `./gradlew build` (branch `main` for 1.21.1, `mc26` for 26.x)

<sub>MIT License. Icons from Game Icon Pack by Nieobie (CC0). The settings screen is made with [Actually Amazing Config Screen](https://modrinth.com/mod/actually-amazing-config-screen). A fan-made mod, not affiliated with HoYoverse; no Genshin Impact assets.</sub>
