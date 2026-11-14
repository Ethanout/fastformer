package io.github.fastformer.server.mode;

import io.github.fastformer.server.specialitem.SpecialItemHandlers;
import io.github.fastformer.common.interaction.InteractionState;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;

/** Stateless input modes participate only after all active editor modes. */
final class HeldItemServerMode extends ServerMode {
   private final Predicate<ServerPlayer> accepts;

   HeldItemServerMode(String id, InteractionState state, Predicate<ServerPlayer> accepts) {
      super(id, state);
      this.accepts = accepts;
   }

   @Override public boolean active(ServerPlayer player) { return false; }
   @Override public boolean acceptsHeldItem(ServerPlayer player) { return accepts.test(player); }
   @Override public void sync(ServerPlayer player) {}
   @Override public boolean rightClickBlock(ServerPlayer player, BlockHitResult hit) {
      return interactionState() == InteractionState.SPECIAL_ITEM && SpecialItemHandlers.useOnBlock(player, hit.getBlockPos());
   }
}
