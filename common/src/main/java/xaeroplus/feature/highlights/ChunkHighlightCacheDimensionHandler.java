package xaeroplus.feature.highlights;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import it.unimi.dsi.fastutil.longs.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import xaeroplus.XaeroPlus;
import xaeroplus.event.XaeroWorldChangeEvent;
import xaeroplus.util.ChunkUtils;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;

import static xaeroplus.util.ChunkUtils.chunkPosToLong;
import static xaeroplus.util.ChunkUtils.regionCoordToChunkCoord;

public class ChunkHighlightCacheDimensionHandler extends ChunkHighlightBaseCacheHandler {
    private static final long EVICTION_DELAY_MS = 30000;
    private static final int MAX_RETAINED_CHUNKS = 6_000_000;
    private final String name;
    private final ChunkBounds retained = new ChunkBounds();
    private ChunkRect loaded = ChunkRect.EMPTY;
    private long retentionStartMs;
    private final ArrayDeque<PendingHighlightLoad> pendingLoads = new ArrayDeque<>();
    private final LongOpenHashSet suppressedKeys = new LongOpenHashSet();
    @NotNull private final ResourceKey<Level> dimension;
    private int windowRegionX = 0;
    private int windowRegionZ = 0;
    // square centered at windowX, windowZ with size windowSize
    private int windowRegionSize = 0;
    @NotNull private final ChunkHighlightDatabase database;
    @NotNull private final ListeningExecutorService dbExecutor;
    // newly added highlights we need to write back to the database
    // if a highlight is not in this set, we do not write it to the database
    // helps performance at very low zoom levels as most data is old and does not need to be rewritten constantly
    public final LongOpenHashSet staleChunks = new LongOpenHashSet();
    public final LongOpenHashSet staleToRemoveChunks = new LongOpenHashSet();
    ListenableFuture<?> windowMoveFuture = Futures.immediateVoidFuture();

    public ChunkHighlightCacheDimensionHandler(
        String name,
        @NotNull ResourceKey<Level> dimension,
        @NotNull ChunkHighlightDatabase database,
        @NotNull ListeningExecutorService dbExecutor
    ) {
        this.name = name;
        this.dimension = dimension;
        this.database = database;
        this.dbExecutor = dbExecutor;
    }

    @Override
    public String name() {
        return name;
    }

    public void setWindow(int regionX, int regionZ, int regionSize) {
        if (!mc.isSameThread()) {
            throw new RuntimeException("setWindow must be called on the main thread");
        }
        boolean windowChanged = regionX != windowRegionX || regionZ != windowRegionZ || regionSize != windowRegionSize;
        if (windowChanged
            && (!windowMoveFuture.isDone() || !pendingLoads.isEmpty())
            && (regionX != 0 || regionZ != 0 || regionSize != 0) // queue window change if we are clearing it (setting size to 0)
        ) {
            XaeroPlus.LOGGER.debug("Rejecting window move to: [{} {} {}] from: [{} {} {}]", regionX, regionZ, regionSize, windowRegionX, windowRegionZ, windowRegionSize);
            return;
        }
        this.windowRegionX = regionX;
        this.windowRegionZ = regionZ;
        this.windowRegionSize = regionSize;
        if (windowChanged) {
            try {
                windowMoveFuture = moveWindow0(regionX, regionZ, regionSize);
            } catch (final Exception e) {
                XaeroPlus.LOGGER.error("Failed submitting move window task for {} disk cache dimension: {}", database.databaseName, dimension.identifier(), e);
            }
        } else if (windowMoveFuture.isDone() && System.currentTimeMillis() - retentionStartMs >= EVICTION_DELAY_MS) {
            var chunkXMin = regionCoordToChunkCoord(windowRegionX - windowRegionSize);
            var chunkXMax = regionCoordToChunkCoord(windowRegionX + windowRegionSize);
            var chunkZMin = regionCoordToChunkCoord(windowRegionZ - windowRegionSize);
            var chunkZMax = regionCoordToChunkCoord(windowRegionZ + windowRegionSize);
            if (!retained.isWithin(chunkXMin, chunkXMax, chunkZMin, chunkZMax)) {
                windowMoveFuture = flushChunksOutsideWindow(windowRegionX, windowRegionZ, windowRegionSize, false);
            }
        }
    }

