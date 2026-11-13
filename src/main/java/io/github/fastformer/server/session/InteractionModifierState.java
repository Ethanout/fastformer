package io.github.fastformer.server.session;

import io.github.fastformer.core.lifecycle.Scoped;
import io.github.fastformer.core.lifecycle.ScopedKey;
import io.github.fastformer.server.player.PlayerStateRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Physical modifier state ends with the current player instance or dimension. */
final class InteractionModifierState implements Scoped {
   private static final ScopedKey<InteractionModifierState> KEY = PlayerStateRegistry.key(
      ResourceLocation.parse("fastformer:interaction_modifier"), InteractionModifierState::new);
   private boolean held;

   static boolean held(ServerPlayer player) {
      return PlayerStateRegistry.find(player).flatMap(scope -> scope.find(KEY)).map(state -> state.held).orElse(false);
   }

   static void set(ServerPlayer player, boolean held) { PlayerStateRegistry.of(player).get(KEY).held = held; }

   static void reset(ServerPlayer player) {
      PlayerStateRegistry.find(player).flatMap(scope -> scope.find(KEY)).ifPresent(state -> state.held = false);
   }

   @Override public void onDimensionChanged() { held = false; }
   @Override public void onDetach() { held = false; }
   @Override public void onDiscard() { held = false; }
}
