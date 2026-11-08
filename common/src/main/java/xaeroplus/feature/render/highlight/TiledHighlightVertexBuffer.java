package xaeroplus.feature.render.highlight;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import xaeroplus.feature.highlights.HighlightTileIndex;
import xaeroplus.feature.render.DrawContext;
import xaeroplus.feature.render.shaders.XaeroPlusShaders;
import xaeroplus.util.ChunkUtils;
import xaeroplus.util.ColorHelper;

import java.util.OptionalInt;

public class TiledHighlightVertexBuffer extends AbstractHighlightVertexBuffer {
    private final Long2ObjectOpenHashMap<HighlightTile> tiles = new Long2ObjectOpenHashMap<>();
    private final LongLinkedOpenHashSet pendingTiles = new LongLinkedOpenHashSet();
    private HighlightTileIndex tileIndex = HighlightTileIndex.EMPTY;
    private long generation = -1;
    private int refreshIntervalMs;

    public void setTileIndex(HighlightTileIndex tileIndex, int refreshIntervalMs) {
        if (this.tileIndex != tileIndex) {
            close();
            this.tileIndex = tileIndex;
            stale = true;
        }
        this.refreshIntervalMs = refreshIntervalMs;
    }

    @Override
    public boolean needsRefresh(DrawContext ctx) {
        return stale || flipped != ctx.worldmap() || uniformBuffer == null || tiles.isEmpty()
            || !pendingTiles.isEmpty()
            || (generation != tileIndex.generation() && System.currentTimeMillis() - lastRefreshed >= refreshIntervalMs);
    }

    @Override
    public void refresh(final DrawContext ctx, final Long2LongMap highlights, final int color) {
        if (highlights.isEmpty() || tileIndex.isEmpty() || ColorHelper.getA(color) == 0.0f) {
            stale = false;
            lastRefreshed = System.currentTimeMillis();
            flipped = ctx.worldmap();
            close();
            return;
        }
        if (flipped != ctx.worldmap()) {
            close();
            flipped = ctx.worldmap();
        }
        if (stale || tiles.isEmpty()
            || (generation != tileIndex.generation() && System.currentTimeMillis() - lastRefreshed >= refreshIntervalMs)) {
            synchronizeTiles();
            generation = tileIndex.generation();
            lastRefreshed = System.currentTimeMillis();
            stale = false;
        }
        if (pendingTiles.isEmpty()) return;
        long started = System.nanoTime();
        long budget = HighlightRenderBudget.remainingNanos();
        boolean built = false;
        try {
            while (!pendingTiles.isEmpty() && (!built || System.nanoTime() - started < budget)) {
                long key = pendingTiles.firstLong();
                var keys = tileIndex.keysOf(key);
                if (keys.isEmpty()) {
                    HighlightTile removed = tiles.remove(key);
                    if (removed != null) removed.buffer().close();
                    pendingTiles.removeFirstLong();
                    continue;
                }
                long revision = tileIndex.revisions().get(key);
                try (var buffer = new ByteBufferBuilder(keys.size() * 4 * 12)) {
                    var builder = new BufferBuilder(buffer, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
                    for (var it = keys.iterator(); it.hasNext(); ) {
                        long highlight = it.nextLong();
                        int chunkPosX = ChunkUtils.longToChunkX(highlight);
                        int chunkPosZ = ChunkUtils.longToChunkZ(highlight);
                        float x1 = chunkPosX;
                        float x2 = chunkPosX + 1;
                        float y1 = flipped ? chunkPosZ + 1 : chunkPosZ;
                        float y2 = flipped ? chunkPosZ : chunkPosZ + 1;
                        builder.addVertex(x1, y2, 0.0F);
                        builder.addVertex(x2, y2, 0.0F);
                        builder.addVertex(x2, y1, 0.0F);
                        builder.addVertex(x1, y1, 0.0F);
                    }
                    try (var meshData = builder.buildOrThrow()) {
                        GpuBuffer gpuBuffer = RenderSystem.getDevice().createBuffer(
                            () -> "Chunk Highlight Tile Buffer", GpuBuffer.USAGE_VERTEX, meshData.vertexBuffer());
                        HighlightTile previous = tiles.put(key, new HighlightTile(gpuBuffer, meshData.drawState().indexCount(), revision));
                        if (previous != null) previous.buffer().close();
                    }
                }
                pendingTiles.removeFirstLong();
                built = true;
            }
        } finally {
            indexCount = 0;
            for (HighlightTile tile : tiles.values()) indexCount = Math.max(indexCount, tile.indexCount());
            HighlightRenderBudget.consume(System.nanoTime() - started);
        }
    }

    private void synchronizeTiles() {
        for (var it = tiles.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            var entry = it.next();
            if (!tileIndex.revisions().containsKey(entry.getLongKey())) {
                entry.getValue().buffer().close();
                pendingTiles.remove(entry.getLongKey());
                it.remove();
            }
        }
        for (var it = Long2LongMaps.fastIterator(tileIndex.revisions()); it.hasNext(); ) {
            var entry = it.next();
            long key = entry.getLongKey();
            HighlightTile tile = tiles.get(key);
            if (tile == null || tile.revision() != entry.getLongValue()) pendingTiles.add(key);
        }
        indexCount = 0;
        for (HighlightTile tile : tiles.values()) indexCount = Math.max(indexCount, tile.indexCount());
    }

    @Override
    public void render(DrawContext ctx, Long2LongMap highlights, int color) {
        if (tiles.isEmpty() || uniformBuffer == null) return;
        var a = ColorHelper.getA(color);
        var r = ColorHelper.getR(color);
        var g = ColorHelper.getG(color);
        var b = ColorHelper.getB(color);
        uniformBuffer.rotate();
        try (var mappedView = RenderSystem.getDevice().createCommandEncoder().mapBuffer(uniformBuffer.currentBuffer(), false, true)) {
            Std140Builder.intoBuffer(mappedView.data())
                .putMat4f(ctx.untranslatedMapViewMatrix())
                .putVec4(new Vector4f(r, g, b, a))
                .putVec2((float) Math.floorDiv(ctx.cameraBlockX(), 16), (float) Math.floorDiv(ctx.cameraBlockZ(), 16))
                .putVec2((float) Math.floorMod(ctx.cameraBlockX(), 16), (float) Math.floorMod(ctx.cameraBlockZ(), 16));
        }
        GpuBufferSlice dynamic = RenderSystem.getDynamicUniforms()
            .writeTransform(RenderSystem.getModelViewMatrix(), new Vector4f(), new Vector3f(), new Matrix4f());
        var autoIndexBuffer = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        var indexBuffer = autoIndexBuffer.getBuffer(indexCount);
        var indexType = autoIndexBuffer.type();
        try (final RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
            .createRenderPass(() -> "XaeroPlus Highlight Vertex Buffer", Minecraft.getInstance().getMainRenderTarget().getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(XaeroPlusShaders.HIGHLIGHT_PIPELINE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", dynamic);
            pass.setUniform("HighlightTransforms", uniformBuffer.currentBuffer());
            pass.setIndexBuffer(indexBuffer, indexType);
            for (HighlightTile tile : tiles.values()) {
                pass.setVertexBuffer(0, tile.buffer());
                pass.drawIndexed(0, 0, tile.indexCount(), 1);
            }
        }
    }

    @Override
    public void close() {
        for (HighlightTile tile : tiles.values()) tile.buffer().close();
        tiles.clear();
        pendingTiles.clear();
        generation = -1;
        indexCount = 0;
        super.close();
    }
}