    private ListenableFuture<?> moveWindow0(final int windowRegionX, final int windowRegionZ, final int windowRegionSize) {
        ChunkRect window = ChunkRect.ofRegionWindow(
            windowRegionX - windowRegionSize, windowRegionX + windowRegionSize,
            windowRegionZ - windowRegionSize, windowRegionZ + windowRegionSize
        );
        ChunkRect previous = loaded;
        // must collect what to delete before flushOutsideWindowFuture
        // so that we can double check that chunks map doesn't contain any staleToRemove chunks
        var flushToDeleteChunksFuture = flushStaleToRemoveChunks();
        var loadDataFuture = dbExecutor.submit(() -> loadUpdatedWindowFromDatabase(window, previous));
        loaded = loaded.contains(window) ? loaded : window;
        if (!window.minus(previous).isEmpty()) retentionStartMs = System.currentTimeMillis();
        var publishedDataFuture = Futures.transform(loadDataFuture, dataBuf -> {
            if (!dataBuf.isEmpty()) {
                retained.include(window.minX(), window.maxX(), window.minZ(), window.maxZ());
                pendingLoads.addLast(dataBuf);
            }
            return null;
        }, mc);
        var handledDataFuture = Futures.catching(publishedDataFuture, Throwable.class, t -> {
            this.windowRegionX = 0;
            this.windowRegionZ = 0;
            this.windowRegionSize = 0;
            loaded = ChunkRect.EMPTY;
            XaeroPlus.LOGGER.error("Error while moving window for {} disk cache dimension: {}",
                                   database.databaseName,
                                   dimension.identifier(),
                                   t);
            return null;
        }, mc);
        boolean deferrable = windowRegionSize != 0;
        var flushOutsideWindowFuture = flushChunksOutsideWindow(windowRegionX, windowRegionZ, windowRegionSize, deferrable);
        return Futures.allAsList(handledDataFuture, flushToDeleteChunksFuture, flushOutsideWindowFuture);
    }


    private PendingHighlightLoad loadUpdatedWindowFromDatabase(final ChunkRect window, final ChunkRect previous) {
        // load new data
        PendingHighlightLoad dataBuf = new PendingHighlightLoad();
        if (!database.getHighlightsInWindowAndOutsidePrevWindow(
            dimension,
            window, previous,
            (chunkX, chunkZ, foundTime) -> dataBuf.add(chunkPosToLong(chunkX, chunkZ), foundTime)
        )) {
            throw new IllegalStateException("Failed loading highlights window");
        }
        return dataBuf;
    }

    private ListenableFuture<?> flushChunksOutsideWindow(final int windowRegionX, final int windowRegionZ, final int windowRegionSize, final boolean deferrable) {
        if (!mc.isSameThread()) {
            throw new RuntimeException("removeChunksOutsideWindow must be called on the main thread");
        }
        Long2LongMap dataBuf = new Long2LongOpenHashMap(64);
        // write to db and remove data from local cache outside window
        var chunkXMin = regionCoordToChunkCoord(windowRegionX - windowRegionSize);
        var chunkXMax = regionCoordToChunkCoord(windowRegionX + windowRegionSize);
        var chunkZMin = regionCoordToChunkCoord(windowRegionZ - windowRegionSize);
        var chunkZMax = regionCoordToChunkCoord(windowRegionZ + windowRegionSize);
        if (retained.isWithin(chunkXMin, chunkXMax, chunkZMin, chunkZMax)) return Futures.immediateVoidFuture();
        if (deferrable
            && System.currentTimeMillis() - retentionStartMs < EVICTION_DELAY_MS
            && chunks.size() <= MAX_RETAINED_CHUNKS) {
            return Futures.immediateVoidFuture();
        }
        // critical section in mc client tick thread
        // it would have huge benefits if we could optimize this
        // there is no cap on the size of the chunks map
        // so this can require iterating through millions of entries
        index.evictOutside(chunkXMin, chunkXMax, chunkZMin, chunkZMax, pos -> {
            if (staleChunks.contains(pos)) dataBuf.put(pos, chunks.get(pos));
            removeChunk(pos, false);
        });
        chunks.trim(Math.max(defaultCapacity, chunks.size()));
        retained.set(chunkXMin, chunkXMax, chunkZMin, chunkZMax);
        loaded = loaded.intersect(new ChunkRect(chunkXMin, chunkXMax, chunkZMin, chunkZMax));
        retentionStartMs = System.currentTimeMillis();
        if (dataBuf.isEmpty()) return Futures.immediateVoidFuture();
        return dbExecutor.submit(() -> database.insertHighlightList(dataBuf, dimension));
    }

