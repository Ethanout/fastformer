package io.github.fastformer.fastplace.geometry.generation.face.policy;

import io.github.fastformer.fastplace.geometry.generation.face.classify.FaceClass;
import java.util.EnumMap;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

/** Separate existing entry routes until shape differences are measured and approved. */
public final class FaceRasterPolicies {
    public static final ResourceLocation LEGACY_QUAD = id("current_quad");
    public static final ResourceLocation LEGACY_POLYGON = id("current_polygon");
    public static final FaceRasterPolicy CURRENT_QUAD = uniform(LEGACY_QUAD);
    public static final FaceRasterPolicy CURRENT_POLYGON = uniform(LEGACY_POLYGON);

    private FaceRasterPolicies() {}

    private static FaceRasterPolicy uniform(ResourceLocation algorithm) {
        EnumMap<FaceClass, List<ResourceLocation>> chains = new EnumMap<>(FaceClass.class);
        for (FaceClass kind : FaceClass.values()) chains.put(kind, List.of(algorithm));
        return new FaceRasterPolicy(chains);
    }

    public static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("fastformer", path); }
}
