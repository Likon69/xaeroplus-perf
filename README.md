# xaeroplus-perf

Fork of [XaeroPlus](https://github.com/rfresh2/XaeroPlus) for **Minecraft 1.21.11 only** (`2.37.2`, Fabric, WorldMap
1.47.2, Minimap 26.6.2), with the chunk highlight changes a world map zoomed out over 2b2t spawn needs: millions of
OldChunks, PaletteNewChunks, LiquidNewChunks, Portals and OldBiomes highlights loaded and drawn without lag or freeze.
Every other feature is upstream's, unchanged.

- Build: `./gradlew :fabric:build`, output in `fabric/build/libs/`. Current version: **v10**.
- Xaero's WorldMap and Minimap are still required as separate downloads, exactly as with upstream.
- Only the Fabric build was tested in game. Forge and NeoForge share the changed common code but were not tested.
- Not affiliated with rfresh2 or xaero96. Report problems with this fork here, not upstream.

## Differences from upstream

1. The highlight cache shrinks back after highlights outside the window are flushed, instead of keeping its peak capacity.
2. Highlights are drawn in tiles of 16x16 regions, and a tile is rebuilt only when its own content changes, instead of the whole layer every 500 ms. On a still map, the rebuild cost goes from 25-46 % of the render thread to zero.
3. Highlights are indexed by tile, so evicting what left the window touches only the tiles at or past its border, instead of walking every cached chunk on the client thread.
4. Tile rebuilds share 3 ms per frame, with at least one tile per layer per frame, so a dense area fills in tile by tile instead of freezing the frame.
5. The database is read only for areas never loaded, in at most four rectangles, each with one primary-key seek per chunk column instead of a scan of the whole column band. A one-region pan went from 517 ms to 36 ms.
6. A failed read resets the window, so the next tick reads it again instead of leaving a permanent hole.
7. Read results are integrated in database order within 6 ms per tick, and only for chunks inside the current window. Results for a window already left, such as after closing the map, are skipped.
8. Eviction waits 30 s after the last load through panning, zooming in and closing the map, up to 6 million chunks per layer, then runs in small steps across ticks. Zooming back out or reopening the map within that time reads nothing from disk.
9. A highlight removed while a read is in flight is not brought back by that read.
10. When OldChunks is drawn inverted, the hidden old-chunks layer is only kept around the player, where the scan reads it.

The highlight API used by other mods is unchanged: `getCacheMap`, `isHighlighted`, the `isOldChunk` and `isNewChunk`
module methods and the original `DrawFeatureFactory` overloads. One overload was added for the tiled layers.

## Credits

rfresh2 and contributors for XaeroPlus, under the MIT license kept in `LICENSE`. xaero96 for Xaero's World Map and
Minimap, not included here.
