package io.github.fastformer.network.payload.preview;

import net.minecraft.resources.ResourceLocation;

/** Server-selected effect identity used by the client preview renderer. */
public record BuildingPreviewEffectSnapshot(ResourceLocation activeEffect) {
}
