package io.github.fastformer.client.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Replays one adjudicated short press through vanilla's complete use implementation. */
@Mixin(Minecraft.class)
public interface MinecraftUseInvoker {
   @Invoker("startUseItem") void fastformer$startUseItem();
}
