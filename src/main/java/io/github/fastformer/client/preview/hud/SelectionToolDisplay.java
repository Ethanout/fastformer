package io.github.fastformer.client.preview.hud;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Frozen presentation identity; HUD code does not receive the tool's executable strategy. */
public record SelectionToolDisplay(ResourceLocation id, String translationKey) {
   public SelectionToolDisplay {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(translationKey, "translationKey");
   }
}
