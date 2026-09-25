package io.github.fastformer.client.mixin;

import io.github.fastformer.client.render.mask.SourceMaskRenderFilter;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hides masked source positions from the section builder.
 *
 * <p>{@code SectionCompiler.compile} reads every block of a section through one
 * {@code RenderChunkRegion}. The region binds one immutable mask snapshot on its first
 * lookup and applies it to the whole mesh, so the asynchronous meshing thread never sees two
 * mask revisions in one mesh. A region can wait in the render queue after construction, so
 * binding there can use a mask revision that predates the compile.
 *
 * <p>The returned {@code VOID_AIR} state makes the builder skip the block mesh, the fluid
 * mesh, the visibility graph entry ({@code isSolidRender} is false), and the block entity
 * collection.
 */
@Mixin(RenderChunkRegion.class)
public abstract class RenderChunkRegionMixin {
   @Unique
   private SourceMaskRenderFilter.Snapshot fastformer$maskSnapshot;

   @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
   private void fastformer$hideMaskedBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> callback) {
      if (this.fastformer$hides(pos)) {
         callback.setReturnValue(SourceMaskRenderFilter.maskedBlockState());
      }
   }

   @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
   private void fastformer$hideMaskedFluidState(BlockPos pos, CallbackInfoReturnable<FluidState> callback) {
      if (this.fastformer$hides(pos)) {
         callback.setReturnValue(Fluids.EMPTY.defaultFluidState());
      }
   }

   @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
   private void fastformer$hideMaskedBlockEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> callback) {
      if (this.fastformer$hides(pos)) {
         callback.setReturnValue(null);
      }
   }

   @Unique
   private boolean fastformer$hides(BlockPos pos) {
      SourceMaskRenderFilter.Snapshot snapshot = this.fastformer$maskSnapshot;
      if (snapshot == null) {
         snapshot = SourceMaskRenderFilter.instance().snapshot();
         this.fastformer$maskSnapshot = snapshot;
      }
      return snapshot != null && snapshot.hides(pos.asLong());
   }
}
