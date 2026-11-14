package io.github.fastformer.common.geometry.raster.face.policy;

import io.github.fastformer.common.geometry.raster.face.algorithm.FaceRasterAlgorithms;
import java.util.Optional;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;

/** Server-owned datapack rules synchronized through the same registry mechanism as session profiles. */
public final class FaceRasterRules {
   public static final ResourceKey<Registry<FaceRasterRule>> REGISTRY = ResourceKey.createRegistryKey(
      FaceRasterPolicies.id("face_raster_policy"));
   private FaceRasterRules() {}

   public static void register(DataPackRegistryEvent.NewRegistry event, FaceRasterAlgorithms algorithms) {
      var codec = FaceRasterRule.codec(algorithms);
      event.dataPackRegistry(REGISTRY, codec, codec);
   }

   public static FaceRasterPolicySet capture(RegistryAccess registries) {
      var registry = registries.registry(REGISTRY).orElse(null);
      if (registry == null) return FaceRasterPolicySet.DEFAULT;
      var shared = policy(registry, "default");
      return new FaceRasterPolicySet(
         policy(registry, "quad").or(() -> shared).orElse(FaceRasterPolicies.CURRENT_QUAD),
         policy(registry, "polygon").or(() -> shared).orElse(FaceRasterPolicies.CURRENT_POLYGON),
         policy(registry, "volume").or(() -> shared).orElse(FaceRasterPolicies.CURRENT_VOLUME_FACE));
   }

   private static Optional<FaceRasterPolicy> policy(Registry<FaceRasterRule> registry, String name) {
      return Optional.ofNullable(registry.get(FaceRasterPolicies.id(name))).flatMap(FaceRasterRule::policy);
   }
}
