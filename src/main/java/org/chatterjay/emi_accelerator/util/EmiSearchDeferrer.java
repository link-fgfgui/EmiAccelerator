package org.chatterjay.emi_accelerator.util;

import com.mojang.logging.LogUtils;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.registry.EmiStackList;
import dev.emi.emi.screen.EmiScreenManager;
import dev.emi.emi.search.EmiSearch;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.chatterjay.emi_accelerator.config.ModConfig;
import org.slf4j.Logger;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class EmiSearchDeferrer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean pending = new AtomicBoolean(false);
    private static volatile boolean running = false;

    private static final VarHandle SEARCHED_STACKS;

    static {
        try {
            SEARCHED_STACKS = MethodHandles.privateLookupIn(
                    EmiScreenManager.class, MethodHandles.lookup()
            ).findStaticVarHandle(EmiScreenManager.class, "searchedStacks", List.class);
        } catch (IllegalAccessException | NoSuchFieldException e) {
            throw new RuntimeException("Failed to access EmiScreenManager.searchedStacks", e);
        }
    }

    public static void markPending() {
        pending.set(true);
    }

    public static boolean consumePending() {
        return pending.compareAndSet(true, false);
    }

    public static void doDefer() {
        if (running) return;
        running = true;

        int stackCount = EmiStackList.stacks != null ? EmiStackList.stacks.size() : -1;
        LOGGER.debug("[EMI加速] doDefer() stacks={}", stackCount);

        Minecraft.getInstance().execute(() -> {
            LOGGER.info("[EMI加速] Deferred search bake starting");

            int preBakeCount = EmiStackList.stacks != null ? EmiStackList.stacks.size() : -1;
            LOGGER.debug("[EMI加速] preBake stacks count={}", preBakeCount);

            try {
                long start = System.currentTimeMillis();
                EmiSearch.bake();
                long took = System.currentTimeMillis() - start;
                LOGGER.info("[EMI加速] EmiSearch.bake() completed in {}ms", took);

                try {
                    String text = EmiScreenManager.search.getValue();
                    LOGGER.info("[EMI加速] Post-bake sync search + worker reset (search text='{}')", text);

                    // ======== STEP 1: Sync search using bakedStacks ========
                    // CRITICAL: Use EmiSearch.bakedStacks as source, NOT getSearchSource()!
                    // getSearchSource() returns searchedStacks which is stale/empty after ReloadWorker.
                    // bakedStacks was just populated by our EmiSearch.bake() and has the full item list.
                    String sourceDesc = "bakedStacks(" + (EmiSearch.bakedStacks != null ? EmiSearch.bakedStacks.size() : "null") + ")";
                    int syncResultsCount = 0;
                    if (text != null && !text.isEmpty() && EmiSearch.bakedStacks != null && !EmiSearch.bakedStacks.isEmpty()) {
                        EmiSearch.CompiledQuery compiled = new EmiSearch.CompiledQuery(text);
                        if (!compiled.isEmpty()) {
                            List<EmiIngredient> results = new ArrayList<>();
                            for (var stack : EmiSearch.bakedStacks) {
                                List<EmiStack> ess = stack.getEmiStacks();
                                if (ess.size() == 1 && compiled.test(ess.get(0))) {
                                    results.add(stack);
                                }
                            }
                            EmiSearch.stacks = java.util.List.copyOf(results);
                            syncResultsCount = results.size();
                        }
                    }
                    LOGGER.info("[EMI加速] Sync search using {}: {} total results",
                            sourceDesc, syncResultsCount);

                    // ======== STEP 2: Force UI recalculation ========
                    // This triggers recalculate(): searchedStacks = EmiSearch.stacks (our results)
                    EmiScreenManager.updateSearchSidebar();
                    EmiScreenManager.forceRecalculate();
                    LOGGER.info("[EMI加速] After step2 forceRecalculate: searchedStacks synced to EmiSearch.stacks");

                    // ======== STEP 3: Restore searchedStacks to bakedStacks ========
                    // The SearchWorker created by EmiSearch.update() uses getSearchSource() which
                    // returns searchedStacks. We need it to point to bakedStacks (all items)
                    // so the worker can find results for ANY query, not just the current one.
                    SEARCHED_STACKS.set(List.copyOf(EmiSearch.bakedStacks));
                    LOGGER.info("[EMI加速] Step3: restored searchedStacks to bakedStacks ({} items)",
                            EmiSearch.bakedStacks != null ? EmiSearch.bakedStacks.size() : "null");

                    // ======== STEP 4: Start fresh SearchWorker ========
                    // Now currentWorker is set to the new worker, ensuring any stale SearchWorker
                    // (from before bake with empty suffix arrays) will self-abort.
                    // Source = searchedStacks = bakedStacks (2843 items) thanks to step 3.
                    LOGGER.info("[EMI加速] Step4: starting fresh SearchWorker with full bakedStacks source");
                    EmiSearch.update();

                    // ======== STEP 5: Restore immediate display ========
                    // The SearchWorker from step 4 runs async. Set EmiSearch.stacks back to our
                    // sync search results so the user sees results immediately.
                    if (text != null && !text.isEmpty() && EmiSearch.bakedStacks != null && !EmiSearch.bakedStacks.isEmpty()) {
                        EmiSearch.CompiledQuery compiled2 = new EmiSearch.CompiledQuery(text);
                        if (!compiled2.isEmpty()) {
                            List<EmiIngredient> results2 = new ArrayList<>();
                            for (var stack : EmiSearch.bakedStacks) {
                                List<EmiStack> ess = stack.getEmiStacks();
                                if (ess.size() == 1 && compiled2.test(ess.get(0))) {
                                    results2.add(stack);
                                }
                            }
                            EmiSearch.stacks = java.util.List.copyOf(results2);
                            LOGGER.info("[EMI加速] Step5: restored sync results ({} items)", results2.size());
                        }
                    }
                    EmiScreenManager.forceRecalculate();
                    LOGGER.info("[EMI加速] After step5 forceRecalculate");
                } catch (Exception e) {
                    LOGGER.error("[EMI加速] Post-bake search/refresh failed", e);
                }

                // Test search index directly after bake
                if (ModConfig.isDebugEnabled()) {
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

                ChatHelper.sendIfNotHidden(
                        Component.translatable("emi_accelerator.search.ready"));
                LOGGER.info("[EMI加速] Search ready message sent");
            } catch (Exception e) {
                LOGGER.error("[EMI加速] Deferred EmiSearch.bake() failed", e);
            } finally {
                running = false;
                LOGGER.info("[EMI加速] doDefer() bake task done, running=false");
            }
        });
    }

    public static boolean isRunning() {
        return running;
    }
}
