package io.github.fastformer.fastplace.history;

import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.fastplace.world.BlockEditTransaction;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;

/** Records ordinary creative placement and breaking through the shared transaction and compression paths. */
public final class VanillaBlockHistory {
   private VanillaBlockHistory() { }

   public static boolean enabled(ServerPlayer player) {
      var settings = FastPlaceSettings.load(player);
      return player.isCreative() && settings.enabled() && settings.vanillaBlockHistory();
   }

   public static InteractionResult place(UseOnContext context, Supplier<InteractionResult> action) {
      if (!(context.getPlayer() instanceof ServerPlayer player)
         || !(context.getItemInHand().getItem() instanceof BlockItem) || !enabled(player)) return action.get();
      return BlockEditTransaction.observe(player, action, InteractionResult.FAIL);
   }

   public static boolean breakBlock(ServerPlayer player, Supplier<Boolean> action) {
      if (!enabled(player)) return action.get();
      return BlockEditTransaction.observe(player, action, false);
   }

   /** Water-surface block items (for example lily pads) place through useItem rather than useOn. */
   public static InteractionResult useItem(ServerPlayer player, ItemStack stack, Supplier<InteractionResult> action) {
      if (!(stack.getItem() instanceof BlockItem) || !enabled(player)) return action.get();
      return BlockEditTransaction.observe(player, action, InteractionResult.FAIL);
   }
}
