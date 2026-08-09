package io.github.fastformer.fastplace.geometry.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.FillMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class TiltedBoxGeneratorTest {
   private static final int[][] AXIS_ORDERS = {
      {0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}
   };

   @Test
   void intermediateTranslatedSectionsNeverOwnTheFinalBoundary() {
      Vec3 first = new Vec3(0.5, 0.5, 0.5);
      Vec3 edgeA = new Vec3(-3.0, 8.0, -1.0);
      Vec3 edgeB = new Vec3(7.0, -2.0, -6.0);
      List<Vec3> base = List.of(first, first.add(edgeA), first.add(edgeA).add(edgeB), first.add(edgeB));
      Vec3 extrusion = new Vec3(-5.0, 0.0, -1.0);

      Set<BlockPos> solid = TiltedBoxGenerator.generate(base, extrusion, FillMode.SOLID, 10000);
      assertSweepContract(base, extrusion, solid);
   }

   @Test
   void axisAlignedBoxHasExpectedVolumeAndCanonicalSurface() {
      List<Vec3> base = List.of(
         new Vec3(0.5, 0.5, 0.5),
         new Vec3(4.5, 0.5, 0.5),
         new Vec3(4.5, 0.5, 3.5),
         new Vec3(0.5, 0.5, 3.5)
      );

      Set<BlockPos> solid = TiltedBoxGenerator.generate(base, new Vec3(0.0, 4.0, 0.0), FillMode.SOLID, 10000);
      Set<BlockPos> hollow = TiltedBoxGenerator.generate(base, new Vec3(0.0, 4.0, 0.0), FillMode.HOLLOW, 10000);
      Set<BlockPos> outline = TiltedBoxGenerator.generate(base, new Vec3(0.0, 4.0, 0.0), FillMode.OUTLINE, 10000);

      assertEquals(100, solid.size());
      assertEquals(expectedHollow(solid), hollow);
      assertSweepContract(base, new Vec3(0.0, 4.0, 0.0), solid);
   }

   @Test
   void hollowOfOneHundredTwentyNineCubedStreamsTheExactNinetyEightThousandThreeHundredSixBoundary() {
      List<Vec3> base = parallelogram(
         new Vec3(0.5, 0.5, 0.5),
         new Vec3(128.0, 0.0, 0.0),
         new Vec3(0.0, 0.0, 128.0)
      );
      Vec3 extrusion = new Vec3(0.0, 128.0, 0.0);
      Set<BlockPos> expected = axisAlignedCubeBoundary(0, 128);
      RecordingObserver exactObserver = new RecordingObserver();

      Set<BlockPos> hollow = TiltedBoxGenerator.generate(
         base,
         extrusion,
         FillMode.HOLLOW,
         98_306,
         exactObserver
      );

      assertFalse(GenerationLimitExceeded.is(hollow));
      assertEquals(98_306, hollow.size());
      assertEquals(expected, hollow);
      assertEquals(98_306, exactObserver.generated.size());

      RecordingObserver limitedObserver = new RecordingObserver();
      Set<BlockPos> limited = TiltedBoxGenerator.generate(
         base,
         extrusion,
         FillMode.HOLLOW,
         98_305,
         limitedObserver
      );
      assertTrue(GenerationLimitExceeded.is(limited));
      assertEquals(98_305, limited.size());
      assertTrue(limitedObserver.generated.isEmpty(), "an over-limit HOLLOW published a prefix");
   }

   @Test
   void zeroExtrusionHollowIsExactlyTheSixNeighborBoundaryOfTheDegenerateSolid() {
      List<Vec3> base = List.of(
         new Vec3(0.5, 0.5, 0.5),
         new Vec3(4.5, 0.5, 0.5),
         new Vec3(4.5, 0.5, 3.5),
         new Vec3(0.5, 0.5, 3.5)
      );

      Set<BlockPos> solid = TiltedBoxGenerator.generate(base, Vec3.ZERO, FillMode.SOLID, 10_000);
      Set<BlockPos> hollow = TiltedBoxGenerator.generate(base, Vec3.ZERO, FillMode.HOLLOW, 10_000);

      assertFalse(solid.isEmpty());
      assertEquals(expectedHollow(solid), hollow);
   }

   @Test
   void inPlaneExtrusionHollowIsExactlyTheSixNeighborBoundaryOfTheDegenerateSolid() {
      List<Vec3> base = List.of(
         new Vec3(0.5, 0.5, 0.5),
         new Vec3(4.5, 0.5, 0.5),
         new Vec3(4.5, 0.5, 3.5),
         new Vec3(0.5, 0.5, 3.5)
      );
      Vec3 extrusion = new Vec3(3.0, 0.0, 2.0);

      Set<BlockPos> solid = TiltedBoxGenerator.generate(base, extrusion, FillMode.SOLID, 10_000);
      Set<BlockPos> hollow = TiltedBoxGenerator.generate(base, extrusion, FillMode.HOLLOW, 10_000);

      assertFalse(solid.isEmpty());
      assertEquals(expectedHollow(solid), hollow);
   }

   @Test
   void largeDegenerateFallbackCountsAndStreamsOnlyItsConservativeBoundary() {
      Vec3 origin = new Vec3(0.5, 0.5, 0.5);
      Vec3 line = new Vec3(64.0, 64.0, 64.0);
      List<Vec3> base = List.of(origin, origin.add(line), origin.add(line), origin);
      Vec3 extrusion = new Vec3(64.0, -64.0, 0.0);
      Set<BlockPos> expected = rectangularBoundary(0, 128, -64, 64, 0, 64);
      RecordingObserver observer = new RecordingObserver();

      Set<BlockPos> hollow = TiltedBoxGenerator.generate(
         base, extrusion, FillMode.HOLLOW, expected.size(), observer
      );

      assertEquals(65_538, expected.size());
      assertEquals(expected, hollow);
      assertEquals(expected.size(), observer.generated.size());

      RecordingObserver limitedObserver = new RecordingObserver();
      Set<BlockPos> limited = TiltedBoxGenerator.generate(
         base, extrusion, FillMode.HOLLOW, expected.size() - 1, limitedObserver
      );
      assertTrue(GenerationLimitExceeded.is(limited));
      assertTrue(limitedObserver.generated.isEmpty(), "large degenerate HOLLOW published a prefix");

      Set<BlockPos> solidLimited = TiltedBoxGenerator.generate(
         base, extrusion, FillMode.SOLID, expected.size(), BlockGenerationObserver.NONE
      );
      assertTrue(GenerationLimitExceeded.is(solidLimited), "SOLID and HOLLOW must select the same AABB entity");
   }

   @Test
   void perpendicularExtrusionKeepsOneConsistentCrossSectionWithOnlyNaturalEdgeFringe() {
      List<Vec3> base = List.of(
         new Vec3(0.5, 0.5, 0.5),
         new Vec3(6.5, 0.5, 2.5),
         new Vec3(4.5, 0.5, 8.5),
         new Vec3(-1.5, 0.5, 6.5)
      );
      BresenhamFaceSweep.Attempt faceAttempt = BoundaryInterpolatedFaceRasterizer.attempt(
         ProjectedBresenhamFace.Frame.create(base),
         LineTieBias.DEFAULT,
         10_000,
         BlockGenerationObserver.NONE
      );
      assertTrue(faceAttempt.succeeded());
      ProjectedBresenhamFace.Frame baseFrame = ProjectedBresenhamFace.Frame.create(base);
      Set<BlockPos> face = clipToNaturalBoundary(
         baseFrame,
         faceAttempt.result().fill(),
         faceAttempt.result().outline()
      );
      Set<BlockPos> solid = TiltedBoxGenerator.generate(
         base, new Vec3(0.0, 8.0, 0.0), FillMode.SOLID, 100000
      );
      Set<BlockPos> hollow = TiltedBoxGenerator.generate(
         base, new Vec3(0.0, 8.0, 0.0), FillMode.HOLLOW, 100000
      );
      Set<BlockPos> naturalOutline = faceAttempt.result().outline();
      for (int y = 0; y <= 8; y++) {
         int sliceY = y;
         Set<BlockPos> slice = solid.stream()
            .filter(position -> position.getY() == sliceY)
            .map(position -> new BlockPos(position.getX(), 0, position.getZ()))
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
         Set<BlockPos> fringe = difference(slice, face);
         assertTrue(slice.containsAll(face), "cross-section lost a logical face column y=" + sliceY);
         assertTrue(
            fringe.stream().allMatch(position -> touchesPlanar(position, naturalOutline, 1)),
            () -> "cross-section escaped the one-cell natural edge fringe y=" + sliceY + ": " + fringe
         );

         Set<BlockPos> hollowSlice = hollow.stream()
            .filter(position -> position.getY() == sliceY)
            .map(position -> new BlockPos(position.getX(), 0, position.getZ()))
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
         assertEquals(
            y == 0 || y == 8 ? slice : planarBoundary(slice, 1),
            hollowSlice,
            "hollow cross-section y=" + y
         );
      }
   }

   @Test
   void tiltedBoxDoesNotExposeTranslatedInteriorLayers() {
      Vec3 first = new Vec3(0.5, 0.5, 0.5);
      Vec3 edgeA = new Vec3(3.0, 3.0, 0.0);
      Vec3 edgeB = new Vec3(-1.0, 1.0, 4.0);
      List<Vec3> base = List.of(first, first.add(edgeA), first.add(edgeA).add(edgeB), first.add(edgeB));
      Vec3 extrusion = new Vec3(6.0, -6.0, 3.0);

      Set<BlockPos> solid = TiltedBoxGenerator.generate(base, extrusion, FillMode.SOLID, 10000);
      Set<BlockPos> hollow = TiltedBoxGenerator.generate(base, extrusion, FillMode.HOLLOW, 10000);
      Set<BlockPos> outline = TiltedBoxGenerator.generate(base, extrusion, FillMode.OUTLINE, 10000);
      assertEquals(expectedHollow(solid), hollow);
      assertTrue(solid.size() > hollow.size());
      assertFalse(hasEnclosedAir(solid));
      assertSweepContract(base, extrusion, solid);
      assertTrue(solid.containsAll(outline));
      assertTrue(corners(base, extrusion).stream().allMatch(outline::contains));
      assertTrue(is26Connected(outline));
   }

   @Test
   void nestedSecondaryAndTertiaryBridgesCloseCombinationCorners() {
      Vec3 first = new Vec3(0.5, 0.5, 0.5);
      Vec3 edgeA = new Vec3(4.0, 0.0, 4.0);
      Vec3 edgeB = new Vec3(-2.0, 4.0, 2.0);
      List<Vec3> base = List.of(first, first.add(edgeA), first.add(edgeA).add(edgeB), first.add(edgeB));
      Vec3 extrusion = new Vec3(-4.0, -4.0, 4.0);

      Set<BlockPos> solid = TiltedBoxGenerator.generate(base, extrusion, FillMode.SOLID, 10000);
      Set<BlockPos> hollow = TiltedBoxGenerator.generate(base, extrusion, FillMode.HOLLOW, 10000);
      Set<BlockPos> outline = TiltedBoxGenerator.generate(base, extrusion, FillMode.OUTLINE, 10000);

      assertEquals(expectedHollow(solid), hollow);
      assertFalse(hasEnclosedAir(solid));
      assertSweepContract(base, extrusion, solid);
      assertTrue(solid.containsAll(outline));
   }

   @Test
   void enclosedWorkerCavitiesAreFilledWithoutChangingTheOwnedOutline() {
      assertVolumeContract(
         new Vec3(2.0, -1.0, -2.0),
         new Vec3(-1.0, 4.0, -3.0),
         new Vec3(4.0, 4.0, -4.0)
      );
   }

   @Test
   void volumeTransitionOpenVoidIsFilled() {
      Vec3 first = new Vec3(0.5, 0.5, 0.5);
      Vec3 edgeA = new Vec3(-1.0, 2.0, 1.0);
      Vec3 edgeB = new Vec3(2.0, 0.0, 2.0);
      List<Vec3> base = List.of(first, first.add(edgeA), first.add(edgeA).add(edgeB), first.add(edgeB));
      Vec3 extrusion = new Vec3(-2.0, -1.0, 0.0);
      Set<BlockPos> solid = TiltedBoxGenerator.generate(base, extrusion, FillMode.SOLID, 10000);

      assertTrue(solid.contains(new BlockPos(0, 1, 2)));
      assertVolumeContract(edgeA, edgeB, extrusion);
   }

   @Test
   void ownedEdgeVoxelStaysRepresentedWithoutCarvingASurfaceHole() {
      Vec3 first = new Vec3(0.5, 0.5, 0.5);
      Vec3 edgeA = new Vec3(-1.0, -2.0, -1.0);
      Vec3 edgeB = new Vec3(0.0, -2.0, -1.0);
      List<Vec3> base = List.of(first, first.add(edgeA), first.add(edgeA).add(edgeB), first.add(edgeB));
      Vec3 extrusion = new Vec3(-1.0, 1.0, 2.0);

      Set<BlockPos> solid = TiltedBoxGenerator.generate(base, extrusion, FillMode.SOLID, 10000);
      Set<BlockPos> hollow = TiltedBoxGenerator.generate(base, extrusion, FillMode.HOLLOW, 10000);
      Set<BlockPos> outline = TiltedBoxGenerator.generate(base, extrusion, FillMode.OUTLINE, 10000);

      assertEquals(expectedHollow(solid), hollow);
      assertFalse(hasEnclosedAir(solid));
      assertSweepContract(base, extrusion, solid);
      assertTrue(solid.containsAll(outline));
   }

   @Test
   void ownedOutlineRemainsInsideSolidAndSurfaceIsCanonical() {
      Vec3 first = new Vec3(0.5, 0.5, 0.5);
      Vec3 edgeA = new Vec3(-4.0, 4.0, 0.0);
      Vec3 edgeB = new Vec3(3.0, -3.0, 3.0);
      List<Vec3> base = List.of(first, first.add(edgeA), first.add(edgeA).add(edgeB), first.add(edgeB));
      Vec3 extrusion = new Vec3(-2.0, -1.0, 4.0);

      Set<BlockPos> solid = TiltedBoxGenerator.generate(base, extrusion, FillMode.SOLID, 10000);
      Set<BlockPos> hollow = TiltedBoxGenerator.generate(base, extrusion, FillMode.HOLLOW, 10000);
      Set<BlockPos> outline = TiltedBoxGenerator.generate(base, extrusion, FillMode.OUTLINE, 10000);

      assertTrue(outline.containsAll(corners(base, extrusion)));
      assertTrue(solid.containsAll(outline));
      assertEquals(expectedHollow(solid), hollow);
      assertSweepContract(base, extrusion, solid);
   }

   @Test
   void tiltedBoxHonorsOutputLimits() {
      Vec3 first = new Vec3(0.5, 0.5, 0.5);
      Vec3 edgeA = new Vec3(3.0, 3.0, 0.0);
      Vec3 edgeB = new Vec3(-1.0, 1.0, 4.0);
      List<Vec3> base = List.of(first, first.add(edgeA), first.add(edgeA).add(edgeB), first.add(edgeB));
      Vec3 extrusion = new Vec3(6.0, -6.0, 3.0);

      for (FillMode mode : List.of(FillMode.SOLID, FillMode.HOLLOW, FillMode.OUTLINE)) {
         Set<BlockPos> full = TiltedBoxGenerator.generate(base, extrusion, mode, 100_000);
         RecordingObserver observer = new RecordingObserver();
         Set<BlockPos> limited = TiltedBoxGenerator.generate(
            base,
            extrusion,
            mode,
            full.size() - 1,
            observer
         );
         assertEquals(
            full.size() - 1,
            limited.size(),
            mode + " must return the deterministic one-past-placement-limit sentinel"
         );
         assertTrue(observer.generated.isEmpty(), mode + " published an incomplete prefix");
      }
   }

   @Test
   void limitedSolidMustNotMasqueradeAsACompleteBaseFace() {
      List<Vec3> base = List.of(
         new Vec3(0.5, 0.5, 0.5),
         new Vec3(20.5, 4.5, 1.5),
         new Vec3(17.5, 16.5, 9.5),
         new Vec3(-2.5, 12.5, 8.5)
      );
      Vec3 extrusion = new Vec3(12.0, 18.0, -9.0);
      Set<BlockPos> completeBase = PlanarFaceRasterizer.generate(base, Integer.MAX_VALUE);
      Set<BlockPos> limited = TiltedBoxGenerator.generate(
         base, extrusion, FillMode.SOLID, completeBase.size()
      );

      assertEquals(
         completeBase.size(),
         limited.size(),
         "an incomplete volume must carry the one-past-placement-limit sentinel"
      );
   }

   @Test
   void fixedGapSolidHollowAndOutlineShareOneOppositeBiasContract() {
      List<Vec3> base = parallelogram(
         new Vec3(0.5, 0.5, 0.5),
         new Vec3(0.0, -1.0, 4.0),
         new Vec3(1.0, -1.0, 1.0)
      );
      Vec3 extrusion = new Vec3(-2.0, -1.0, -1.0);
      LineTieBias bias = LineTieBias.OPPOSITE;
      Set<BlockPos> solid = TiltedBoxGenerator.generate(
         base, extrusion, FillMode.SOLID, 100_000, BlockGenerationObserver.NONE, bias
      );
      Set<BlockPos> hollow = TiltedBoxGenerator.generate(
         base, extrusion, FillMode.HOLLOW, 100_000, BlockGenerationObserver.NONE, bias
      );
      Set<BlockPos> outline = TiltedBoxGenerator.generate(
         base, extrusion, FillMode.OUTLINE, 100_000, BlockGenerationObserver.NONE, bias
      );
      Set<BlockPos> expectedOutline = independentlyRasterizedOutline(base, extrusion, bias);

      assertFalse(solid.isEmpty());
      assertEquals(expectedHollow(solid), hollow);
      assertEquals(expectedOutline, outline, "outline must use the requested bias on every final face");
      assertTrue(solid.containsAll(outline));
      assertTrue(hollow.containsAll(corners(base, extrusion)), "a box corner escaped the physical boundary");
      assertTrue(fiveSidedOpenGaps(solid).isEmpty(), () -> "five-sided gaps " + fiveSidedOpenGaps(solid));
      assertFalse(hasEnclosedAir(solid));
   }

   @Test
   void hollowCancellationDuringStagedSolidDoesNotPublishBoundaryPrefix() {
      List<Vec3> base = List.of(
         new Vec3(0.5, 0.5, 0.5),
         new Vec3(24.5, 0.5, 5.5),
         new Vec3(21.5, 0.5, 23.5),
         new Vec3(-2.5, 0.5, 18.5)
      );
      CancellingObserver observer = new CancellingObserver(32);

      assertThrows(
         CancellationException.class,
         () -> TiltedBoxGenerator.generate(
            base,
            new Vec3(0.0, 8.0, 0.0),
            FillMode.HOLLOW,
            100_000,
            observer
         )
      );
      assertTrue(observer.generated.isEmpty(), "cancelled staging published a HOLLOW prefix");
   }

   @Test
   void sweepContractHoldsUnderEveryAxisOrderAndSign() {
      Vec3 first = new Vec3(0.5, 0.5, 0.5);
      Vec3 edgeA = new Vec3(5.0, 2.0, -1.0);
      Vec3 edgeB = new Vec3(-2.0, 4.0, 3.0);
      List<Vec3> base = List.of(first, first.add(edgeA), first.add(edgeA).add(edgeB), first.add(edgeB));
      Vec3 extrusion = new Vec3(4.0, -3.0, 5.0);
      for (int[] order : AXIS_ORDERS) {
         for (int mask = 0; mask < 8; mask++) {
            int[] signs = {
               (mask & 1) == 0 ? -1 : 1,
               (mask & 2) == 0 ? -1 : 1,
               (mask & 4) == 0 ? -1 : 1
            };
            List<Vec3> transformedBase = base.stream().map(point -> transform(point, order, signs)).toList();
            Vec3 transformedExtrusion = transform(extrusion, order, signs);
            Set<BlockPos> solid = TiltedBoxGenerator.generate(
               transformedBase, transformedExtrusion, FillMode.SOLID, 10000
            );
            Set<BlockPos> hollow = TiltedBoxGenerator.generate(
               transformedBase, transformedExtrusion, FillMode.HOLLOW, 10000
            );
            Set<BlockPos> outline = TiltedBoxGenerator.generate(
               transformedBase, transformedExtrusion, FillMode.OUTLINE, 10000
            );

            assertSweepContract(transformedBase, transformedExtrusion, solid);
            assertEquals(expectedHollow(solid), hollow, () -> frame(order, signs));
         }
      }
   }

   @Test
   void coordinatePlaneFacesCoverPerpendicularAndShearedRandomSlopes() {
      java.util.Random random = new java.util.Random(0x434F4F5244504C41L);
      int checked = 0;
      for (int normalAxis = 0; normalAxis < 3; normalAxis++) {
         int firstAxis = (normalAxis + 1) % 3;
         int secondAxis = (normalAxis + 2) % 3;
         for (int sample = 0; sample < 32; sample++) {
            Vec3 edgeA = sample % 4 == 0
               ? vector(firstAxis, nonZero(random, 1, 9), secondAxis, 0)
               : vector(firstAxis, nonZero(random, 1, 9), secondAxis, nonZero(random, 1, 9));
            Vec3 edgeB = sample % 4 == 0
               ? vector(firstAxis, 0, secondAxis, nonZero(random, 1, 9))
               : vector(firstAxis, nonZero(random, 1, 9), secondAxis, nonZero(random, 1, 9));
            if (edgeA.cross(edgeB).lengthSqr() < 1.0E-7) {
               sample--;
               continue;
            }
            double[] extrusion = {
               sample % 2 == 0 ? 0.0 : nonZero(random, 1, 5),
               sample % 2 == 0 ? 0.0 : nonZero(random, 1, 5),
               sample % 2 == 0 ? 0.0 : nonZero(random, 1, 5)
            };
            extrusion[normalAxis] = nonZero(random, 2, 9);
            Vec3 origin = randomBlockCenter(random);
            List<Vec3> base = parallelogram(origin, edgeA, edgeB);
            assertRandomVolumeContract(base, new Vec3(extrusion[0], extrusion[1], extrusion[2]), "coordinate-" + checked);
            checked++;
         }
      }
      assertEquals(96, checked);
   }

   @Test
   void fullyTiltedRandomAnglesAndSlopesSatisfyTheSameVolumeContract() {
      java.util.Random random = new java.util.Random(0x54494C544544564FL);
      int checked = 0;
      int attempts = 0;
      while (checked < 128 && attempts++ < 10000) {
         Vec3 edgeA = randomFullyTiltedVector(random, 1, 8);
         Vec3 edgeB = randomFullyTiltedVector(random, 1, 8);
         Vec3 extrusion = randomFullyTiltedVector(random, 1, 8);
         Vec3 normal = edgeA.cross(edgeB);
         if (normal.lengthSqr() < 4.0 || !allComponentsNonZero(normal) || Math.abs(normal.dot(extrusion)) < 1.0) {
            continue;
         }
         List<Vec3> base = parallelogram(randomBlockCenter(random), edgeA, edgeB);
         assertRandomVolumeContract(base, extrusion, "tilted-" + checked);
         checked++;
      }
      assertEquals(128, checked);
   }

   @Test
   void partiallyTiltedRandomFacesSweepAlongMixedDirectionExtrusions() {
      java.util.Random random = new java.util.Random(0x50415254564F4C55L);
      int checked = 0;
      for (int zeroAxis = 0; zeroAxis < 3; zeroAxis++) {
         for (int sample = 0; sample < 16; sample++) {
            Vec3[] basis = partialTiltBasis(
               zeroAxis,
               nonZero(random, 1, 8),
               nonZero(random, 1, 8),
               nonZero(random, 1, 8)
            );
            Vec3 extrusion = randomFullyTiltedVector(random, 1, 6);
            Vec3 normal = basis[0].cross(basis[1]);
            if (Math.abs(normal.dot(extrusion)) < 1.0) {
               sample--;
               continue;
            }
            assertRandomVolumeContract(
               parallelogram(randomBlockCenter(random), basis[0], basis[1]),
               extrusion,
               "partial-" + checked
            );
            checked++;
         }
      }
      assertEquals(48, checked);
   }

   private static Set<BlockPos> boundary(Set<BlockPos> solid) {
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      for (BlockPos position : solid) {
         for (Direction direction : Direction.values()) {
            if (!solid.contains(position.relative(direction))) {
               result.add(position);
               break;
            }
         }
      }
      return result;
   }

   private static Set<BlockPos> axisAlignedCubeBoundary(int minimum, int maximum) {
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      for (int first = minimum; first <= maximum; first++) {
         for (int second = minimum; second <= maximum; second++) {
            result.add(new BlockPos(minimum, first, second));
            result.add(new BlockPos(maximum, first, second));
            result.add(new BlockPos(first, minimum, second));
            result.add(new BlockPos(first, maximum, second));
            result.add(new BlockPos(first, second, minimum));
            result.add(new BlockPos(first, second, maximum));
         }
      }
      return result;
   }

   private static Set<BlockPos> rectangularBoundary(
      int minimumX, int maximumX,
      int minimumY, int maximumY,
      int minimumZ, int maximumZ
   ) {
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      for (int x = minimumX; x <= maximumX; x++) {
         for (int y = minimumY; y <= maximumY; y++) {
            for (int z = minimumZ; z <= maximumZ; z++) {
               if (x == minimumX || x == maximumX
                  || y == minimumY || y == maximumY
                  || z == minimumZ || z == maximumZ) {
                  result.add(new BlockPos(x, y, z));
               }
            }
         }
      }
      return result;
   }

   private static void assertRandomVolumeContract(List<Vec3> base, Vec3 extrusion, String name) {
      try {
         Set<BlockPos> solid = TiltedBoxGenerator.generate(base, extrusion, FillMode.SOLID, 100000);
         Set<BlockPos> hollow = TiltedBoxGenerator.generate(base, extrusion, FillMode.HOLLOW, 100000);
         Set<BlockPos> outline = TiltedBoxGenerator.generate(base, extrusion, FillMode.OUTLINE, 100000);
         assertFalse(solid.isEmpty());
         assertSweepContract(base, extrusion, solid);
         assertEquals(expectedHollow(solid), hollow);
         assertTrue(outline.containsAll(corners(base, extrusion)));
         assertTrue(is26Connected(outline));
         assertTrue(fiveSidedOpenGaps(solid).isEmpty(), () -> name + " five-sided gaps " + fiveSidedOpenGaps(solid));
      } catch (AssertionError error) {
         throw new AssertionError(name + " base=" + base + " extrusion=" + extrusion + ": " + error.getMessage(), error);
      }
   }

   private static List<Vec3> parallelogram(Vec3 origin, Vec3 first, Vec3 second) {
      return List.of(origin, origin.add(first), origin.add(first).add(second), origin.add(second));
   }

   private static Vec3 randomBlockCenter(java.util.Random random) {
      return new Vec3(
         random.nextInt(-12, 13) + 0.5,
         random.nextInt(-12, 13) + 0.5,
         random.nextInt(-12, 13) + 0.5
      );
   }

   private static Vec3 randomFullyTiltedVector(java.util.Random random, int minimum, int maximum) {
      return new Vec3(
         nonZero(random, minimum, maximum),
         nonZero(random, minimum, maximum),
         nonZero(random, minimum, maximum)
      );
   }

   private static boolean allComponentsNonZero(Vec3 vector) {
      return vector.x != 0.0 && vector.y != 0.0 && vector.z != 0.0;
   }

   private static Vec3[] partialTiltBasis(int zeroAxis, int first, int second, int height) {
      return switch (zeroAxis) {
         case 0 -> new Vec3[]{new Vec3(0.0, second, -first), new Vec3(height, 0.0, 0.0)};
         case 1 -> new Vec3[]{new Vec3(second, 0.0, -first), new Vec3(0.0, height, 0.0)};
         default -> new Vec3[]{new Vec3(second, -first, 0.0), new Vec3(0.0, 0.0, height)};
      };
   }

   private static int nonZero(java.util.Random random, int minimum, int maximum) {
      int magnitude = random.nextInt(minimum, maximum + 1);
      return random.nextBoolean() ? magnitude : -magnitude;
   }

   private static Vec3 vector(int firstAxis, int firstValue, int secondAxis, int secondValue) {
      double[] values = {0.0, 0.0, 0.0};
      values[firstAxis] = firstValue;
      values[secondAxis] = secondValue;
      return new Vec3(values[0], values[1], values[2]);
   }

   private static Set<BlockPos> planarBoundary(Set<BlockPos> face, int normalAxis) {
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      for (BlockPos position : face) {
         for (int axis = 0; axis < 3; axis++) {
            if (axis == normalAxis) {
               continue;
            }
            Direction positive = Direction.fromAxisAndDirection(
               Direction.Axis.values()[axis], Direction.AxisDirection.POSITIVE
            );
            if (!face.contains(position.relative(positive)) || !face.contains(position.relative(positive.getOpposite()))) {
               result.add(position);
               break;
            }
         }
      }
      return result;
   }

   private static Set<BlockPos> expectedHollow(Set<BlockPos> solid) {
      return boundary(solid);
   }

   private static Set<BlockPos> difference(Set<BlockPos> expected, Set<BlockPos> actual) {
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>(expected);
      result.removeAll(actual);
      return result;
   }

   private static void assertVolumeContract(Vec3 edgeA, Vec3 edgeB, Vec3 extrusion) {
      Vec3 first = new Vec3(0.5, 0.5, 0.5);
      List<Vec3> base = List.of(first, first.add(edgeA), first.add(edgeA).add(edgeB), first.add(edgeB));
      Set<BlockPos> solid = TiltedBoxGenerator.generate(base, extrusion, FillMode.SOLID, 10000);
      Set<BlockPos> hollow = TiltedBoxGenerator.generate(base, extrusion, FillMode.HOLLOW, 10000);
      Set<BlockPos> outline = TiltedBoxGenerator.generate(base, extrusion, FillMode.OUTLINE, 10000);

      assertEquals(expectedHollow(solid), hollow);
      assertFalse(hasEnclosedAir(solid));
      assertSweepContract(base, extrusion, solid);
      assertTrue(solid.containsAll(outline));
   }

   private static void assertSweepContract(List<Vec3> base, Vec3 extrusion, Set<BlockPos> solid) {
      BresenhamColumnVolume.Result expected = BresenhamColumnVolume.generate(
         base,
         extrusion,
         Integer.MAX_VALUE,
         BlockGenerationObserver.NONE
      );
      Set<BlockPos> independentShell = independentlyRasterizedShell(base, extrusion, LineTieBias.DEFAULT);
      Set<BlockPos> generatedOutline = TiltedBoxGenerator.generate(
         base,
         extrusion,
         FillMode.OUTLINE,
         Integer.MAX_VALUE
      );
      assertTrue(expected.complete());
      assertEquals(expected.blocks(), solid);
      assertEquals(independentShell, expected.shell(), "volume shell must be the six independent face rasterizations");
      assertTrue(generatedOutline.containsAll(corners(base, extrusion)), "outline lost a box corner");
      assertTrue(is26Connected(generatedOutline), "outline disconnected");
      assertTrue(solid.containsAll(expected.shell()));
      assertTrue(solid.containsAll(corners(base, extrusion)), "solid lost a box corner");
      assertTrue(is26Connected(solid));
      assertFalse(hasEnclosedAir(solid));
      assertTrue(fiveSidedOpenGaps(solid).isEmpty(), () -> "five-sided gaps " + fiveSidedOpenGaps(solid));
   }

   private static Set<BlockPos> independentlyRasterizedShell(
      List<Vec3> base,
      Vec3 extrusion,
      LineTieBias tieBias
   ) {
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      for (List<Vec3> face : boxFaces(base, extrusion)) {
         ProjectedBresenhamFace.Frame frame = ProjectedBresenhamFace.Frame.create(face);
         BresenhamFaceSweep.Attempt attempt = BoundaryInterpolatedFaceRasterizer.attempt(
            frame,
            tieBias,
            100_000,
            BlockGenerationObserver.NONE
         );
         assertTrue(attempt.succeeded(), "independent face failed " + face + " status=" + attempt.status());
         Set<BlockPos> clipped = clipToNaturalBoundary(
            frame,
            attempt.result().fill(),
            attempt.result().outline()
         );
         assertTrue(clipped.containsAll(attempt.result().outline()), "face lost its natural outline " + face);
         result.addAll(clipped);
      }
      return result;
   }

   private static List<List<Vec3>> boxFaces(List<Vec3> base, Vec3 extrusion) {
      List<Vec3> top = base.stream().map(point -> point.add(extrusion)).toList();
      ArrayList<List<Vec3>> result = new ArrayList<>();
      result.add(List.copyOf(base));
      result.add(List.copyOf(top));
      for (int index = 0; index < 4; index++) {
         int next = (index + 1) % 4;
         result.add(List.of(base.get(index), base.get(next), top.get(next), top.get(index)));
      }
      return result;
   }

   private static Set<BlockPos> clipToNaturalBoundary(
      ProjectedBresenhamFace.Frame frame,
      Set<BlockPos> fill,
      Set<BlockPos> outline
   ) {
      Set<FaceColumn> boundaryColumns = outline.stream()
         .map(block -> faceColumn(frame, block))
         .collect(java.util.stream.Collectors.toSet());
      return fill.stream()
         .filter(block -> outline.contains(block) || !boundaryColumns.contains(faceColumn(frame, block)))
         .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
   }

   private static FaceColumn faceColumn(ProjectedBresenhamFace.Frame frame, BlockPos block) {
      long[] delta = {
         (long)block.getX() - frame.anchor().getX(),
         (long)block.getY() - frame.anchor().getY(),
         (long)block.getZ() - frame.anchor().getZ()
      };
      int[] order = frame.order();
      int[] signs = frame.signs();
      return new FaceColumn(
         Math.toIntExact(delta[order[0]] * signs[0]),
         Math.toIntExact(delta[order[1]] * signs[1])
      );
   }

   private static boolean touchesPlanar(BlockPos position, Set<BlockPos> boundary, int normalAxis) {
      if (boundary.contains(position)) {
         return true;
      }
      for (int axis = 0; axis < 3; axis++) {
         if (axis == normalAxis) {
            continue;
         }
         Direction positive = Direction.fromAxisAndDirection(
            Direction.Axis.values()[axis], Direction.AxisDirection.POSITIVE
         );
         if (boundary.contains(position.relative(positive))
            || boundary.contains(position.relative(positive.getOpposite()))) {
            return true;
         }
      }
      return false;
   }

   private record FaceColumn(int u, int v) {
   }

   private static Set<BlockPos> independentlyRasterizedOutline(
      List<Vec3> base,
      Vec3 extrusion,
      LineTieBias tieBias
   ) {
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      for (List<Vec3> face : boxFaces(base, extrusion)) {
         ProjectedBresenhamFace.Frame frame = ProjectedBresenhamFace.Frame.create(face);
         BresenhamFaceSweep.Attempt attempt = BoundaryInterpolatedFaceRasterizer.attempt(
            frame,
            tieBias,
            100_000,
            BlockGenerationObserver.NONE
         );
         assertTrue(attempt.succeeded(), () -> "face failed: " + face + " status=" + attempt.status());
         result.addAll(attempt.result().outline());
      }
      return result;
   }

   private static Set<BlockPos> fiveSidedOpenGaps(Set<BlockPos> solid) {
      LinkedHashSet<BlockPos> candidates = new LinkedHashSet<>();
      for (BlockPos position : solid) {
         for (Direction direction : Direction.values()) {
            BlockPos neighbor = position.relative(direction);
            if (!solid.contains(neighbor)) {
               candidates.add(neighbor);
            }
         }
      }
      candidates.removeIf(candidate -> java.util.Arrays.stream(Direction.values())
         .filter(direction -> solid.contains(candidate.relative(direction)))
         .count() != 5L);
      return candidates;
   }

   private static Vec3 transform(Vec3 point, int[] order, int[] signs) {
      double[] values = {point.x, point.y, point.z};
      return new Vec3(
         signs[0] * values[order[0]],
         signs[1] * values[order[1]],
         signs[2] * values[order[2]]
      );
   }

   private static String frame(int[] order, int[] signs) {
      return "order=" + java.util.Arrays.toString(order) + " signs=" + java.util.Arrays.toString(signs);
   }

   private static Set<BlockPos> corners(List<Vec3> base, Vec3 extrusion) {
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      base.forEach(vertex -> {
         result.add(BlockPos.containing(vertex));
         result.add(BlockPos.containing(vertex.add(extrusion)));
      });
      return result;
   }

   private static boolean is26Connected(Set<BlockPos> blocks) {
      if (blocks.isEmpty()) {
         return true;
      }
      HashSet<BlockPos> visited = new HashSet<>();
      ArrayDeque<BlockPos> queue = new ArrayDeque<>();
      queue.add(blocks.iterator().next());
      while (!queue.isEmpty()) {
         BlockPos current = queue.removeFirst();
         if (!visited.add(current)) {
            continue;
         }
         for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
               for (int z = -1; z <= 1; z++) {
                  BlockPos neighbor = current.offset(x, y, z);
                  if (blocks.contains(neighbor) && !visited.contains(neighbor)) {
                     queue.addLast(neighbor);
                  }
               }
            }
         }
      }
      return visited.size() == blocks.size();
   }

   private static boolean isSixConnected(Set<BlockPos> blocks) {
      if (blocks.isEmpty()) {
         return true;
      }
      HashSet<BlockPos> visited = new HashSet<>();
      ArrayDeque<BlockPos> queue = new ArrayDeque<>();
      queue.add(blocks.iterator().next());
      while (!queue.isEmpty()) {
         BlockPos current = queue.removeFirst();
         if (!visited.add(current)) {
            continue;
         }
         for (Direction direction : Direction.values()) {
            BlockPos neighbor = current.relative(direction);
            if (blocks.contains(neighbor) && !visited.contains(neighbor)) {
               queue.addLast(neighbor);
            }
         }
      }
      return visited.size() == blocks.size();
   }

   private static boolean hasEnclosedAir(Set<BlockPos> solid) {
      int minX = solid.stream().mapToInt(BlockPos::getX).min().orElse(0) - 1;
      int minY = solid.stream().mapToInt(BlockPos::getY).min().orElse(0) - 1;
      int minZ = solid.stream().mapToInt(BlockPos::getZ).min().orElse(0) - 1;
      int maxX = solid.stream().mapToInt(BlockPos::getX).max().orElse(0) + 1;
      int maxY = solid.stream().mapToInt(BlockPos::getY).max().orElse(0) + 1;
      int maxZ = solid.stream().mapToInt(BlockPos::getZ).max().orElse(0) + 1;
      HashSet<BlockPos> exterior = new HashSet<>();
      ArrayDeque<BlockPos> queue = new ArrayDeque<>();
      queue.add(new BlockPos(minX, minY, minZ));
      while (!queue.isEmpty()) {
         BlockPos current = queue.removeFirst();
         if (!exterior.add(current)) {
            continue;
         }
         for (Direction direction : Direction.values()) {
            BlockPos neighbor = current.relative(direction);
            if (neighbor.getX() >= minX && neighbor.getX() <= maxX
               && neighbor.getY() >= minY && neighbor.getY() <= maxY
               && neighbor.getZ() >= minZ && neighbor.getZ() <= maxZ
               && !solid.contains(neighbor) && !exterior.contains(neighbor)) {
               queue.addLast(neighbor);
            }
         }
      }
      for (int x = minX; x <= maxX; x++) {
         for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
               BlockPos position = new BlockPos(x, y, z);
               if (!solid.contains(position) && !exterior.contains(position)) {
                  return true;
               }
            }
         }
      }
      return false;
   }

   private static class RecordingObserver implements BlockGenerationObserver {
      final List<BlockPos> generated = new ArrayList<>();

      @Override
      public void onGenerated(BlockPos position) {
         this.generated.add(position);
      }
   }

   private static final class CancellingObserver extends RecordingObserver {
      private int checksRemaining;

      CancellingObserver(int checksRemaining) {
         this.checksRemaining = checksRemaining;
      }

      @Override
      public void checkCancelled() {
         if (this.checksRemaining-- <= 0) {
            throw new CancellationException("test cancellation");
         }
      }
   }
}
