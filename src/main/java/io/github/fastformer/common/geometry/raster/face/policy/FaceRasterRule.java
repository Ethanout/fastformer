package io.github.fastformer.common.geometry.raster.face.policy;

import com.google.gson.JsonNull;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import io.github.fastformer.common.geometry.raster.face.algorithm.FaceRasterAlgorithms;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.LoggerFactory;

/** Invalid datapack entries retain an explicit fallback marker for each original entrance. */
public record FaceRasterRule(Optional<FaceRasterPolicy> policy) {
   public FaceRasterRule { Objects.requireNonNull(policy, "policy"); }

   public static Codec<FaceRasterRule> codec(FaceRasterAlgorithms algorithms) {
      Objects.requireNonNull(algorithms, "algorithms");
      return Codec.PASSTHROUGH.xmap(dynamic -> {
         var json = dynamic.convert(JsonOps.INSTANCE).getValue();
         if (json.isJsonNull()) return new FaceRasterRule(Optional.empty());
         try { return new FaceRasterRule(Optional.of(FaceRasterPolicy.parse(json, algorithms))); }
         catch (IllegalArgumentException failure) {
            LoggerFactory.getLogger(FaceRasterRule.class).error("Invalid face raster policy; retaining entrance defaults: {}", failure.getMessage());
            return new FaceRasterRule(Optional.empty());
         }
      }, rule -> new Dynamic<>(JsonOps.INSTANCE, rule.policy().map(policy -> (com.google.gson.JsonElement)policy.encode()).orElse(JsonNull.INSTANCE)));
   }
}
