package xaeroplus.feature.highlights;

import java.util.ArrayList;
import java.util.List;

import static xaeroplus.util.ChunkUtils.regionCoordToChunkCoord;

public record ChunkRect(int minX, int maxX, int minZ, int maxZ) {
    public static final ChunkRect EMPTY = new ChunkRect(0, -1, 0, -1);

    public static ChunkRect ofRegionWindow(int regionXMin, int regionXMax, int regionZMin, int regionZMax) {
        return new ChunkRect(
            regionCoordToChunkCoord(regionXMin), regionCoordToChunkCoord(regionXMax),
            regionCoordToChunkCoord(regionZMin), regionCoordToChunkCoord(regionZMax)
        );
    }

    public boolean isEmpty() {
        return minX > maxX || minZ > maxZ;
    }

    public boolean contains(ChunkRect other) {
        return other.isEmpty() || (!isEmpty() && minX <= other.minX && maxX >= other.maxX
            && minZ <= other.minZ && maxZ >= other.maxZ);
    }

    public ChunkRect intersect(ChunkRect other) {
        ChunkRect intersection = new ChunkRect(
            Math.max(minX, other.minX), Math.min(maxX, other.maxX),
            Math.max(minZ, other.minZ), Math.min(maxZ, other.maxZ)
        );
        return intersection.isEmpty() ? EMPTY : intersection;
    }

    public List<ChunkRect> minus(ChunkRect previous) {
        if (isEmpty()) return List.of();
        if (previous.isEmpty()) return List.of(this);
        if (maxX < previous.minX || minX > previous.maxX
            || maxZ < previous.minZ || minZ > previous.maxZ) return List.of(this);
        List<ChunkRect> rectangles = new ArrayList<>(4);
        if (minZ < previous.minZ) {
            rectangles.add(new ChunkRect(minX, maxX, minZ, previous.minZ - 1));
        }
        if (maxZ > previous.maxZ) {
            rectangles.add(new ChunkRect(minX, maxX, previous.maxZ + 1, maxZ));
        }
        int overlapMinZ = Math.max(minZ, previous.minZ);
        int overlapMaxZ = Math.min(maxZ, previous.maxZ);
        if (minX < previous.minX) {
            rectangles.add(new ChunkRect(minX, previous.minX - 1, overlapMinZ, overlapMaxZ));
        }
        if (maxX > previous.maxX) {
            rectangles.add(new ChunkRect(previous.maxX + 1, maxX, overlapMinZ, overlapMaxZ));
        }
        return rectangles;
    }
}
