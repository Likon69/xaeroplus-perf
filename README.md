# XaeroPlus, highlight performance fork

Unofficial fork of [XaeroPlus](https://github.com/rfresh2/XaeroPlus) by rfresh2, based on **2.37.2 for Fabric 1.21.11**
(WorldMap 1.47.2, Minimap 26.6.2). It changes how chunk highlights (OldChunks, PaletteNewChunks, LiquidNewChunks,
Portals, OldBiomes) are loaded, kept and drawn, so that a world map zoomed out over 2b2t spawn, with millions of
highlights per layer, stays smooth. Every other feature is upstream's, unchanged.

- Not affiliated with or endorsed by rfresh2 or xaero96. Report problems with this fork here, not upstream.
- Only the Fabric 1.21.11 build was tested in game. The Forge and NeoForge modules share the changed common code but were not tested.
- Build: `./gradlew :fabric:build`, output in `fabric/build/libs/`.
- Xaero's WorldMap and Minimap are still required as separate downloads, exactly as with upstream.

## Differences from upstream

1. The highlight cache shrinks back after highlights outside the window are flushed. Upstream it kept its peak capacity.
2. Chunk highlights are drawn in tiles of 16x16 regions. A tile is rebuilt only when its own content changes, instead of the whole layer every 500 ms. The minimap and the world map both benefit.
3. Highlights are indexed by tile, so evicting what left the window touches only the tiles at or past its border, instead of walking every cached chunk on the client thread.
4. Tile rebuilds share a budget of 3 ms per frame, with at least one tile per layer per frame. A dense area fills in tile by tile instead of freezing the frame.
5. Database reads fetch only the area that was never loaded, split into at most four rectangles. Each rectangle is read with one primary-key seek per chunk column, instead of scanning a whole column band. A one-region pan at a 200-region window went from 517 ms to 36 ms on a real 2b2t database.
6. A failed read resets the window, so the next tick reads it again instead of leaving a permanent hole.
7. Read results are integrated in database order within a budget of 6 ms per tick, and only for chunks inside the current window. Results for a window you already left, for example after closing the map, are skipped.
8. Eviction waits 30 s after the last load, through panning, zooming in and closing the map, as long as a layer stays under 6 million chunks. It then runs in budgeted steps across ticks. Zooming back out or reopening the map within that time reads nothing from disk.
9. A highlight removed while a read is in flight is not brought back by that read.
10. When OldChunks is drawn inverted, the hidden old-chunks layer is only kept around the player, which is where the scan reads it.

The chunk highlight API used by other mods is unchanged: `getCacheMap`, `isHighlighted`, the `isOldChunk`/`isNewChunk`
module methods and the original `DrawFeatureFactory` overloads. One overload was added for the tiled layers.

## Measured effect

Measured with spark on the render thread, world map zoomed out over 2b2t spawn:

| | Upstream 2.37.2 | This fork |
| --- | --- | --- |
| Highlight rebuild, map still | 25 to 46 % of the render thread | 0 |
| Highlight work while panning | about 38 s per minute | a few seconds per minute, spread over frames and ticks |
| Freeze when panning fast or closing the map | several seconds | none observed |

Rendering of the map tiles by Xaero's WorldMap itself is not changed, and remains the largest cost when zoomed far out.

## Credits and license

- [XaeroPlus](https://github.com/rfresh2/XaeroPlus) is by **rfresh2** and contributors, under the MIT license. This fork keeps that license and its copyright notice in `LICENSE`.
- [Xaero's World Map](https://modrinth.com/mod/xaeros-world-map) and [Minimap](https://modrinth.com/mod/xaeros-minimap) are by **xaero96**. They are not included here.
- Changes in this fork by Likon69.

---

The upstream README follows, unchanged.

# Xaero Versions

Each XaeroPlus release is only compatible with a specific version of Xaero's WorldMap and Minimap (or [BetterPVP](https://chocolateminecraft.com/betterpvp2.php)).

Download and include these mods **in addition** to `XaeroPlus-*.jar` (3 jars total).

You can find download links to Xaero's mods here:
* https://modrinth.com/mod/xaeros-world-map/versions
* https://modrinth.com/mod/xaeros-minimap/versions

# Modifications

* [1.5-3x your FPS by limiting the framerate the minimap is rendered at!](https://youtu.be/hIG-VyGQLao)
  * Tip: For least visual impact, lock the minimap's north
* Drawing Tool
* [Adjustable minimap scaling that increases how many chunks are visible](https://youtu.be/dNqxGzGAHyk)
* [NewChunks Highlighting in MiniMap and WorldMap.](https://youtu.be/n-Tf6TJSsiA)
* [Baritone](https://github.com/cabaletta/baritone) integration
  * Baritone Goals synced as temporary waypoints
  * [Point and Click Travel](https://youtu.be/gbguyfXLgi0)
* [WorldTools](https://modrinth.com/mod/worldtools/) World Downloader integration
  * [Highlights saved chunks while downloading](https://youtu.be/mtCqwJ_RGcc)
* [Portals Highlighting in Minimap and WorldMap](https://youtu.be/zstGVfVRrAs)
* [Portal Skip Highlighting in Minimap and WorldMap](https://youtu.be/g_yQ8D95RY0). Detects chunks where a portal could have been loaded.
* OldChunks Highlighting in Minimap and WorldMap.
  * Intended for use on 2b2t. Highlights chunks that were generated in 1.12.2 or lower in the Overworld and Nether based on the natural blocks present in the chunk.
* Transparent obsidian roof. Useful for mapping 2b2t spawn.
* Setting to always view and create waypoints in the Overworld when in Nether.
* [Render server view distance square around the player.](https://youtu.be/iY_JTGFK6Yg)
* [Dimension hot-switching on WorldMap and Minimap.](https://youtu.be/hXZQtX2df3I)
  * Recommended: Create keybinds for Minimap dimension switching.
* Waypoints GUI:
  * GUI button to enable/disable all waypoints
* [Waypoint Beacons](https://imgur.com/a/jGgHqL4)
* WorldMap and Waypoint directories optionally indexed by:
  * Multiplayer server list name.
  * Base Server Domain Name
  * Server IP (Xaero's default)
  * **Changing this setting requires you to manually rename existing folders in `.minecraft/xaero/minimap` and `.minecraft/xaero/world-map`**
* WorldMap 1.30.0 added cave data saving and rendering. There is a setting on by default in XaeroPlus that changes how the nether is rendered with cave mode off to be as it was previously.
  * This removes the need to manually move existing world data files.

Configurations are in the Xaero WorldMap and Minimap settings GUI.

Toggleable settings support keybinds through the standard Minecraft Controls GUI.

# Language Translations

PR's are welcomed for language translations. 

Language files are located in `common/src/main/resources/assets/xaeroplus/lang/`

Submit PR's targeting the `1.20.1` branch only. Changes will be merged to all the other MC versions by me.

# Other Useful Tools

* Convert JourneyMap World Files to Xaero: [JMToXaero](https://github.com/Entropy5/JMtoXaero)
* Convert JourneyMap Waypoints to Xaero: [JMWaypointsToXaero](https://github.com/rfresh2/JMWaypointsToXaero)
* 2b2t World Download Xaero Maps:
  * 2025 100k^2 (6GB): https://data.mc-archive.org/s/6XJqdEtxkqwSGrs
    * Cache (15GB): https://data.mc-archive.org/s/HSB3aEatWDFwG4Z 
  * 2022 256k^2 (20GB): https://data.mc-archive.org/s/eFDEy2XKof83Kez
