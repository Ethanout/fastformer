package io.github.fastformer.fastplace.placement.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.fastformer.fastplace.interaction.InteractionUpdateScope;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockInteractionMixin {
   @WrapMethod(method = "useWithoutItem")
   private InteractionResult fastformer$use(Level level, Player player, BlockHitResult hit,
                                           Operation<InteractionResult> original) {
      return InteractionUpdateScope.interact(level, player, () -> original.call(level, player, hit));
   }

   @WrapMethod(method = "useItemOn")
   private ItemInteractionResult fastformer$useItem(ItemStack stack, Level level, Player player,
         InteractionHand hand, BlockHitResult hit, Operation<ItemInteractionResult> original) {
      return InteractionUpdateScope.interact(level, player, () -> original.call(stack, level, player, hand, hit));
   }
}
