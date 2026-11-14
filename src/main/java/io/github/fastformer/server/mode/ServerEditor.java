package io.github.fastformer.server.mode;

import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/** Owns the lifetime of one editor repository, including parked dimension drafts. */
public interface ServerEditor {
   boolean active(ServerPlayer player);
   void cancel(ServerPlayer player);
   void remove(ServerPlayer player);
   void parkDimensionSession(UUID owner, ResourceKey<Level> dimension);
   void restoreDimensionSession(UUID owner, ResourceKey<Level> dimension);
   void sync(ServerPlayer player);
   default void modifierChanged(ServerPlayer player, boolean held) {}
}
