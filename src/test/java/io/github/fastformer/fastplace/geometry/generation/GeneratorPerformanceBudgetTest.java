package io.github.fastformer.fastplace.geometry.generation;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.ConePlaneMode;
import io.github.fastformer.fastplace.FillMode;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class GeneratorPerformanceBudgetTest {
   private static final Duration BUDGET = Duration.ofSeconds(15);

   @Test
   void shapeSpecificGeneratorsStayWithinInteractivePreparationBudget() {
      assertTimeoutPreemptively(BUDGET, () -> {
         PolyhedronParameters sphere = polyhedron(4, 32.0);
         Set<BlockPos> result = SphereGenerator.generate(sphere, FillMode.HOLLOW, 500000);
         assertTrue(result.size() < 500000);
         assertTrue(SphereGenerator.estimateScanCells(sphere) < PolyhedronGeometry.bounds(sphere, false).volume());
      });

      assertTimeoutPreemptively(BUDGET, () -> {
         PolyhedronParameters convex = polyhedron(1, 32.0);
         Set<BlockPos> result = ConvexPolyhedronGenerator.generate(convex, FillMode.SOLID, 500000);
         assertTrue(result.size() < 500000);
         assertTrue(ConvexPolyhedronGenerator.estimateScanCells(convex) < PolyhedronGeometry.bounds(convex, true).volume());
      });

      assertTimeoutPreemptively(BUDGET, () -> {
         Vec3 center = new Vec3(0.5, 0.5, 0.5);
         ConePrismParameters cone = new ConePrismParameters(
            List.of(center, center.add(24.0, 0.0, 0.0)),
            Optional.of(center.add(0.0, 32.0, 0.0)),
            1,
            ConePlaneMode.RADIUS,
            24.0,
            1.0,
            1.0,
            0.0,
            Vec3.ZERO,
            0.0
         );
         assertTrue(ConePrismGenerator.generate(cone, FillMode.HOLLOW, 500000).size() < 500000);
      });

      assertTimeoutPreemptively(BUDGET, () -> {
         List<Vec3> hull = List.of(
            center(0, 0, 0), center(40, 0, 0), center(0, 40, 0), center(40, 40, 0),
            center(0, 0, 40), center(40, 0, 40), center(0, 40, 40), center(40, 40, 40)
         );
         assertTrue(ArbitraryConvexPolyhedronGenerator.generate(hull, FillMode.HOLLOW, 500000).size() < 500000);
         assertTrue(ArbitraryConvexPolyhedronGenerator.estimateScanCells(hull) < 41L * 41L * 41L);
      });

      assertTimeoutPreemptively(BUDGET, () -> {
         Vec3 first = center(0, 0, 0);
         Vec3 edgeA = new Vec3(32.0, 8.0, 0.0);
         Vec3 edgeB = new Vec3(-4.0, 6.0, 32.0);
         List<Vec3> base = List.of(first, first.add(edgeA), first.add(edgeA).add(edgeB), first.add(edgeB));
         Set<BlockPos> result = TiltedBoxGenerator.generate(
            base,
            new Vec3(24.0, -24.0, 16.0),
            FillMode.HOLLOW,
            500000
         );
         assertTrue(result.size() < 500000);
      });
   }

   private static PolyhedronParameters polyhedron(int shapeVariant, double radius) {
      Vec3 center = new Vec3(0.5, 0.5, 0.5);
      return new PolyhedronParameters(
         center,
         center.add(radius, 0.0, 0.0),
         shapeVariant,
         new double[]{1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0}
      );
   }

   private static Vec3 center(int x, int y, int z) {
      return new Vec3(x + 0.5, y + 0.5, z + 0.5);
   }
}
