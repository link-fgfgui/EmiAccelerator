package org.chatterjay.emi_accelerator.mixin;

import net.minecraft.client.Minecraft;
import org.chatterjay.emi_accelerator.util.EmiSearchDeferrer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {

    @Inject(method = "stop", at = @At("HEAD"))
    private void emiAccelerator$beforeStop(CallbackInfo ci) {
        EmiSearchDeferrer.beginShutdown();
    }
}
