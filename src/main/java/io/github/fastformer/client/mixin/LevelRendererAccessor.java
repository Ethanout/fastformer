package io.github.fastformer.client.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the section grid of the level renderer.
 *
 * <p>{@code LevelRenderer.setSectionDirty} and {@code setBlocksDirty} fail with a null
 * pointer error when the grid is absent. The grid is absent before the first level and
 * during a world switch, so the dirty-mark hook must test it first.
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
   @Accessor("viewArea")
   ViewArea fastformer$viewArea();
}
