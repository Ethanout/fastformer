package io.github.fastformer.fastplace.placement.mixin;

import io.github.fastformer.fastplace.interaction.InteractionUpdateScope;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** These creative cleanup callbacks write the partner directly, outside neighbor updates. */
@Mixin({DoorBlock.class, DoublePlantBlock.class, BedBlock.class, PistonHeadBlock.class})
public abstract class ConnectedBlockBreakMixin extends Block {
   protected ConnectedBlockBreakMixin(Properties properties) {
      super(properties);
   }

   @Inject(method = "playerWillDestroy", at = @At("HEAD"), cancellable = true)
   private void fastformer$keepUnclickedPart(Level level, BlockPos pos, BlockState state, Player player,
      CallbackInfoReturnable<BlockState> callback) {
      if (InteractionUpdateScope.suppresses(level, player)) {
         callback.setReturnValue(super.playerWillDestroy(level, pos, state, player));
      }
   }
}
