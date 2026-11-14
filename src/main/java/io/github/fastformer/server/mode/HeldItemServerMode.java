package io.github.fastformer.server.mode;

import io.github.fastformer.server.specialitem.SpecialItemHandlers;
import io.github.fastformer.common.interaction.InteractionState;
import io.github.fastformer.common.mode.ModeId;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;

/** Stateless input modes participate only after all active editor modes. */
final class HeldItemServerMode extends ServerMode<ServerPlayer> {
   private final Predicate<ServerPlayer> accepts;

   HeldItemServerMode(ModeId id, InteractionState state, Predicate<ServerPlayer> accepts) {
      super(id, state, new Environment());
      this.accepts = accepts;
   }

   @Override protected boolean activeSession(ServerPlayer player) { return false; }
   @Override public boolean acceptsHeldItem(ServerPlayer player) { return accepts.test(player); }
   @Override protected boolean onRightClickBlock(ServerPlayer player, ServerPlayer environment, BlockHitResult hit) {
      return interactionState() == InteractionState.SPECIAL_ITEM && SpecialItemHandlers.useOnBlock(player, hit.getBlockPos());
   }

   /** The player is the current environment; this repository owns no retained draft. */
   private static final class Environment implements ServerEditor<ServerPlayer> {
      @Override public java.util.Optional<ServerPlayer> session(ServerPlayer player) { return java.util.Optional.ofNullable(player); }
      @Override public void activate(ServerPlayer player, ServerPlayer environment) {
         if (player != environment) throw new IllegalArgumentException("Held-item environment must be the current player");
      }
      @Override public void cancel(ServerPlayer player) {}
      @Override public void remove(ServerPlayer player) {}
      @Override public void parkDimensionSession(java.util.UUID owner, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {}
      @Override public void restoreDimensionSession(java.util.UUID owner, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {}
      @Override public void sync(ServerPlayer player) {}
      @Override public void onDiscard() {}
   }
}
