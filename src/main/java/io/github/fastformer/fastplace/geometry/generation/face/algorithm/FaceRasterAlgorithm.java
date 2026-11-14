package io.github.fastformer.fastplace.geometry.generation.face.algorithm;

import io.github.fastformer.fastplace.geometry.generation.face.FaceRasterResult;
import io.github.fastformer.fastplace.geometry.generation.face.FaceRequest;
import io.github.fastformer.fastplace.geometry.generation.face.classify.FaceShape;
import net.minecraft.resources.ResourceLocation;

/** One registered implementation; routing decisions belong to the dispatcher and policy. */
public interface FaceRasterAlgorithm {
    ResourceLocation id();
    boolean accepts(FaceShape shape);
    FaceRasterResult rasterize(FaceShape shape, FaceRequest request);
}
