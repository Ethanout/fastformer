package io.github.fastformer.fastplace.geometry.generation.face.policy;

import io.github.fastformer.fastplace.geometry.generation.face.algorithm.FaceRasterAlgorithms;
import io.github.fastformer.fastplace.geometry.generation.face.classify.FaceClass;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/** Complete immutable chain table; invalid policy data fails before a generation request starts. */
public record FaceRasterPolicy(Map<FaceClass, List<ResourceLocation>> chains) {
    public FaceRasterPolicy {
        EnumMap<FaceClass, List<ResourceLocation>> copy = new EnumMap<>(FaceClass.class);
        for (FaceClass kind : FaceClass.values()) {
            List<ResourceLocation> chain = chains.get(kind);
            if (chain == null || chain.isEmpty()) throw new IllegalArgumentException("Missing face algorithm chain: " + kind);
            copy.put(kind, List.copyOf(chain));
        }
        chains = Collections.unmodifiableMap(copy);
    }

    public List<ResourceLocation> chain(FaceClass kind) { return chains.get(kind); }

    public void validate(FaceRasterAlgorithms algorithms) {
        chains.values().forEach(chain -> chain.forEach(algorithms::require));
    }
}
