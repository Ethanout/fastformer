package io.github.fastformer.fastplace.geometry.generation.face.algorithm;

import io.github.fastformer.core.registry.Registry;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Assembly-owned algorithm registry; freeze before exposing it to preview or world generation. */
public final class FaceRasterAlgorithms {
    private final Registry<ResourceLocation, FaceRasterAlgorithm> algorithms = Registry.create("face raster algorithms");

    public FaceRasterAlgorithms register(FaceRasterAlgorithm algorithm) {
        Objects.requireNonNull(algorithm, "algorithm");
        algorithms.register(algorithm.id(), algorithm);
        return this;
    }

    public FaceRasterAlgorithm require(ResourceLocation id) {
        return algorithms.get(id).orElseThrow(() -> new IllegalArgumentException("Unknown face raster algorithm: " + id));
    }

    public FaceRasterAlgorithms freeze() { algorithms.freeze(); return this; }
}
