package xaeroplus.feature.render.highlight;

public final class HighlightRenderBudget {
    private static final long BUDGET_NS = 3_000_000;
    private static long remainingNs;

    private HighlightRenderBudget() {}

    public static void beginFrame() {
        remainingNs = BUDGET_NS;
    }

    public static long remainingNanos() {
        return remainingNs;
    }

    public static void consume(long elapsedNs) {
        remainingNs -= elapsedNs;
    }
}
