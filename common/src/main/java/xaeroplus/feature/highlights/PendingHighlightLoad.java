package xaeroplus.feature.highlights;

import it.unimi.dsi.fastutil.longs.LongArrayList;

final class PendingHighlightLoad {
    private final LongArrayList keys = new LongArrayList();
    private final LongArrayList values = new LongArrayList();
    private int cursor;

    void add(long key, long value) {
        keys.add(key);
        values.add(value);
    }

    boolean isEmpty() {
        return keys.isEmpty();
    }

    boolean hasNext() {
        return cursor < keys.size();
    }

    long nextKey() {
        return keys.getLong(cursor);
    }

    long nextValue() {
        return values.getLong(cursor++);
    }
}
