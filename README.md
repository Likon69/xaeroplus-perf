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
