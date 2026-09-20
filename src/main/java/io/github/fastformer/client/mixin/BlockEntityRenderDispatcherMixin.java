package io.github.fastformer.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.fastformer.client.render.mask.SourceMaskRenderFilter;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the block entity of a masked source position.
 *
 * <p>A section that was built before the mask changed still lists that block entity, and
 * {@code LevelRenderer} renders every listed entity, including the global ones. The check
 * covers that window until the section rebuild removes the entity from the list.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
   @Inject(method = "render", at = @At("HEAD"), cancellable = true)
   private void fastformer$hideMaskedBlockEntity(
      BlockEntity blockEntity,
      float partialTick,
      PoseStack poseStack,
      MultiBufferSource bufferSource,
      CallbackInfo callback
   ) {
      if (blockEntity != null && SourceMaskRenderFilter.instance().hides(blockEntity.getBlockPos())) {
         callback.cancel();
      }
   }
}
