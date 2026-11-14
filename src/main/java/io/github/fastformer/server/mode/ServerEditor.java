package io.github.fastformer.server.mode;

import java.util.UUID;
import java.util.Optional;
import io.github.fastformer.core.lifecycle.Scoped;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/** Owns the lifetime of one editor repository, including parked dimension drafts. */
public interface ServerEditor<S> extends Scoped {
   Optional<S> session(ServerPlayer player);
   void activate(ServerPlayer player, S session);
   default boolean active(ServerPlayer player) { return session(player).isPresent(); }
   void cancel(ServerPlayer player);
   void remove(ServerPlayer player);
   void parkDimensionSession(UUID owner, ResourceKey<Level> dimension);
   void restoreDimensionSession(UUID owner, ResourceKey<Level> dimension);
   void sync(ServerPlayer player);
   default void modifierChanged(ServerPlayer player, boolean held) {}
   /** Persistent drafts keep their UUID owner; connection-bound repositories override this. */
   default void detach(ServerPlayer player) {}
   default void dimensionChanged(ServerPlayer player) {}
}
