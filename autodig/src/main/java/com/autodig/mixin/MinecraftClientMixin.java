package com.autodig.mixin;

import com.autodig.client.AutoMiner;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
	/** Пока идёт автокопание, ванилла не должна сбрасывать прогресс ломания блока (она делает это каждый тик, если ЛКМ не зажата). */
	@Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
	private void autodig$keepBreaking(boolean breaking, CallbackInfo ci) {
		if (AutoMiner.isActive()) ci.cancel();
	}
}
