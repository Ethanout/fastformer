package io.github.fastformer.server.mixin.history;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.fastformer.server.interaction.ServerInteractionUpdates;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ServerPlayerGameMode.class)
public abstract class ServerBlockHistoryMixin {
   @Shadow protected ServerLevel level;
   @Shadow @Final protected ServerPlayer player;

   @WrapMethod(method = "destroyBlock")
   private boolean fastformer$breakWithUpdatePolicy(BlockPos pos, Operation<Boolean> original) {
      return io.github.fastformer.server.history.vanilla.VanillaBlockHistory.breakBlock(player,
         () -> ServerInteractionUpdates.interact(level, player, () -> original.call(pos)));
   }

   @WrapMethod(method = "useItem")
   private InteractionResult fastformer$recordWaterSurfacePlacement(ServerPlayer player, Level level, ItemStack stack,
      InteractionHand hand, Operation<InteractionResult> original) {
      return io.github.fastformer.server.history.vanilla.VanillaBlockHistory.useItem(player, stack,
         () -> original.call(player, level, stack, hand));
   }
}
