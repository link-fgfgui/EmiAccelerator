package org.chatterjay.emi_accelerator.mixin;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import org.chatterjay.emi_accelerator.config.ModConfig;
import org.chatterjay.emi_accelerator.util.ChatHelper;
import org.chatterjay.emi_accelerator.util.EmiSearchDeferrer;
import org.chatterjay.emi_accelerator.util.EmiStackCache;
import org.chatterjay.emi_accelerator.util.ReloadTimer;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "dev.emi.emi.runtime.EmiReloadManager$ReloadWorker", remap = false)
public class EmiReloadWorkerMixin {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Inject(method = "run", at = @At("HEAD"))
    private void onRunHead(CallbackInfo ci) {
        if (!ModConfig.isAccelerationEnabled()) return;
        LOGGER.info("[EMI加速] ReloadWorker.run() starting, deferredSearch={}, cacheEnabled={}",
                ModConfig.isDeferredSearchEnabled(), ModConfig.isCacheEnabled());
        ReloadTimer.startReload();
    }

    @Inject(method = "run", at = @At(value = "INVOKE", target = "dev/emi/emi/registry/EmiRecipes.clear()V", ordinal = 0))
    private void beforeClearRecipes(CallbackInfo ci) {
        if (!ModConfig.isAccelerationEnabled()) return;
        LOGGER.info("[EMI加速] ReloadWorker checkpoint: clear_recipes");
        ReloadTimer.checkpoint("clear_recipes");
    }

    @Inject(method = "run", at = @At(value = "INVOKE", target = "dev/emi/emi/registry/EmiStackList.clear()V", ordinal = 0))
    private void beforeClearStackList(CallbackInfo ci) {
        if (!ModConfig.isAccelerationEnabled()) return;
        LOGGER.info("[EMI加速] ReloadWorker checkpoint: clear_stacklist");
        ReloadTimer.checkpoint("clear_stacklist");
    }

    @Inject(method = "run", at = @At(value = "INVOKE", target = "dev/emi/emi/registry/EmiTags.reload()V", ordinal = 0))
    private void beforeTagsReload(CallbackInfo ci) {
        if (!ModConfig.isAccelerationEnabled()) return;
        LOGGER.info("[EMI加速] ReloadWorker checkpoint: tags_reload");
        ReloadTimer.checkpoint("tags_reload");
    }

    @Inject(method = "run", at = @At(value = "INVOKE", target = "dev/emi/emi/registry/EmiStackList.reload()V", ordinal = 0))
    private void beforeStackListReload(CallbackInfo ci) {
        if (!ModConfig.isAccelerationEnabled()) return;
        LOGGER.info("[EMI加速] ReloadWorker checkpoint: stacklist_reload");
        ReloadTimer.checkpoint("stacklist_reload");
    }

    @Inject(method = "run", at = @At(value = "INVOKE", target = "dev/emi/emi/registry/EmiStackList.bake()V", ordinal = 0))
    private void beforeStackListBake(CallbackInfo ci) {
        if (!ModConfig.isAccelerationEnabled()) return;
        LOGGER.info("[EMI加速] ReloadWorker checkpoint: stacklist_bake");
        ReloadTimer.checkpoint("stacklist_bake");
    }

    @Inject(method = "run", at = @At(value = "INVOKE", target = "dev/emi/emi/registry/EmiRecipes.bake()V", ordinal = 0))
    private void beforeRecipesBake(CallbackInfo ci) {
        if (!ModConfig.isAccelerationEnabled()) return;
        LOGGER.info("[EMI加速] ReloadWorker checkpoint: recipes_bake");
        ReloadTimer.checkpoint("recipes_bake");
    }

    @Inject(method = "run", at = @At(value = "INVOKE", target = "dev/emi/emi/bom/BoM.reload()V", ordinal = 0))
    private void beforeBoMReload(CallbackInfo ci) {
        if (!ModConfig.isAccelerationEnabled()) return;
        LOGGER.info("[EMI加速] ReloadWorker checkpoint: bom_reload");
        ReloadTimer.checkpoint("bom_reload");
    }

