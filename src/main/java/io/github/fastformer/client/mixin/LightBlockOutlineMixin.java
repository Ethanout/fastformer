package io.github.fastformer.client.mixin;

import io.github.fastformer.client.interaction.ClientBlockTinker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes an otherwise invisible light cell targetable and outlined during wrench editing. */
@Mixin(LightBlock.class)
public abstract class LightBlockOutlineMixin {
   @Inject(method = "getShape", at = @At("RETURN"), cancellable = true)
   private void fastformer$wrenchOutline(BlockState state, BlockGetter level, BlockPos pos,
      CollisionContext context, CallbackInfoReturnable<VoxelShape> callback) {
      if (context instanceof EntityCollisionContext entity && entity.getEntity() instanceof Player player
         && ClientBlockTinker.available(player)) callback.setReturnValue(Shapes.block());
   }
}
