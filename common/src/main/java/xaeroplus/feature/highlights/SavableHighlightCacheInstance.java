package xaeroplus.feature.highlights;

import net.lenni0451.lambdaevents.EventHandler;
import xaeroplus.XaeroPlus;
import xaeroplus.event.ClientTickEvent;
import xaeroplus.event.XaeroWorldChangeEvent;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class SavableHighlightCacheInstance {
    private static final Set<SavableHighlightCacheInstance> INSTANCES = ConcurrentHashMap.newKeySet();
    private ChunkHighlightCache cache;
    private final String name;
    private boolean followsPlayer;

    public SavableHighlightCacheInstance(String name) {
        this.name = name;
        // always starts as a local cache
        // to switch to disk cache call setDiskCache
        this.cache = new ChunkHighlightLocalCache(name);
    }

    public static Set<SavableHighlightCacheInstance> instances() {
        return INSTANCES;
    }

    public ChunkHighlightCache get() {
        return cache;
    }

    public String getName() {
        return name;
    }

    /**
     * These must be called when the owning module is enabled/disabled
     */

    public synchronized void onEnable() {
        XaeroPlus.EVENT_BUS.register(this);
        cache.onEnable();
        INSTANCES.add(this);
    }

    public synchronized void onDisable() {
        cache.onDisable();
        XaeroPlus.EVENT_BUS.unregister(this);
        INSTANCES.remove(this);
    }

    public synchronized void setFollowsPlayer(boolean followsPlayer) {
        this.followsPlayer = followsPlayer;
        if (cache instanceof ChunkHighlightSavingCache savingCache) {
            savingCache.setFollowsPlayer(followsPlayer);
        }
    }

    public synchronized void setDiskCache(final boolean disk, final boolean enabled) {
        try {
            cache.onDisable();
            if (cache instanceof ChunkHighlightSavingCache savingCache) {
                savingCache.close();
            }
            if (disk) {
                var savingCache = new ChunkHighlightSavingCache(name);
                savingCache.setFollowsPlayer(followsPlayer);
                cache = savingCache;
            } else {
                cache = new ChunkHighlightLocalCache(name);
            }
            if (enabled) {
                cache.onEnable();
            }
        } catch (final Exception e) {
            XaeroPlus.LOGGER.error("Error setting {} cache [{} {}]", name, disk, enabled, e);
        }
    }

    @EventHandler
    public void onXaeroWorldChange(XaeroWorldChangeEvent event) {
        try {
            cache.handleWorldChange(event);
        } catch (final Exception e) {
            XaeroPlus.LOGGER.error("Error handling world change event for cache: {} event: {}", name, event, e);
        }
    }

    @EventHandler
    public void onClientTickEvent(ClientTickEvent.Post event) {
        try {
            cache.handleTick();
        } catch (final Exception e) {
            XaeroPlus.LOGGER.error("Error handling tick event for cache: {}", name, e);
        }
    }

    public CompletableFuture<Void> shutdown() {
        if (!INSTANCES.contains(this)) return CompletableFuture.completedFuture(null);
        XaeroPlus.EVENT_BUS.unregister(this);
        INSTANCES.remove(this);
        if (cache instanceof ChunkHighlightSavingCache savingCache) {
            return savingCache.onShutdown();
        }
        return CompletableFuture.completedFuture(null);
    }
}
