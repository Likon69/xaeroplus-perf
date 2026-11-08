package xaeroplus.feature.render.highlight;

import com.mojang.blaze3d.buffers.GpuBuffer;

record HighlightTile(GpuBuffer buffer, int indexCount, long revision) {}
