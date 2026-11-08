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
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import xaeroplus.feature.render.DrawContext;
import xaeroplus.feature.render.shaders.XaeroPlusShaders;
import xaeroplus.util.ChunkUtils;
import xaeroplus.util.ColorHelper;

import java.util.OptionalInt;

public class TiledHighlightVertexBuffer extends AbstractHighlightVertexBuffer {
    private static final int TILE_SHIFT = 9;
    private final Long2ObjectOpenHashMap<HighlightTile> tiles = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<TileSignature> signatures = new Long2ObjectOpenHashMap<>();

    @Override
    public boolean needsRefresh(DrawContext ctx) {
        return stale || flipped != ctx.worldmap() || uniformBuffer == null || tiles.isEmpty();
    }

    @Override
    public void refresh(final DrawContext ctx, final Long2LongMap highlights, final int color) {
        lastRefreshed = System.currentTimeMillis();
        if (flipped != ctx.worldmap()) signatures.clear();
        flipped = ctx.worldmap();
        if (highlights.isEmpty() || ColorHelper.getA(color) == 0.0f) {
            stale = false;
            close();
            return;
        }
        stale = true;
        var currentSignatures = new Long2ObjectOpenHashMap<TileSignature>();
        for (var it = highlights.keySet().iterator(); it.hasNext(); ) {
            long highlight = it.nextLong();
            long key = tileKey(highlight);
            TileSignature signature = currentSignatures.get(key);
            if (signature == null) {
                signature = new TileSignature();
                currentSignatures.put(key, signature);
            }
            signature.add(highlight);
        }
        for (var it = tiles.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            var entry = it.next();
            if (!currentSignatures.containsKey(entry.getLongKey())) {
                entry.getValue().buffer().close();
                it.remove();
            }
        }
        var buffers = new Long2ObjectOpenHashMap<ByteBufferBuilder>();
        var builders = new Long2ObjectOpenHashMap<BufferBuilder>();
        try {
            for (var it = currentSignatures.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
                var entry = it.next();
                long key = entry.getLongKey();
                TileSignature signature = entry.getValue();
                if (!signature.matches(signatures.get(key))) {
                    var buffer = new ByteBufferBuilder(signature.count * 4 * 12);
                    buffers.put(key, buffer);
                    builders.put(key, new BufferBuilder(buffer, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION));
                }
            }
            if (!builders.isEmpty()) {
                for (var it = highlights.keySet().iterator(); it.hasNext(); ) {
                    long highlight = it.nextLong();
                    BufferBuilder builder = builders.get(tileKey(highlight));
                    if (builder == null) continue;
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
                for (var it = builders.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
                    var entry = it.next();
                    long key = entry.getLongKey();
                    try (var buffer = buffers.remove(key); var meshData = entry.getValue().buildOrThrow()) {
                        GpuBuffer gpuBuffer = RenderSystem.getDevice().createBuffer(
                            () -> "Chunk Highlight Tile Buffer", GpuBuffer.USAGE_VERTEX, meshData.vertexBuffer());
                        HighlightTile previous = tiles.put(key, new HighlightTile(gpuBuffer, meshData.drawState().indexCount()));
                        if (previous != null) previous.buffer().close();
                    }
                }
            }
        } finally {
            for (ByteBufferBuilder buffer : buffers.values()) buffer.close();
        }
        signatures.clear();
        signatures.putAll(currentSignatures);
        indexCount = 0;
        for (HighlightTile tile : tiles.values()) indexCount = Math.max(indexCount, tile.indexCount());
        stale = false;
    }

    private long tileKey(long highlight) {
        return ChunkUtils.chunkPosToLong(
            ChunkUtils.longToChunkX(highlight) >> TILE_SHIFT,
            ChunkUtils.longToChunkZ(highlight) >> TILE_SHIFT
        );
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
        signatures.clear();
        indexCount = 0;
        super.close();
    }
}
