package dev.example.chesthighlight.mixin;

import dev.example.chesthighlight.ChestHighlightClient;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Inject(method = "close", at = @At("RETURN"))
	private void chesthighlight$onClose(CallbackInfo ci) {
		ChestHighlightClient.close();
	}
}
