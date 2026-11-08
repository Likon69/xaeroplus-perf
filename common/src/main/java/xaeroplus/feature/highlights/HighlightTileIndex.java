package xaeroplus.feature.highlights;

import it.unimi.dsi.fastutil.longs.*;
import xaeroplus.util.ChunkUtils;

import java.util.function.LongConsumer;

public final class HighlightTileIndex {
    private static final int TILE_SHIFT = 9;
    public static final HighlightTileIndex EMPTY = new HighlightTileIndex();
    private final Long2ObjectOpenHashMap<LongOpenHashSet> tiles = new Long2ObjectOpenHashMap<>();
    private final Long2LongOpenHashMap revisions = new Long2LongOpenHashMap();
    private final Long2LongMap revisionView = Long2LongMaps.unmodifiable(revisions);
    private long generation;

    public boolean add(long pos) {
        checkMutable();
        long key = tileKey(pos);
        LongOpenHashSet keys = tiles.get(key);
        if (keys == null) {
            keys = new LongOpenHashSet();
            tiles.put(key, keys);
        }
        if (!keys.add(pos)) return false;
        revisions.put(key, ++generation);
        return true;
    }

    public boolean remove(long pos) {
        checkMutable();
        long key = tileKey(pos);
        LongOpenHashSet keys = tiles.get(key);
        if (keys == null || !keys.remove(pos)) return false;
        revisions.put(key, ++generation);
        if (keys.isEmpty()) {
            tiles.remove(key);
            revisions.remove(key);
        }
        return true;
    }

    public void clear() {
        checkMutable();
        generation += tiles.size();
        tiles.clear();
        revisions.clear();
    }

    public long generation() {
        return generation;
    }

    public boolean isEmpty() {
        return tiles.isEmpty();
    }

    public Long2LongMap revisions() {
        return revisionView;
    }

    public LongSet keysOf(long tileKey) {
        LongOpenHashSet keys = tiles.get(tileKey);
        return keys == null ? LongSets.EMPTY_SET : LongSets.unmodifiable(keys);
    }

    public void evictOutside(int minX, int maxX, int minZ, int maxZ, LongConsumer evicted) {
        checkMutable();
        for (var it = tiles.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            var entry = it.next();
            long key = entry.getLongKey();
            int tileMinX = ChunkUtils.longToChunkX(key) << TILE_SHIFT;
            int tileMinZ = ChunkUtils.longToChunkZ(key) << TILE_SHIFT;
            int tileMaxX = tileMinX + (1 << TILE_SHIFT) - 1;
            int tileMaxZ = tileMinZ + (1 << TILE_SHIFT) - 1;
            if (tileMinX >= minX && tileMaxX <= maxX && tileMinZ >= minZ && tileMaxZ <= maxZ) continue;
            LongOpenHashSet keys = entry.getValue();
            if (tileMaxX < minX || tileMinX > maxX || tileMaxZ < minZ || tileMinZ > maxZ) {
                for (var positions = keys.iterator(); positions.hasNext(); ) evicted.accept(positions.nextLong());
                it.remove();
                revisions.remove(key);
                generation++;
            } else {
                for (var positions = keys.iterator(); positions.hasNext(); ) {
                    long pos = positions.nextLong();
                    int x = ChunkUtils.longToChunkX(pos);
                    int z = ChunkUtils.longToChunkZ(pos);
                    if (x < minX || x > maxX || z < minZ || z > maxZ) {
                        positions.remove();
                        revisions.put(key, ++generation);
                        evicted.accept(pos);
                    }
                }
                if (keys.isEmpty()) {
                    it.remove();
                    revisions.remove(key);
                }
            }
        }
    }

    private static long tileKey(long pos) {
        return ChunkUtils.chunkPosToLong(
            ChunkUtils.longToChunkX(pos) >> TILE_SHIFT,
            ChunkUtils.longToChunkZ(pos) >> TILE_SHIFT
        );
    }

    private void checkMutable() {
        if (this == EMPTY) throw new UnsupportedOperationException("Cannot modify empty tile index");
    }
}
