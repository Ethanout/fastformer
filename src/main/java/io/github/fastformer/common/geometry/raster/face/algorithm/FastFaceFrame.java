package io.github.fastformer.common.geometry.raster.face.algorithm;

import io.github.fastformer.common.geometry.raster.face.FaceRequest;
import java.util.List;
import net.minecraft.core.BlockPos;

/** Existing canonical projection facts; generation and its budgets remain owned by the selected algorithm. */
public interface FastFaceFrame {
    record Cell(int u, int v, int height) {}
    List<BlockPos> corners();
    Cell project(BlockPos block);
    BlockPos restore(int u, int v, int height);

    @FunctionalInterface
    interface Factory { FastFaceFrame create(FaceRequest request); }
}
