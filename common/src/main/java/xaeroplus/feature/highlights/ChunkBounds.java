package xaeroplus.feature.highlights;

public final class ChunkBounds {
    private int minX;
    private int maxX;
    private int minZ;
    private int maxZ;
    private boolean empty = true;

    public void include(int x, int z) {
        include(x, x, z, z);
    }

    public void include(int minX, int maxX, int minZ, int maxZ) {
        if (empty) {
            set(minX, maxX, minZ, maxZ);
            return;
        }
        this.minX = Math.min(this.minX, minX);
        this.maxX = Math.max(this.maxX, maxX);
        this.minZ = Math.min(this.minZ, minZ);
        this.maxZ = Math.max(this.maxZ, maxZ);
    }

    public void set(int minX, int maxX, int minZ, int maxZ) {
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
        empty = false;
    }

    public boolean isWithin(int minX, int maxX, int minZ, int maxZ) {
        return empty || (this.minX >= minX && this.maxX <= maxX
            && this.minZ >= minZ && this.maxZ <= maxZ);
    }
}
