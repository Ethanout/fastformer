package io.github.fastformer.fastplace.session;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Keeps one editing session per dimension for a player.
 *
 * <p>A dimension change unbinds the old environment but must not destroy the
 * work inside it. The old session moves here, and the session of the target
 * dimension returns to the active map. Coordinates of one dimension are never
 * offered to another dimension.</p>
 */
public final class DimensionSessionStore<V> {
   private final Map<UUID, Map<ResourceKey<Level>, V>> parked = new HashMap<>();

   /** Stores the session of one dimension for later reuse. */
   public void park(UUID owner, ResourceKey<Level> dimension, V session) {
      if (owner == null || dimension == null || session == null) {
         return;
      }
      this.parked.computeIfAbsent(owner, ignored -> new HashMap<>()).put(dimension, session);
   }

   /** Removes and returns the stored session of one dimension, or {@code null}. */
   public V take(UUID owner, ResourceKey<Level> dimension) {
      if (owner == null || dimension == null) {
         return null;
      }
      Map<ResourceKey<Level>, V> dimensions = this.parked.get(owner);
      if (dimensions == null) {
         return null;
      }
      V session = dimensions.remove(dimension);
      if (dimensions.isEmpty()) {
         this.parked.remove(owner);
      }
      return session;
   }

   /** Returns the stored session of one dimension without removing it. */
   public V peek(UUID owner, ResourceKey<Level> dimension) {
      Map<ResourceKey<Level>, V> dimensions = this.parked.get(owner);
      return dimensions == null ? null : dimensions.get(dimension);
   }

   /** Drops every stored session of a player. */
   public void forget(UUID owner) {
      if (owner != null) {
         this.parked.remove(owner);
      }
   }

   public void clear() {
      this.parked.clear();
   }

   public int dimensionCount(UUID owner) {
      Map<ResourceKey<Level>, V> dimensions = this.parked.get(owner);
      return dimensions == null ? 0 : dimensions.size();
   }
}
