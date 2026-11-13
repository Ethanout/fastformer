package io.github.fastformer.common.preview.quickshape;

import net.minecraft.resources.ResourceLocation;

/** Server-selected effect identity used by the client preview renderer. */
public record BuildingPreviewEffectSnapshot(ResourceLocation activeEffect) {
}
