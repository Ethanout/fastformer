package io.github.fastformer.common.mode;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Stable mode identity, independent of display text and runtime classification. */
public record ModeId(ResourceLocation value) {
   public ModeId { Objects.requireNonNull(value, "value"); }
   public static ModeId of(String path) {
      return new ModeId(ResourceLocation.fromNamespaceAndPath("fastformer", path));
   }
}
