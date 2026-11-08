package xaeroplus.feature.highlights;

public final class HighlightTickBudget {
    private static final long BUDGET_NS = 3_000_000;
    private static long remainingNs;

    private HighlightTickBudget() {}

    public static void beginTick() {
        remainingNs = BUDGET_NS;
    }

    public static long remainingNanos() {
        return remainingNs;
    }

    public static void consume(long elapsedNs) {
        remainingNs -= elapsedNs;
    }
}
