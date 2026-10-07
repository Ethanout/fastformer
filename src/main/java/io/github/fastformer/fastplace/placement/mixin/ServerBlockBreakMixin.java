package io.github.fastformer.fastplace.placement.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.fastformer.fastplace.interaction.InteractionUpdateScope;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ServerPlayerGameMode.class)
public abstract class ServerBlockBreakMixin {
   @Shadow protected ServerLevel level;
   @Shadow @Final protected ServerPlayer player;

   @WrapMethod(method = "destroyBlock")
   private boolean fastformer$breakWithUpdatePolicy(BlockPos pos, Operation<Boolean> original) {
      return InteractionUpdateScope.interact(level, player, () -> original.call(pos));
   }
}
