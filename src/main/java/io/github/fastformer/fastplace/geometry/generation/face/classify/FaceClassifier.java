package io.github.fastformer.fastplace.geometry.generation.face.classify;

import io.github.fastformer.common.math.BlockQuads;
import io.github.fastformer.core.math.Axis;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Floors once using existing block ownership; every geometric comparison thereafter is exact integer math. */
public final class FaceClassifier {
    private FaceClassifier() {}

    public static FaceShape classify(List<Vec3> vertices) {
        List<BlockPos> corners = vertices.stream().map(BlockPos::containing).toList();
        boolean parallelogram = BlockQuads.isParallelogram(corners);
        Optional<Axis> normal = coordinateNormal(corners);
        if (degenerate(corners)) return shape(FaceClass.DEGENERATE, corners, normal, Optional.empty(), parallelogram);
        if (!parallelogram) return shape(FaceClass.POLYGON, corners, normal, Optional.empty(), false);
        Optional<Axis> first = axialEdge(corners.getFirst(), corners.get(1));
        Optional<Axis> second = axialEdge(corners.getFirst(), corners.get(3));
        if (first.isPresent() && second.isPresent() && first.get() != second.get()) {
            return shape(FaceClass.AXIS_RECTANGLE, corners, normal, first, true);
        }
        if (normal.isPresent()) return shape(FaceClass.COORDINATE_PLANE, corners, normal, Optional.empty(), true);
        Optional<Axis> parallel = first.isPresent() ? first : second;
        return shape(parallel.isPresent() ? FaceClass.AXIS_EXTRUSION : FaceClass.TILTED_PARALLELOGRAM,
                corners, normal, parallel, true);
    }

    private static FaceShape shape(FaceClass kind, List<BlockPos> corners, Optional<Axis> normal,
                                   Optional<Axis> parallel, boolean parallelogram) {
        return new FaceShape(kind, corners, normal, parallel, parallelogram);
    }

    private static Optional<Axis> coordinateNormal(List<BlockPos> points) {
        if (points.isEmpty()) return Optional.empty();
        BlockPos first = points.getFirst();
        if (points.stream().allMatch(point -> point.getX() == first.getX())) return Optional.of(Axis.X);
        if (points.stream().allMatch(point -> point.getY() == first.getY())) return Optional.of(Axis.Y);
        if (points.stream().allMatch(point -> point.getZ() == first.getZ())) return Optional.of(Axis.Z);
        return Optional.empty();
    }

    private static Optional<Axis> axialEdge(BlockPos first, BlockPos second) {
        boolean x = first.getX() != second.getX();
        boolean y = first.getY() != second.getY();
        boolean z = first.getZ() != second.getZ();
        if ((x ? 1 : 0) + (y ? 1 : 0) + (z ? 1 : 0) != 1) return Optional.empty();
        return Optional.of(x ? Axis.X : y ? Axis.Y : Axis.Z);
    }

    private static boolean degenerate(List<BlockPos> points) {
        if (points.size() < 3) return true;
        if (new java.util.HashSet<>(points).size() != points.size()) return true;
        BlockPos origin = points.getFirst();
        BlockPos second = points.stream().filter(point -> !point.equals(origin)).findFirst().orElse(null);
        if (second == null) return true;
        long[] direction = delta(origin, second);
        if (points.stream().allMatch(point -> collinear(direction, delta(origin, point)))) return true;
        BigInteger[] area = {BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO};
        for (int index = 0; index < points.size(); index++) {
            long[] first = delta(origin, points.get(index));
            long[] next = delta(origin, points.get((index + 1) % points.size()));
            for (int axis = 0; axis < 3; axis++) {
                int secondAxis = (axis + 1) % 3;
                int third = (axis + 2) % 3;
                area[axis] = area[axis].add(BigInteger.valueOf(first[secondAxis]).multiply(BigInteger.valueOf(next[third])))
                        .subtract(BigInteger.valueOf(first[third]).multiply(BigInteger.valueOf(next[secondAxis])));
            }
        }
        return java.util.Arrays.stream(area).allMatch(value -> value.signum() == 0);
    }

    private static long[] delta(BlockPos first, BlockPos second) {
        return new long[]{(long)second.getX() - first.getX(), (long)second.getY() - first.getY(), (long)second.getZ() - first.getZ()};
    }

    private static boolean collinear(long[] first, long[] second) {
        for (int index = 0; index < 3; index++) {
            int next = (index + 1) % 3;
            if (!BigInteger.valueOf(first[index]).multiply(BigInteger.valueOf(second[next]))
                    .equals(BigInteger.valueOf(first[next]).multiply(BigInteger.valueOf(second[index])))) return false;
        }
        return true;
    }
}
