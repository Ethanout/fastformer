package io.github.fastformer.common.geometry.raster.face.policy;

import io.github.fastformer.common.geometry.raster.face.classify.FaceClass;
import java.util.EnumMap;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

/** Separate existing entry routes until shape differences are measured and approved. */
public final class FaceRasterPolicies {
    public static final ResourceLocation LEGACY_QUAD = id("current_quad");
    public static final ResourceLocation LEGACY_POLYGON = id("current_polygon");
    public static final FaceRasterPolicy CURRENT_QUAD = uniform(LEGACY_QUAD);
    public static final FaceRasterPolicy CURRENT_POLYGON = uniform(LEGACY_POLYGON);
    public static final FaceRasterPolicy DEFAULT = defaults();

    private FaceRasterPolicies() {}

    private static FaceRasterPolicy defaults() {
        EnumMap<FaceClass, List<ResourceLocation>> chains = new EnumMap<>(FaceClass.class);
        chains.put(FaceClass.AXIS_RECTANGLE, ids("axis_rectangle", "normal_plane"));
        chains.put(FaceClass.COORDINATE_PLANE, ids("coordinate_plane", "normal_plane"));
        chains.put(FaceClass.AXIS_EXTRUSION, ids("axis_extrusion", "normal_plane"));
        chains.put(FaceClass.TILTED_PARALLELOGRAM, ids("normal_plane", "translated_scan", "edge_only"));
        chains.put(FaceClass.POLYGON, ids("projected_polygon", "edge_only"));
        chains.put(FaceClass.DEGENERATE, ids("edge_only"));
        return new FaceRasterPolicy(chains);
    }

    private static List<ResourceLocation> ids(String... paths) { return java.util.Arrays.stream(paths).map(FaceRasterPolicies::id).toList(); }

    private static FaceRasterPolicy uniform(ResourceLocation algorithm) {
        EnumMap<FaceClass, List<ResourceLocation>> chains = new EnumMap<>(FaceClass.class);
        for (FaceClass kind : FaceClass.values()) chains.put(kind, List.of(algorithm));
        return new FaceRasterPolicy(chains);
    }

    public static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("fastformer", path); }
}
