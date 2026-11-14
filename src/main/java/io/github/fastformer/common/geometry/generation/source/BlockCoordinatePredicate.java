package io.github.fastformer.common.geometry.generation.source;

/** Tests integer coordinates without allocating a block position for rejected cells. */
@FunctionalInterface
public interface BlockCoordinatePredicate {
   boolean test(int x, int y, int z);
}
