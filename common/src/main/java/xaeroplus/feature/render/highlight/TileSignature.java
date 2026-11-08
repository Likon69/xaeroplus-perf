package xaeroplus.feature.render.highlight;

import it.unimi.dsi.fastutil.HashCommon;

final class TileSignature {
    int count;
    long sum;

    void add(long highlight) {
        count++;
        sum += HashCommon.mix(highlight);
    }

    boolean matches(TileSignature other) {
        return other != null && count == other.count && sum == other.sum;
    }
}
