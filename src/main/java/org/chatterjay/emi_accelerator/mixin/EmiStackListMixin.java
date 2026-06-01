package org.chatterjay.emi_accelerator.mixin;

import com.mojang.logging.LogUtils;
import dev.emi.emi.registry.EmiStackList;
import org.chatterjay.emi_accelerator.config.ModConfig;
import org.chatterjay.emi_accelerator.util.EmiStackCache;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = EmiStackList.class, remap = false)
public class EmiStackListMixin {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Inject(method = "reload", at = @At("HEAD"), cancellable = true)
    private static void onReloadHead(CallbackInfo ci) {
        if (!ModConfig.isAccelerationEnabled()) {
            LOGGER.info("[EMI加速] EmiStackList.reload() acceleration disabled, proceeding with normal reload");
            return;
        }

        LOGGER.info("[EMI加速] EmiStackList.reload() attempting cache load");
        boolean loaded = EmiStackCache.tryLoad();
        LOGGER.info("[EMI加速] EmiStackList.reload() cache tryLoad={}, stacks.size={}",
                loaded, EmiStackList.stacks != null ? EmiStackList.stacks.size() : "null");

        if (loaded) {
            LOGGER.info("[EMI加速] EmiStackList.reload() CACHE HIT, cancelling reload, {} stacks",
                    EmiStackList.stacks != null ? EmiStackList.stacks.size() : "null");
            EmiStackList.bakeFiltered();
            LOGGER.info("[EMI加速] EmiStackList.bakeFiltered() done, filteredStacks={}",
                    EmiStackList.filteredStacks != null ? EmiStackList.filteredStacks.size() : "null");
            ci.cancel();
        } else {
            LOGGER.info("[EMI加速] EmiStackList.reload() cache miss, proceeding with full reload");
        }
    }

    @Inject(method = "reload", at = @At("RETURN"))
    private static void onReloadReturn(CallbackInfo ci) {
        if (!ModConfig.isAccelerationEnabled()) return;
        LOGGER.info("[EMI加速] EmiStackList.reload() RETURN: triggering async save, stacks.size={}",
                EmiStackList.stacks != null ? EmiStackList.stacks.size() : "null");
        EmiStackCache.saveAsync();
    }
}
