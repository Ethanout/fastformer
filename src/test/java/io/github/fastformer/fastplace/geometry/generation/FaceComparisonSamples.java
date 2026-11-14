package io.github.fastformer.fastplace.geometry.generation;

import io.github.fastformer.common.geometry.raster.face.classify.FaceClass;
import io.github.fastformer.common.geometry.raster.face.classify.FaceClassifier;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Reproducible stratified inputs; no shape is filtered according to algorithm success. */
final class FaceComparisonSamples {
    static final long SEED = 202611140106L;
    record Sample(int id, String group, List<BlockPos> corners) {
        List<Vec3> vertices() { return corners.stream().map(Vec3::atCenterOf).toList(); }
    }

    private final Random random = new Random(SEED);

    Sample next(int id, boolean parallelogram) {
        // Stratify requested length instead of accidentally sampling mostly short or large faces.
        int firstLength = 1 + id % 128;
        int secondLength = 1 + (id * 37 + 19) % 128;
        while (true) {
            BlockPos first = vector(Math.max(3, firstLength));
            BlockPos second = vector(Math.max(3, secondLength));
            BlockPos origin = new BlockPos(random.nextInt(513) - 256, random.nextInt(257) - 128, random.nextInt(513) - 256);
            List<BlockPos> corners;
            if (parallelogram) corners = List.of(origin, origin.offset(first), origin.offset(first).offset(second), origin.offset(second));
            else {
                // 0, 2u, u+v, v is a planar trapezoid in any integer plane, with unequal opposite edges.
                BlockPos u = vector(Math.max(1, firstLength / 2));
                BlockPos v = vector(secondLength);
                corners = List.of(origin, origin.offset(u).offset(u), origin.offset(u).offset(v), origin.offset(v));
            }
            FaceClass kind = FaceClassifier.classify(corners.stream().map(Vec3::atCenterOf).toList()).kind();
            if (parallelogram && kind != FaceClass.TILTED_PARALLELOGRAM) continue;
            if (!parallelogram && kind != FaceClass.POLYGON) continue;
            if (!validLengths(corners)) continue;
            return new Sample(id, parallelogram ? "tilted_parallelogram" : "non_parallelogram_quad", corners);
        }
    }

    private BlockPos vector(int length) {
        double x = random.nextGaussian();
        double y = random.nextGaussian();
        double z = random.nextGaussian();
        double scale = Math.max(1, length - 1) / Math.sqrt(x * x + y * y + z * z);
        BlockPos value = new BlockPos((int)Math.rint(x * scale), (int)Math.rint(y * scale), (int)Math.rint(z * scale));
        return value.equals(BlockPos.ZERO) ? new BlockPos(1, 0, 0) : value;
    }

    private static boolean validLengths(List<BlockPos> corners) {
        for (int index = 0; index < corners.size(); index++) {
            BlockPos a = corners.get(index);
            BlockPos b = corners.get((index + 1) % corners.size());
            long x = (long)a.getX() - b.getX();
            long y = (long)a.getY() - b.getY();
            long z = (long)a.getZ() - b.getZ();
            long length = x * x + y * y + z * z;
            if (length < 1 || length > 128L * 128L) return false;
        }
        return true;
    }
}
