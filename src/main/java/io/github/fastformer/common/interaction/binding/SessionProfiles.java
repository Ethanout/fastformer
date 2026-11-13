package io.github.fastformer.common.interaction.binding;

import java.util.Optional;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;

/** Synchronized datapack registry; clients request IDs, never arbitrary constraint values. */
public final class SessionProfiles {
   public static final ResourceLocation NONE = ResourceLocation.fromNamespaceAndPath("fastformer", "none");
   public static final ResourceKey<Registry<SessionProfile>> REGISTRY = ResourceKey.createRegistryKey(
      ResourceLocation.fromNamespaceAndPath("fastformer", "session_profile"));
   private SessionProfiles() {}

   public static void register(DataPackRegistryEvent.NewRegistry event) {
      event.dataPackRegistry(REGISTRY, SessionProfile.CODEC, SessionProfile.CODEC);
   }

   public static Optional<SessionProfile> find(RegistryAccess registries, ResourceLocation id) {
      if (NONE.equals(id)) return Optional.of(SessionProfile.NONE);
      return registries.registry(REGISTRY).flatMap(registry -> Optional.ofNullable(registry.get(id)));
   }
}
