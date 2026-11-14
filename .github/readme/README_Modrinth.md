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

<p align="center">
  <img src="https://raw.githubusercontent.com/Tapacywka/teyvat-cartographia/main/.github/images/hero-world-map.jpg" alt="The world map: an explored island with map edges and the world border, far land under the Fog of War" width="100%">
</p>

<h2 id="features">✨ Features</h2>

- **World map** (**M**): the land you explored, in the colours of its blocks, with shading and water depth. Pan, zoom from 1/32 to 32, search pins and biomes with **Ctrl+F**.
- **Minimap**: square or round, north up or turning with you, with coordinates, biome and a clock.
- **Pins**: seven types, up to 300 per world. Right-click the map to place one, or press **B** where you stand.
- **Tracking**: a golden beam of light over the pin, sparkles on the ground and a line on both maps that walks you there, around water and mountains. Or by boat, or straight through the air for elytra.
- **Caves**: underground, the map switches to the layer you are in.
- **Radar**: other players as arrows or their skins' heads, mobs as dots or their faces, on the minimap and the world map, by the cave layer you look at. On servers only players, unless you allow mobs.
- **Every dimension** gets its own map, The Nether and The End their own fog.
- **Settings** with a live preview of whatever you point at; pick where each kind of pin and path shows, box by box. English and Russian.
- **Experimental**, off by default: Far land (the outline of unexplored land from the world's generator), soft animated map edges, the world border on the map.

<p align="center">
  <img src="https://raw.githubusercontent.com/Tapacywka/teyvat-cartographia/main/.github/images/world-map-water-path.jpg" alt="The path to a tracked pin across an island, going on over water, with the radar's mobs" width="49%">
  <img src="https://raw.githubusercontent.com/Tapacywka/teyvat-cartographia/main/.github/images/world-map-radar.jpg" alt="The path around bays and rivers to a tracked pin, pins and mobs on the world map" width="49%">
  <img src="https://raw.githubusercontent.com/Tapacywka/teyvat-cartographia/main/.github/images/pin-search.jpg" alt="Searching pins and biomes by name in the pin list" width="49%">
  <img src="https://raw.githubusercontent.com/Tapacywka/teyvat-cartographia/main/.github/images/pin-pick-list.jpg" alt="A list to pick from where several pins lie on one spot" width="49%">
  <img src="https://raw.githubusercontent.com/Tapacywka/teyvat-cartographia/main/.github/images/settings-checkboxes.jpg" alt="Settings: a box for each place a kind of pin or path shows, with a live preview" width="49%">
  <img src="https://raw.githubusercontent.com/Tapacywka/teyvat-cartographia/main/.github/images/settings-compat.jpg" alt="Settings: a section for each supported mod in the game" width="49%">
  <img src="https://raw.githubusercontent.com/Tapacywka/teyvat-cartographia/main/.github/images/aether-map.jpg" alt="The map of the Aether over its sea of clouds" width="49%">
  <img src="https://raw.githubusercontent.com/Tapacywka/teyvat-cartographia/main/.github/images/beam-sparkles.jpg" alt="The beam of light over a tracked pin and the path sparkles" width="49%">
</p>

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
[Waystones](https://modrinth.com/mod/waystones) (its stones on the map, trackable),
[Xaero's Minimap](https://modrinth.com/mod/xaeros-minimap) (import its waypoints) and
[Xaero's World Map](https://modrinth.com/mod/xaeros-world-map) (import the explored map),
[The Aether](https://modrinth.com/mod/aether) and [Mielon's The Sift](https://modrinth.com/mod/mielons-the-sift) (their own fog),
[Sable](https://modrinth.com/mod/sable) (its ships on the radar, 1.21.1),
[Vanilla Backport](https://modrinth.com/mod/vanillabackport) and [Tiny Takeover Backport](https://modrinth.com/mod/tiny-takeover-backport) (their mobs' faces on the radar, 1.21.1),
[Sodium](https://modrinth.com/mod/sodium), [Iris](https://modrinth.com/mod/iris),
[VulkanMod](https://modrinth.com/mod/vulkanmod) and [Mod Menu](https://modrinth.com/mod/modmenu).

The mod costs at most a few percent of your frame rate: the minimap is redrawn only when it changes, and the map is made on background threads.

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
