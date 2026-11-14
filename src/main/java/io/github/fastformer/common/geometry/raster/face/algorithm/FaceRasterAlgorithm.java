package io.github.fastformer.common.geometry.raster.face.algorithm;

import io.github.fastformer.common.geometry.raster.face.FaceRasterResult;
import io.github.fastformer.common.geometry.raster.face.FaceRequest;
import io.github.fastformer.common.geometry.raster.face.classify.FaceShape;
import net.minecraft.resources.ResourceLocation;

/** One registered implementation; routing decisions belong to the dispatcher and policy. */
public interface FaceRasterAlgorithm {
    ResourceLocation id();
    boolean accepts(FaceShape shape);
    FaceRasterResult rasterize(FaceShape shape, FaceRequest request);
}