    @Inject(method = "run", at = @At(value = "INVOKE", target = "dev/emi/emi/runtime/EmiPersistentData.load()V", ordinal = 0))
    private void beforePersistentDataLoad(CallbackInfo ci) {
        if (!ModConfig.isAccelerationEnabled()) return;
        LOGGER.info("[EMI加速] ReloadWorker checkpoint: persistent_data_load");
        ReloadTimer.checkpoint("persistent_data_load");
    }

    @Inject(method = "run", at = @At(value = "INVOKE", target = "dev/emi/emi/search/EmiSearch.bake()V", ordinal = 0))
    private void beforeSearchBake(CallbackInfo ci) {
        if (!ModConfig.isAccelerationEnabled()) return;
        LOGGER.info("[EMI加速] ReloadWorker checkpoint: search_bake (pre-bake), deferredSearch={}",
                ModConfig.isDeferredSearchEnabled());
        ReloadTimer.checkpoint("search_bake");
        if (ModConfig.isDeferredSearchEnabled()) {
            EmiSearchDeferrer.markPending();
            LOGGER.info("[EMI加速] ReloadWorker called EmiSearchDeferrer.markPending()");
        }
    }

    @Inject(method = "run", at = @At(value = "INVOKE", target = "dev/emi/emi/screen/widget/EmiSearchWidget.update()V", ordinal = 0, shift = At.Shift.AFTER))
    private void afterSearchUpdate(CallbackInfo ci) {
        if (!ModConfig.isAccelerationEnabled()) return;
        LOGGER.info("[EMI加速] ReloadWorker checkpoint: after_search_update");
        ReloadTimer.checkpoint("after_search_update");
    }

    @Inject(method = "run", at = @At(value = "INVOKE", target = "dev/emi/emi/screen/EmiScreenManager.forceRecalculate()V", ordinal = 0, shift = At.Shift.AFTER))
    private void afterForceRecalculate(CallbackInfo ci) {
        if (!ModConfig.isAccelerationEnabled()) return;
        LOGGER.info("[EMI加速] ReloadWorker checkpoint: after_force_recalculate");
        ReloadTimer.checkpoint("after_force_recalculate");
    }

    @Inject(method = "run", at = @At(value = "INVOKE", target = "dev/emi/emi/runtime/EmiReloadLog.bake()V", ordinal = 0, shift = At.Shift.AFTER))
    private void afterReloadLogBake(CallbackInfo ci) {
        if (!ModConfig.isAccelerationEnabled()) return;
        LOGGER.info("[EMI加速] ReloadWorker checkpoint: after_reload_log_bake");
        ReloadTimer.checkpoint("after_reload_log_bake");
    }

    @Inject(method = "run", at = @At("RETURN"))
    private void onRunReturn(CallbackInfo ci) {
        if (!ModConfig.isAccelerationEnabled()) return;

        ReloadTimer.checkpoint("run_return");

        boolean cacheHit = EmiStackCache.wasCacheUsed();
        ReloadTimer.finishReload(cacheHit);
        long totalTime = ReloadTimer.getLastReloadDuration();

        LOGGER.info("[EMI加速] ReloadWorker.run() RETURN: cacheHit={}, totalTime={}ms, deferrerRunning={}, cacheFileExists={}",
                cacheHit, totalTime, EmiSearchDeferrer.isRunning(), EmiStackCache.cacheFileExists());

        if (!cacheHit && !EmiStackCache.cacheFileExists()) {
            LOGGER.info("[EMI加速] ReloadWorker: no cache file and not a cache hit, skipping completion message");
            return;
        }

        Component msg = cacheHit
                ? Component.translatable("emi_accelerator.reload.complete_cached", totalTime)
                : Component.translatable("emi_accelerator.reload.complete_uncached", totalTime);

        if (ModConfig.isDeferredSearchEnabled() && EmiSearchDeferrer.isRunning()) {
            msg = Component.literal("").append(msg)
                    .append(Component.translatable("emi_accelerator.reload.building_suffix"));
            LOGGER.info("[EMI加速] ReloadWorker: deferred search still running, appending suffix message");
        }

        ChatHelper.sendIfNotHidden(msg);
        LOGGER.info("[EMI加速] ReloadWorker: completion message sent");
    }
}