    public ListenableFuture<?> flushStaleToRemoveChunks() {
        if (!mc.isSameThread()) {
            throw new RuntimeException("flushStaleToRemoveChunks must be called on the main thread");
        }
        var dataBuf = collectStaleToRemoveChunks();
        if (dataBuf.isEmpty()) return Futures.immediateVoidFuture();
        try {
            return dbExecutor.submit(() -> database.removeHighlights(dataBuf, dimension));
        } catch (final Exception e) {
            XaeroPlus.LOGGER.error("Failed to execute flush stale to remove chunks task for {} disk cache dimension: {}", database.databaseName, dimension.identifier(), e);
            return Futures.immediateFailedFuture(e);
        }
    }

    private LongCollection collectStaleToRemoveChunks() {
        if (staleToRemoveChunks.isEmpty()) return LongSets.emptySet();
        var chunksToRemove = new LongOpenHashSet(staleToRemoveChunks.size());
        for (var it = staleToRemoveChunks.longIterator(); it.hasNext(); ) {
            var chunkPos = it.nextLong();
            if (!chunks.containsKey(chunkPos)) {
                chunksToRemove.add(chunkPos);
            }
            it.remove();
        }
        staleToRemoveChunks.trim(defaultCapacity);
        return chunksToRemove;
    }

    public Long2LongMap collectStaleHighlightsToWrite() {
        if (!mc.isSameThread()) {
            throw new RuntimeException("collectStaleHighlightsToWrite must be called on the main thread");
        }
        if (staleChunks.isEmpty()) return Long2LongMaps.EMPTY_MAP;
        Long2LongMap chunksToWrite = new Long2LongOpenHashMap(staleChunks.size());
        for (var it = staleChunks.longIterator(); it.hasNext(); ) {
            long chunkPos = it.nextLong();
            long foundTime = chunks.get(chunkPos);
            if (foundTime != chunks.defaultReturnValue()) {
                chunksToWrite.put(chunkPos, foundTime);
            }
            it.remove();
        }
        staleChunks.trim(defaultCapacity);
        return chunksToWrite;
    }

    public ListenableFuture<?> writeDataToDatabase(Long2LongMap toWrite) {
        try {
            return dbExecutor.submit(() -> database.insertHighlightList(toWrite, dimension));
        } catch (final Exception e) {
            XaeroPlus.LOGGER.error("Failed to submit db write task for {} disk cache dimension: {}", database.databaseName, dimension.identifier(), e);
            return Futures.immediateFailedFuture(e);
        }
    }

    // does not remove from local cache
    public ListenableFuture<?> writeStaleHighlightsToDatabase() {
        if (!mc.isSameThread()) {
            throw new RuntimeException("writeStaleHighlightsToDatabase must be called on the main thread");
        }
        var toWrite = collectStaleHighlightsToWrite();
        if (toWrite.isEmpty()) return Futures.immediateVoidFuture();
        return writeDataToDatabase(toWrite);
    }

    @Override
    public void addHighlight(final int x, final int z, final long foundTime) {
        super.addHighlight(x, z, foundTime);
        retained.include(x, z);
        var pos = chunkPosToLong(x, z);
        staleChunks.add(pos);
        staleToRemoveChunks.remove(pos);
        suppressedKeys.remove(pos);
    }

    @Override
    public void addHighlight(final int x, final int z, final long foundTime, final ResourceKey<Level> dimensionId) {
        super.addHighlight(x, z, foundTime, dimensionId);
        var pos = chunkPosToLong(x, z);
        staleChunks.add(pos);
        staleToRemoveChunks.remove(pos);
    }

