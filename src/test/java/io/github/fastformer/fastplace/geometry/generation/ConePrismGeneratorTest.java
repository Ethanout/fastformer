package io.github.fastformer.fastplace.geometry.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.ConePlaneMode;
import io.github.fastformer.fastplace.FillMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ConePrismGeneratorTest {
   private static final Vec3 CENTER = new Vec3(0.5, 0.5, 0.5);

   @Test
   void hollowCylinderIsAProperSubsetOfTheSolid() {
      ConePrismParameters parameters = parameters(0, 4.0, 5.0);

      Set<BlockPos> solid = ConePrismGenerator.generate(parameters, FillMode.SOLID, 10000);
      Set<BlockPos> hollow = ConePrismGenerator.generate(parameters, FillMode.HOLLOW, 10000);

      assertFalse(solid.isEmpty());
      assertTrue(solid.containsAll(hollow));
      assertTrue(hollow.size() < solid.size());
   }

   @Test
   void rowBoundsDoNotClipTheBottomDisk() {
      double radius = 6.0;
      ConePrismParameters parameters = parameters(0, radius, 3.0);
      Set<BlockPos> solid = ConePrismGenerator.generate(parameters, FillMode.SOLID, 10000);
      Set<BlockPos> actualBottom = solid.stream()
         .filter(position -> position.getY() == 0)
         .collect(java.util.stream.Collectors.toSet());
      Set<BlockPos> expectedBottom = new LinkedHashSet<>();
      for (int x = -8; x <= 8; x++) {
         for (int z = -8; z <= 8; z++) {
            if (Math.hypot(x, z) <= radius + 0.25) {
               expectedBottom.add(new BlockPos(x, 0, z));
            }
         }
      }

      assertEquals(expectedBottom, actualBottom);
   }

   @Test
   void coneEstimateCoversTheContinuousCandidateBounds() {
      double radius = 12.0;
      ConePrismParameters parameters = parameters(1, radius, 12.0);
      ConePrismGeometry geometry = ConePrismGeometry.from(parameters);
      Set<BlockPos> solid = ConePrismGenerator.generate(parameters, FillMode.SOLID, 100000);

      assertTrue(geometry != null && geometry.heightReady());
      assertEquals(geometry.voxelBounds().volume(), ConePrismGenerator.estimateScanCells(parameters));
      assertTrue(ConePrismGenerator.estimateScanCells(parameters) >= solid.size());
   }

   @Test
   void largeVolumeUsesLazyStorage() {
      Set<BlockPos> solid = ConePrismGenerator.generate(parameters(0, 20.0, 20.0), FillMode.SOLID, 100_000);

      assertTrue(solid.getClass().getSimpleName().contains("LazyBlockSet"));
      assertEquals(solid.size(), new java.util.HashSet<>(solid).size());
   }

   @Test
   void tiltedHollowMatchesTheSixNeighborBoundary() {
      List<Vec3> face = List.of(
         new Vec3(0.5, 0.5, 0.5),
         new Vec3(6.5, 0.5, 0.5),
         new Vec3(0.5, 6.5, 6.5)
      );
      ConePrismParameters baseParameters = new ConePrismParameters(
         face, Optional.empty(), 0, ConePlaneMode.THREE_POINT, 1.0, 1.0, 1.0, 0.0, Vec3.ZERO, 0.0
      );
      ConePrismGeometry.Base base = ConePrismGenerator.baseInfo(baseParameters);
      assertTrue(base != null);
      ConePrismParameters parameters = new ConePrismParameters(
         face,
         Optional.of(base.center().add(base.normal().scale(7.0))),
         0,
         ConePlaneMode.THREE_POINT,
         base.radius(),
         1.0,
         1.0,
         0.0,
         Vec3.ZERO,
         0.0
      );

      Set<BlockPos> solid = ConePrismGenerator.generate(parameters, FillMode.SOLID, 100000);
      Set<BlockPos> hollow = ConePrismGenerator.generate(parameters, FillMode.HOLLOW, 100000);
      List<BlockPos> neighbors = List.of(
         new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0),
         new BlockPos(0, 1, 0), new BlockPos(0, -1, 0),
         new BlockPos(0, 0, 1), new BlockPos(0, 0, -1)
      );

      assertTrue(solid.containsAll(hollow));
      assertTrue(solid.stream().filter(position ->
         neighbors.stream().anyMatch(offset -> !solid.contains(position.offset(offset)))
      ).allMatch(hollow::contains));
      assertTrue(hollow.stream().allMatch(position ->
         neighbors.stream().anyMatch(offset -> !solid.contains(position.offset(offset)))
      ));
      assertEquals(685, solid.size());
      assertEquals(281, hollow.size());
   }

   @Test
   void placementOutlineReportsLimitWhilePreviewRemainsBounded() {
      ConePrismParameters parameters = parameters(0, 8.0, 10.0);
      Set<BlockPos> complete = ConePrismGenerator.generate(parameters, FillMode.OUTLINE, 10000);

      assertFalse(complete.isEmpty());
      assertEquals(
         complete,
         ConePrismGenerator.generate(parameters, FillMode.OUTLINE, complete.size())
      );
      assertTrue(GenerationLimitExceeded.is(
         ConePrismGenerator.generate(parameters, FillMode.OUTLINE, complete.size() - 1)
      ));

      Set<BlockPos> preview = ConePrismGenerator.previewOutline(parameters, complete.size() - 1);
      assertFalse(GenerationLimitExceeded.is(preview));
      assertTrue(preview.size() <= complete.size() - 1);
   }

   private static ConePrismParameters parameters(int shapeVariant, double radius, double height) {
      return new ConePrismParameters(
         List.of(CENTER, CENTER.add(radius, 0.0, 0.0)),
         Optional.of(CENTER.add(0.0, height, 0.0)),
         shapeVariant,
         ConePlaneMode.RADIUS,
         radius,
         1.0,
         1.0,
         0.0,
         Vec3.ZERO,
         0.0
      );
   }
}
