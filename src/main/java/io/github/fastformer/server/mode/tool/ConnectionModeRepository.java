package io.github.fastformer.server.mode.tool;

import io.github.fastformer.server.mode.ServerEditor;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/** Adapts state owned and discarded by the player's connection scope. */
public abstract class ConnectionModeRepository<S> implements ServerEditor<S> {
   @Override public final void parkDimensionSession(UUID owner, ResourceKey<Level> dimension) {}
   @Override public final void restoreDimensionSession(UUID owner, ResourceKey<Level> dimension) {}
   @Override public final void detach(ServerPlayer player) { remove(player); }
   @Override public final void dimensionChanged(ServerPlayer player) { remove(player); }
   /** PlayerStateRegistry closes the actual state, including states whose player is offline. */
   @Override public final void onDiscard() {}
}