    @Override
    public void addHighlight(final int x, final int z, final ResourceKey<Level> dimensionId) {
        super.addHighlight(x, z, dimensionId);
        var pos = chunkPosToLong(x, z);
        staleChunks.add(pos);
        staleToRemoveChunks.remove(pos);
    }

    @Override
    public void removeHighlight(final int x, final int z) {
        if (!mc.isSameThread()) {
            throw new RuntimeException("removeHighlight must be called on the main thread!");
        }
        var pos = chunkPosToLong(x, z);
        super.removeHighlight(x, z);
        staleToRemoveChunks.add(pos);
        if (!pendingLoads.isEmpty() || !windowMoveFuture.isDone()) suppressedKeys.add(pos);
    }

    @Override
    public void removeHighlights(final LongCollection toRemove) {
        if (!mc.isSameThread()) {
            throw new RuntimeException("removeHighlights must be called on the main thread!");
        }
        super.removeHighlights(toRemove);
        staleToRemoveChunks.addAll(toRemove);
        if (!pendingLoads.isEmpty() || !windowMoveFuture.isDone()) suppressedKeys.addAll(toRemove);
    }

    @Override
    public CompletableFuture<Long2LongMap> getHighlightsInCustomWindow(final int windowRegionX, final int windowRegionZ, final int windowRegionSize, final ResourceKey<Level> dimension) {
        // stale highlight write is async but our db executor is single threaded so we will always execute after the write task
        return submitTickTask(this::writeStaleHighlightsToDatabase)
            .thenApplyAsync((v) -> {
                int regionXMin = windowRegionX - windowRegionSize;
                int regionZMin = windowRegionZ - windowRegionSize;
                int regionXMax = windowRegionX + windowRegionSize;
                int regionZMax = windowRegionZ + windowRegionSize;
                var resultMap = new Long2LongOpenHashMap(64);
                ListenableFuture<?> dbLoadFuture = dbExecutor.submit(() -> database.getHighlightsInWindow(
                    dimension,
                    regionXMin, regionXMax,
                    regionZMin, regionZMax,
                    (chunkX, chunkZ, foundTime) -> resultMap.put(chunkPosToLong(chunkX, chunkZ), foundTime)
                ));
                try {
                    dbLoadFuture.get();
                    return resultMap;
                } catch (final Exception e) {
                    XaeroPlus.LOGGER.error("Failed to load highlights in custom window for {} disk cache dimension: {}",
                                           database.databaseName,
                                           this.dimension.identifier(),
                                           e);
                }
                return Long2LongMaps.EMPTY_MAP;
            });
    }

    @Override
    public void handleWorldChange(final XaeroWorldChangeEvent event) {}

    @Override
    public void handleTick() {}

    public void integratePendingLoads() {
        if (!mc.isSameThread()) {
            throw new IllegalStateException("integratePendingLoads must be called on the main thread");
        }
        clearSuppressedKeys();
        if (pendingLoads.isEmpty() || HighlightTickBudget.remainingNanos() <= 0) return;
        long started = System.nanoTime();
        long budget = HighlightTickBudget.remainingNanos();
        try {
            while (!pendingLoads.isEmpty() && System.nanoTime() - started < budget) {
                var load = pendingLoads.peekFirst();
                if (!load.hasNext()) {
                    pendingLoads.removeFirst();
                    continue;
                }
                long pos = load.nextKey();
                long foundTime = load.nextValue();
                if (suppressedKeys.contains(pos)) continue;
                putChunk(pos, foundTime);
                retained.include(ChunkUtils.longToChunkX(pos), ChunkUtils.longToChunkZ(pos));
            }
        } finally {
            HighlightTickBudget.consume(System.nanoTime() - started);
        }
        clearSuppressedKeys();
    }

    @Override
    public void onEnable() {}

    @Override
    public void onDisable() {}

    private void clearSuppressedKeys() {
        if (!suppressedKeys.isEmpty() && pendingLoads.isEmpty() && windowMoveFuture.isDone()) {
            suppressedKeys.clear();
            suppressedKeys.trim(defaultCapacity);
        }
    }
}
