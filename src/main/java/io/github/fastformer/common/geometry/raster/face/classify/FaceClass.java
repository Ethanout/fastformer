package io.github.fastformer.common.geometry.raster.face.classify;

/** Ordered geometric pruning categories, independent of the selected algorithm. */
public enum FaceClass {
    AXIS_RECTANGLE, COORDINATE_PLANE, AXIS_EXTRUSION, TILTED_PARALLELOGRAM, POLYGON, DEGENERATE
}
