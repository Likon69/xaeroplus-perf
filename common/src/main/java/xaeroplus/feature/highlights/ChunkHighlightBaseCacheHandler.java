package xaeroplus.feature.highlights;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongCollection;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import static xaeroplus.util.ChunkUtils.chunkPosToLong;

public abstract class ChunkHighlightBaseCacheHandler implements ChunkHighlightCache {
    public int defaultCapacity = 1024;
    public final Long2LongOpenHashMap chunks = new Long2LongOpenHashMap(defaultCapacity);
    public Minecraft mc = Minecraft.getInstance();
    protected final HighlightTileIndex index = new HighlightTileIndex();

    public ChunkHighlightBaseCacheHandler() {
        this.chunks.defaultReturnValue(-1);
    }

    @Override
    public void addHighlight(final int x, final int z) {
        addHighlight(x, z, System.currentTimeMillis());
    }

    @Override
    public void addHighlight(final int x, final int z, final ResourceKey<Level> dimensionId) {
        addHighlight(x, z);
    }

    @Override
    public void addHighlight(final int x, final int z, final long foundTime) {
        if (!mc.isSameThread()) {
            throw new RuntimeException("addHighlight must be called on the main thread!");
        }
        putChunk(chunkPosToLong(x, z), foundTime);
    }

    @Override
    public void addHighlight(final int x, final int z, final long foundTime, final ResourceKey<Level> dimensionId) {
        addHighlight(x, z, foundTime);
    }

    @Override
    public void removeHighlight(final int x, final int z) {
        if (!mc.isSameThread()) {
            throw new RuntimeException("removeHighlight must be called on the main thread!");
        }
        removeChunk(chunkPosToLong(x, z));
    }

    @Override
    public void removeHighlight(final int x, final int z, final ResourceKey<Level> dimensionId) {
        removeHighlight(x, z);
    }

    @Override
    public void removeHighlights(final LongCollection toRemove) {
        if (!mc.isSameThread()) {
            throw new RuntimeException("removeHighlights must be called on the main thread!");
        }
        toRemove.forEach(this::removeChunk);
    }

    @Override
    public void removeHighlights(final LongCollection toRemove, ResourceKey<Level> dimensionId) {
        removeHighlights(toRemove);
    }

    @Override
    public boolean isHighlighted(final int x, final int z, ResourceKey<Level> dimensionId) {
        return isHighlighted(chunkPosToLong(x, z));
    }

    @Override
    public Long2LongMap getCacheMap(final ResourceKey<Level> dimension) {
        return chunks;
    }

    @Override
    public HighlightTileIndex tileIndex(final ResourceKey<Level> dimensionId) {
        return index;
    }

    protected void putChunk(long pos, long value) {
        chunks.put(pos, value);
        index.add(pos);
    }

    protected void removeChunk(long pos) {
        removeChunk(pos, true);
    }

    protected void removeChunk(long pos, boolean updateIndex) {
        chunks.remove(pos);
        if (updateIndex) index.remove(pos);
    }

    protected void clearChunks() {
        chunks.clear();
        index.clear();
        chunks.trim(defaultCapacity);
    }

    public boolean isHighlighted(final long chunkPos) {
        return chunks.containsKey(chunkPos);
    }

    public void replaceState(final Long2LongOpenHashMap state) {
        if (!mc.isSameThread()) {
            throw new RuntimeException("replaceState must be called on the main thread!");
        }
        clearChunks();
        for (var it = state.long2LongEntrySet().fastIterator(); it.hasNext(); ) {
            var entry = it.next();
            putChunk(entry.getLongKey(), entry.getLongValue());
        }
    }

    public void reset() {
        if (!mc.isSameThread()) {
            throw new RuntimeException("reset must be called on the main thread!");
        }
        clearChunks();
    }
}
