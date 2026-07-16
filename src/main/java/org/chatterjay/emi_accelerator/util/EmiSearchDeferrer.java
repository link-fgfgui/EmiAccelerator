package org.chatterjay.emi_accelerator.util;

import com.mojang.logging.LogUtils;
import dev.emi.emi.registry.EmiStackList;
import dev.emi.emi.screen.EmiScreenManager;
import dev.emi.emi.search.EmiSearch;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.chatterjay.emi_accelerator.config.ModConfig;
import org.slf4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class EmiSearchDeferrer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean pending = new AtomicBoolean(false);
    private static final ReentrantReadWriteLock STATE_LOCK = new ReentrantReadWriteLock(true);
    private static final ThreadLocal<Boolean> RELOAD_LOCK_HELD = ThreadLocal.withInitial(() -> false);
    private static final ExecutorService SEARCH_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "EMI Accelerator Search Bake");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final AtomicInteger generation = new AtomicInteger();
    private static volatile boolean stopping = false;

    public static void markPending() {
        pending.set(true);
    }

    public static boolean consumePending() {
        return pending.compareAndSet(true, false);
    }

    public static void beginReload() {
        if (RELOAD_LOCK_HELD.get()) return;
        LOGGER.debug("[EMI加速] Waiting for search bake before EMI reload state mutation");
        STATE_LOCK.writeLock().lock();
        RELOAD_LOCK_HELD.set(true);
        stopping = false;
        pending.set(false);
        int currentGeneration = generation.incrementAndGet();
        LOGGER.debug("[EMI加速] EMI reload generation {} started", currentGeneration);
    }

    public static void endReload() {
        if (!RELOAD_LOCK_HELD.get()) return;
        RELOAD_LOCK_HELD.set(false);
        STATE_LOCK.writeLock().unlock();
        LOGGER.debug("[EMI加速] EMI reload generation {} released", generation.get());
    }

    public static void beginShutdown() {
        stopping = true;
        pending.set(false);
        if (running.get()) {
            LOGGER.info("[EMI加速] Client stopping; waiting for background search bake to finish");
        }
        boolean lockHeld = false;
        try {
            lockHeld = STATE_LOCK.writeLock().tryLock(30, TimeUnit.SECONDS);
            int currentGeneration = generation.incrementAndGet();
            if (lockHeld) {
                LOGGER.debug("[EMI加速] Client stopping invalidated search generation {}", currentGeneration);
            } else {
                LOGGER.warn("[EMI加速] Timed out waiting for search bake during client stop; invalidated generation {}", currentGeneration);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            int currentGeneration = generation.incrementAndGet();
            LOGGER.warn("[EMI加速] Interrupted while waiting for search bake during client stop; invalidated generation {}", currentGeneration);
        } finally {
            if (lockHeld) {
                STATE_LOCK.writeLock().unlock();
            }
        }
    }

    public static void doDefer() {
        if (!running.compareAndSet(false, true)) return;

        int taskGeneration = generation.get();
        int stackCount = EmiStackList.stacks != null ? EmiStackList.stacks.size() : -1;
        LOGGER.debug("[EMI加速] doDefer() generation={}, stacks={}", taskGeneration, stackCount);

        SEARCH_EXECUTOR.execute(() -> {
            boolean finishScheduled = false;
            boolean lockHeld = false;

            LOGGER.info("[EMI加速] Deferred search bake starting on background thread");

            try {
                STATE_LOCK.readLock().lock();
                lockHeld = true;

                if (isTaskInvalid(taskGeneration)) {
                    LOGGER.info("[EMI加速] Skipping stale deferred search bake for generation {}", taskGeneration);
                    return;
                }

                int preBakeCount = EmiStackList.stacks != null ? EmiStackList.stacks.size() : -1;
                LOGGER.debug("[EMI加速] preBake stacks count={}", preBakeCount);

                long start = System.currentTimeMillis();
                EmiSearch.bake();
                long took = System.currentTimeMillis() - start;
                LOGGER.info("[EMI加速] EmiSearch.bake() completed in {}ms", took);

                if (isTaskInvalid(taskGeneration)) {
                    LOGGER.info("[EMI加速] Discarding stale deferred search bake result for generation {}", taskGeneration);
                    return;
                }

                if (ModConfig.isDebugEnabled()) {
                    logDebugSearchHits();
                }

                finishScheduled = true;
                Minecraft.getInstance().execute(() -> finishOnClientThread(taskGeneration));
            } catch (Exception e) {
                LOGGER.error("[EMI加速] Deferred EmiSearch.bake() failed", e);
            } finally {
                if (lockHeld) {
                    STATE_LOCK.readLock().unlock();
                }
                if (!finishScheduled) {
                    running.set(false);
                    LOGGER.info("[EMI加速] doDefer() bake task done, running=false");
                }
            }
        });
    }

    private static void finishOnClientThread(int taskGeneration) {
        try {
            if (isTaskInvalid(taskGeneration)) {
                LOGGER.info("[EMI加速] Skipping stale post-bake search refresh for generation {}", taskGeneration);
                return;
            }

            LOGGER.info("[EMI加速] Post-bake search refresh starting");
            EmiScreenManager.updateSearchSidebar();
            EmiSearch.update();
            EmiScreenManager.forceRecalculate();

            ChatHelper.sendIfNotHidden(Component.translatable("emi_accelerator.search.ready"));
            LOGGER.info("[EMI加速] Search ready message sent");
        } catch (Exception e) {
            LOGGER.error("[EMI加速] Post-bake search/refresh failed", e);
        } finally {
            running.set(false);
            LOGGER.info("[EMI加速] doDefer() bake task done, running=false");
        }
    }

    private static boolean isTaskInvalid(int taskGeneration) {
        return stopping || taskGeneration != generation.get();
    }

    private static void logDebugSearchHits() {
        try {
            int namesFound = EmiSearch.names != null ? EmiSearch.names.search("conduit").size() : -1;
            int tooltipsFound = EmiSearch.tooltips != null ? EmiSearch.tooltips.search("conduit").size() : -1;
            int modsFound = EmiSearch.mods != null ? EmiSearch.mods.search("conduit").size() : -1;
            int aliasesFound = EmiSearch.aliases != null ? EmiSearch.aliases.search("conduit").size() : -1;
            LOGGER.info("[EMI加速] SuffixArray 'conduit' hits: names={}, tooltips={}, mods={}, aliases={}",
                    namesFound, tooltipsFound, modsFound, aliasesFound);
        } catch (Exception e) {
            LOGGER.error("[EMI加速] SuffixArray check failed", e);
        }
    }

    public static boolean isRunning() {
        return running.get();
    }
}
