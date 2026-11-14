package io.github.fastformer.server.specialitem;

import io.github.fastformer.core.registry.Registry;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/** Item IDs are registered during mod construction and frozen before gameplay. */
public final class SpecialItemRegistry {
   private final Registry<ResourceLocation, SpecialItemHandlers.UseOnBlock> handlers = Registry.create("special items");

   public synchronized void register(ResourceLocation item, SpecialItemHandlers.UseOnBlock handler) {
      handlers.register(item, handler);
   }

   public synchronized void freeze() { handlers.freeze(); }

   public Optional<SpecialItemHandlers.UseOnBlock> handler(ResourceLocation item) { return handlers.get(item); }
}
