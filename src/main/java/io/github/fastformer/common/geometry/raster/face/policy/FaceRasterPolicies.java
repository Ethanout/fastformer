package io.github.fastformer.common.geometry.raster.face.policy;

import io.github.fastformer.common.geometry.raster.face.classify.FaceClass;
import java.util.EnumMap;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

/** Each entrance retains its existing shape; configurable alternatives do not change these defaults. */
public final class FaceRasterPolicies {
    public static final ResourceLocation QUAD_ALGORITHM = id("current_quad");
    public static final ResourceLocation POLYGON_ALGORITHM = id("current_polygon");
    public static final FaceRasterPolicy CURRENT_QUAD = uniform(QUAD_ALGORITHM);
    public static final FaceRasterPolicy CURRENT_POLYGON = uniform(POLYGON_ALGORITHM);
    /** Volume faces require the raw normal-plane result, never a generated safety outline. */
    public static final FaceRasterPolicy CURRENT_VOLUME_FACE = uniform(id("normal_plane"));
    /** Explicit classified alternative; neither production entrance adopts it by default. */
    public static final FaceRasterPolicy CLASSIFIED = classified();

    private FaceRasterPolicies() {}

    private static FaceRasterPolicy classified() {
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
