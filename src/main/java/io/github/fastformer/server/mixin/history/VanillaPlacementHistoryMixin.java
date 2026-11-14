package io.github.fastformer.server.mixin.history;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.fastformer.server.history.vanilla.VanillaBlockHistory;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ItemStack.class)
public abstract class VanillaPlacementHistoryMixin {
   @WrapMethod(method = "useOn")
   private InteractionResult fastformer$recordPlacement(UseOnContext context, Operation<InteractionResult> original) {
      return VanillaBlockHistory.place(context, () -> original.call(context));
   }
}
