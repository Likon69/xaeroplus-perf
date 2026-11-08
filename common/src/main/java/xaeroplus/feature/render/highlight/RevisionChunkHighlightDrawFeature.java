package xaeroplus.feature.render.highlight;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaeroplus.Globals;
import xaeroplus.feature.highlights.HighlightTileIndex;
import xaeroplus.feature.render.DrawContext;

import java.util.function.IntSupplier;
import java.util.function.Function;

public class RevisionChunkHighlightDrawFeature extends AbstractChunkHighlightDrawFeature {
    private final String id;
    private final DirectChunkHighlightSupplier supplier;
    private final Function<ResourceKey<Level>, HighlightTileIndex> indexFunction;
    private final IntSupplier colorSupplier;
    private final int refreshIntervalMs;

    public RevisionChunkHighlightDrawFeature(String id, TiledHighlightVertexBuffer drawBuffer, DirectChunkHighlightSupplier supplier, Function<ResourceKey<Level>, HighlightTileIndex> indexFunction, IntSupplier colorSupplier, int refreshIntervalMs) {
        super(drawBuffer);
        this.id = id;
        this.supplier = supplier;
        this.indexFunction = indexFunction;
        this.colorSupplier = colorSupplier;
        this.refreshIntervalMs = refreshIntervalMs;
    }

    public int color() {
        return colorSupplier.getAsInt();
    }

    public Long2LongMap chunkHighlights() {
        return supplier.getHighlights(Globals.getCurrentDimensionId());
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public void preRender(final DrawContext ctx) {
        super.preRender(ctx);
        ResourceKey<Level> dimension = Globals.getCurrentDimensionId();
        Long2LongMap highlights = supplier.getHighlights(dimension);
        ((TiledHighlightVertexBuffer) drawBuffer).setTileIndex(indexFunction.apply(dimension), refreshIntervalMs);
        drawBuffer.preRender(ctx, highlights, color());
    }

    @Override
    public void render(final DrawContext ctx) {
        preRender(ctx);
        drawBuffer.render(ctx, chunkHighlights(), color());
        postRender(ctx);
    }
}
