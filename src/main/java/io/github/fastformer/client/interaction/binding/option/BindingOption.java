package io.github.fastformer.client.interaction.binding.option;

import net.minecraft.resources.ResourceLocation;

/** Data-defined client preference, independent of server-side session constraints. */
public record BindingOption(ResourceLocation id, boolean defaultValue) {
   public String translationKey() { return "fastformer.option." + id.getPath().replace('/', '.'); }
}
