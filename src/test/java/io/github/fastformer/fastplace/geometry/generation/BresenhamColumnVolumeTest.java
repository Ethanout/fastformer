package io.github.fastformer.fastplace.geometry.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.FillMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class BresenhamColumnVolumeTest {
   @Test
   void axisAlignedBoxHasOneHundredBlocksAndSixCompleteFaceShells() {
      List<Vec3> base = List.of(
         center(0, 0, 0),
         center(4, 0, 0),
         center(4, 0, 3),
         center(0, 0, 3)
      );
      Vec3 extrusion = new Vec3(0.0, 4.0, 0.0);

      BresenhamColumnVolume.Result result = generate(base, extrusion, 10_000);
      Set<BlockPos> expectedShell = independentlyRasterizedShell(base, extrusion);

      assertTrue(result.complete());
      assertEquals(100, result.blocks().size());
      assertEquals(82, sixNeighborBoundary(result.blocks()).size());
      assertEquals(expectedShell, result.shell());
      assertTrue(result.blocks().containsAll(expectedShell));
      assertTrue(result.blocks().containsAll(result.shell()));
      assertFalse(hasEnclosedAir(result.blocks()));
   }

   @Test
   void solidResultKeepsCompactLazyColumnStorage() {
      Set<BlockPos> expected = new LinkedHashSet<>();
      for (int x = -2; x <= 2; x++) {
         for (int y = 4; y <= 8; y++) {
            expected.add(new BlockPos(x, y, 11));
         }
      }

      Set<BlockPos> actual = BresenhamColumnVolume.solidFromSpansForTesting(expected, 1);

      assertTrue(BresenhamColumnVolume.usesLazyColumnStorageForTesting(actual));
      assertEquals(expected.size(), actual.size());
      assertEquals(expected, actual);
      assertTrue(actual.contains(new BlockPos(0, 6, 11)));
      assertFalse(actual.contains(new BlockPos(0, 9, 11)));
   }

   @Test
   void lazyColumnIteratorStopsAtMaximumIntegerCoordinate() {
      BlockPos penultimate = new BlockPos(Integer.MAX_VALUE - 1, 3, -5);
      BlockPos last = new BlockPos(Integer.MAX_VALUE, 3, -5);
      Set<BlockPos> actual = BresenhamColumnVolume.solidFromSpansForTesting(
         new LinkedHashSet<>(List.of(penultimate, last)),
         0
      );

      assertEquals(List.of(penultimate, last), new ArrayList<>(actual));
   }

   @Test
   void oldTranslatedFaceCavityRegressionIsClosedByTheColumnVolume() {
      assertVolumeContract(
         parallelogram(
            center(0, 0, 0),
            new Vec3(2.0, -1.0, -2.0),
            new Vec3(-1.0, 4.0, -3.0)
         ),
         new Vec3(4.0, 4.0, -4.0),
         "translated-face-cavity"
      );
   }

   @Test
   void oldTransitionOpenVoidRegressionIsClosedByTheColumnVolume() {
      assertVolumeContract(
         parallelogram(
            center(0, 0, 0),
            new Vec3(-1.0, 2.0, 1.0),
            new Vec3(2.0, 0.0, 2.0)
         ),
         new Vec3(-2.0, -1.0, 0.0),
         "transition-open-void"
      );
   }

   @Test
   void randomCoordinatePlaneVolumesHaveCompleteShellsAndNoCavities() {
      Random random = new Random(0x434F4C554D4E4350L);
      int checked = 0;
      for (int normalAxis = 0; normalAxis < 3; normalAxis++) {
         int firstAxis = (normalAxis + 1) % 3;
         int secondAxis = (normalAxis + 2) % 3;
         for (int sample = 0; sample < 8; sample++) {
            Vec3 first = vector(
               firstAxis,
               nonZero(random, 1, 6),
               secondAxis,
               sample % 3 == 0 ? 0 : nonZero(random, 1, 6)
            );
            Vec3 second = vector(
               firstAxis,
               sample % 3 == 0 ? 0 : nonZero(random, 1, 6),
               secondAxis,
               nonZero(random, 1, 6)
            );
            if (first.cross(second).lengthSqr() < 1.0E-7) {
               sample--;
               continue;
            }
            double[] delta = {
               nonZero(random, 1, 4),
               nonZero(random, 1, 4),
               nonZero(random, 1, 4)
            };
            delta[normalAxis] = nonZero(random, 2, 7);
            assertVolumeContract(
               parallelogram(randomCenter(random), first, second),
               new Vec3(delta[0], delta[1], delta[2]),
               "coordinate-" + checked
            );
            checked++;
         }
      }
      assertEquals(24, checked);
   }

   @Test
   void randomPartiallyTiltedVolumesHaveCompleteShellsAndNoCavities() {
      Random random = new Random(0x434F4C554D4E5054L);
      int checked = 0;
      for (int zeroNormalAxis = 0; zeroNormalAxis < 3; zeroNormalAxis++) {
         for (int sample = 0; sample < 8; sample++) {
            Vec3[] basis = partialTiltBasis(
               zeroNormalAxis,
               nonZero(random, 1, 6),
               nonZero(random, 1, 6),
               nonZero(random, 1, 6)
            );
            Vec3 extrusion = fullyTiltedVector(random, 1, 5);
            if (Math.abs(basis[0].cross(basis[1]).dot(extrusion)) < 1.0) {
               sample--;
               continue;
            }
            assertVolumeContract(
               parallelogram(randomCenter(random), basis[0], basis[1]),
               extrusion,
               "partial-" + checked
            );
            checked++;
         }
      }
      assertEquals(24, checked);
   }

   @Test
   void randomFullyTiltedVolumesHaveCompleteShellsAndNoCavities() {
      Random random = new Random(0x434F4C554D4E4654L);
      int checked = 0;
      int attempts = 0;
      while (checked < 48 && attempts++ < 10_000) {
         Vec3 first = fullyTiltedVector(random, 1, 6);
         Vec3 second = fullyTiltedVector(random, 1, 6);
         Vec3 extrusion = fullyTiltedVector(random, 1, 6);
         Vec3 normal = first.cross(second);
         if (normal.lengthSqr() < 2.0
            || !allComponentsNonZero(normal)
            || Math.abs(normal.dot(extrusion)) < 1.0) {
            continue;
         }
         assertVolumeContract(
            parallelogram(randomCenter(random), first, second),
            extrusion,
            "fully-tilted-" + checked
         );
         checked++;
      }
      assertEquals(48, checked);
   }

   @Test
   void outputLimitIsAtomicAndDoesNotPublishAnIncompletePrefix() {
      List<Vec3> base = List.of(
         center(0, 0, 0),
         center(12, 3, -2),
         center(9, 11, 4),
         center(-3, 8, 6)
      );
      Vec3 extrusion = new Vec3(9.0, -7.0, 11.0);

      BresenhamColumnVolume.Result full = generate(base, extrusion, 100_000);
      RecordingObserver observer = new RecordingObserver();
      BresenhamColumnVolume.Result limited = BresenhamColumnVolume.generate(
         base,
         extrusion,
         31,
         observer
      );

      assertTrue(full.complete());
      assertTrue(full.blocks().size() > 31);
      assertFalse(limited.complete());
      assertEquals(31, limited.blocks().size(), "the incomplete result must carry the one-past-limit sentinel size");
      assertTrue(limited.blocks().containsAll(limited.shell()));
      assertTrue(observer.generated.isEmpty(), "an over-limit attempt must not publish a prefix");
   }

   @Test
   void exactCompleteVolumeLimitStillReportsCompleteWithTheExactFormalShell() {
      List<Vec3> base = List.of(
         center(0, 0, 0),
         center(4, 0, 0),
         center(4, 0, 3),
         center(0, 0, 3)
      );
      Vec3 extrusion = new Vec3(0.0, 4.0, 0.0);
      BresenhamColumnVolume.Result unrestricted = generate(base, extrusion, 10_000);
      assertTrue(unrestricted.complete());

      BresenhamColumnVolume.Result exactLimit = generate(base, extrusion, unrestricted.blocks().size());

      assertTrue(exactLimit.complete(), "an exact complete-volume limit must not be reported as truncation");
      assertEquals(unrestricted.blocks(), exactLimit.blocks());
      assertEquals(independentlyRasterizedShell(base, extrusion), exactLimit.shell());
   }

   @Test
   void faceConstraintFailureIsTypedAndNeverPublishesOldFallbackGeometry() {
      List<Vec3> base = parallelogram(
         center(0, 0, 0),
         new Vec3(0.0, -1.0, 4.0),
         new Vec3(1.0, -1.0, 1.0)
      );
      Vec3 extrusion = new Vec3(-2.0, -1.0, -1.0);

      RecordingObserver observer = new RecordingObserver();
      BresenhamColumnVolume.Result failed = BresenhamColumnVolume.forceFaceCandidateFailureForTesting(
         base,
         extrusion,
         100_000,
         observer,
         LineTieBias.DEFAULT
      );

      assertFalse(failed.complete());
      assertTrue(GenerationFailed.is(failed.blocks()));
      assertTrue(failed.shell().isEmpty());
      assertTrue(observer.generated.isEmpty(), "a failed face published old fallback geometry");

      RecordingObserver limitedObserver = new RecordingObserver();
      int limit = 1;
      BresenhamColumnVolume.Result limited = BresenhamColumnVolume.forceFaceCandidateFailureForTesting(
         base,
         extrusion,
         limit,
         limitedObserver,
         LineTieBias.DEFAULT
      );
      assertFalse(limited.complete());
      assertTrue(GenerationLimitExceeded.is(limited.blocks()), "insufficient work budget must remain a typed limit");
      assertFalse(GenerationFailed.is(limited.blocks()));
      assertTrue(limitedObserver.generated.isEmpty(), "a failed limited face published geometry");
   }

   @Test
   void immutableConvexCoreCanMakeAnOwnedCapVoxelImpossibleToKeepOnThePhysicalBoundary() {
      Vec3 first = new Vec3(7.0, -5.0, -7.0);
      Vec3 second = new Vec3(4.0, -8.0, 3.0);
      Vec3 extrusion = new Vec3(7.0, 3.0, -4.0);
      List<Vec3> base = parallelogram(center(0, 0, 0), first, second);
      Set<BlockPos> target = canonicalTarget(base, extrusion);
      Set<BlockPos> owned = independentlyRasterizedShell(base, extrusion);
      LinkedHashSet<BlockPos> immutableSeed = new LinkedHashSet<>(target);
      immutableSeed.addAll(owned);
      Set<BlockPos> alreadyBuried = difference(owned, sixNeighborBoundary(immutableSeed));

      assertFalse(alreadyBuried.isEmpty(), "the witness must retain an already-buried formal surface voxel");

      BresenhamColumnVolume.Result result = generate(base, extrusion, 100_000);
      Set<BlockPos> hollow = TiltedBoxGenerator.generate(base, extrusion, FillMode.HOLLOW, 100_000);
      assertTrue(result.complete());
      assertTrue(result.blocks().containsAll(immutableSeed), "the generator must not carve the immutable core");
      assertFalse(
         sixNeighborBoundary(result.blocks()).containsAll(owned),
         "an additions-only algorithm cannot expose the already-buried owned voxel"
      );
      assertEquals(sixNeighborBoundary(result.blocks()), hollow);
   }

   @Test
   void forcedAffineFallbackIsRejectedWithoutPublishingOldGeometry() {
      List<Vec3> base = parallelogram(
         center(1, -2, 3),
         new Vec3(4.0, 0.0, 0.0),
         new Vec3(0.0, 0.0, 3.0)
      );
      Vec3 extrusion = new Vec3(0.0, 5.0, 0.0);
      RecordingObserver observer = new RecordingObserver();
      BresenhamColumnVolume.Result failed = BresenhamColumnVolume.forceAffineFallbackForTesting(
         base,
         extrusion,
         100_000,
         observer,
         LineTieBias.DEFAULT
      );

      assertFalse(failed.complete());
      assertTrue(GenerationFailed.is(failed.blocks()));
      assertTrue(failed.shell().isEmpty());
      assertTrue(observer.generated.isEmpty(), "a rejected affine fallback published geometry");
   }

   @Test
   void forcedAffineSolidAndBoundaryBothReturnTheSameTypedFailure() {
      List<Vec3> base = parallelogram(
         center(1, -2, 3),
         new Vec3(4.0, 0.0, 0.0),
         new Vec3(0.0, 0.0, 3.0)
      );
      Vec3 extrusion = new Vec3(0.0, 5.0, 0.0);
      BresenhamColumnVolume.Result solid = BresenhamColumnVolume.forceAffineFallbackForTesting(
         base, extrusion, 100_000, BlockGenerationObserver.NONE, LineTieBias.DEFAULT
      );
      BresenhamColumnVolume.Result boundary = BresenhamColumnVolume.forceAffineFallbackBoundaryForTesting(
         base, extrusion, 100_000, BlockGenerationObserver.NONE, LineTieBias.DEFAULT
      );

      assertFalse(solid.complete());
      assertFalse(boundary.complete());
      assertTrue(GenerationFailed.is(solid.blocks()));
      assertTrue(GenerationFailed.is(boundary.blocks()));
      assertTrue(solid.shell().isEmpty());
      assertTrue(boundary.shell().isEmpty());
   }

   @Test
   void sixIndependentFaceVolumeIsSignedAxisAndWindingEquivariant() {
      List<Vec3> base = parallelogram(
         center(1, -2, 3),
         new Vec3(6.0, 0.0, 2.0),
         new Vec3(-2.0, 0.0, 6.0)
      );
      Vec3 extrusion = new Vec3(0.0, 5.0, 0.0);
      BresenhamColumnVolume.Result normal = generate(base, extrusion, 100_000);
      assertTrue(normal.complete());
      Set<BlockPos> normalReference = normal.blocks();
      int[][] orders = {
         {0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}
      };
      for (int[] order : orders) {
         for (int mask = 0; mask < 8; mask++) {
            int[] signs = {
               (mask & 1) == 0 ? -1 : 1,
               (mask & 2) == 0 ? -1 : 1,
               (mask & 4) == 0 ? -1 : 1
            };
            List<Vec3> transformedBase = base.stream()
               .map(point -> transform(point, order, signs))
               .toList();
            Vec3 transformedExtrusion = transform(extrusion, order, signs);
            Set<BlockPos> normalActual = generate(transformedBase, transformedExtrusion, 100_000).blocks();
            Set<BlockPos> normalExpected = normalReference.stream()
               .map(position -> transformCenter(position, order, signs))
               .collect(java.util.stream.Collectors.toSet());
            assertEquals(
               normalExpected,
               normalActual,
               "normal six-face order=" + java.util.Arrays.toString(order) + " mask=" + mask
            );
         }
      }

      for (int start = 0; start < 4; start++) {
         for (boolean reversed : List.of(false, true)) {
            ArrayList<Vec3> winding = new ArrayList<>(4);
            for (int offset = 0; offset < 4; offset++) {
               int direction = reversed ? -offset : offset;
               winding.add(base.get(Math.floorMod(start + direction, 4)));
            }
            assertEquals(
               normalReference,
               generate(winding, extrusion, 100_000).blocks(),
               "normal six-face start=" + start + " reversed=" + reversed
            );
         }
      }
   }

   @Test
   void laterColumnAxisCanFitWhenTheDominantAxisExceedsTheBudget() {
      List<Vec3> base = parallelogram(
         center(0, 0, 0),
         new Vec3(-4.0, 0.0, -3.0),
         new Vec3(2.0, 0.0, -3.0)
      );
      Vec3 extrusion = new Vec3(-4.0, 2.0, 4.0);

      BresenhamColumnVolume.Result unrestricted = generate(base, extrusion, 100_000);
      assertTrue(unrestricted.complete());
      BresenhamColumnVolume.Result result = generate(base, extrusion, unrestricted.blocks().size());

      assertTrue(result.complete(), "a later safe axis fits the exact budget");
      assertEquals(unrestricted.blocks(), result.blocks());
      assertTrue(fiveSidedOpenGaps(result.blocks()).isEmpty());
      assertFalse(hasEnclosedAir(result.blocks()));
   }

   @Test
   void faceWorkCapFallsBackBeforeAllocatingAMillionCellFaceAndKeepsLimitAtomic() {
      List<Vec3> base = parallelogram(
         center(0, 0, 0),
         new Vec3(1_000.0, 0.0, 0.0),
         new Vec3(0.0, 0.0, 1_000.0)
      );
      Vec3 extrusion = new Vec3(0.0, 1.0, 0.0);
      RecordingObserver solidObserver = new RecordingObserver();
      RecordingObserver boundaryObserver = new RecordingObserver();

      BresenhamColumnVolume.Result solid = BresenhamColumnVolume.generate(
         base, extrusion, 16, solidObserver, LineTieBias.DEFAULT
      );
      BresenhamColumnVolume.Result boundary = BresenhamColumnVolume.generateBoundary(
         base, extrusion, 16, boundaryObserver, LineTieBias.DEFAULT
      );

      assertFalse(solid.complete());
      assertFalse(boundary.complete());
      assertTrue(GenerationLimitExceeded.is(solid.blocks()));
      assertTrue(GenerationLimitExceeded.is(boundary.blocks()));
      assertTrue(solidObserver.generated.isEmpty());
      assertTrue(boundaryObserver.generated.isEmpty());
      assertEquals(0L, solidObserver.scanned, "face fallback must happen before face/offset enumeration");
      assertEquals(0L, boundaryObserver.scanned, "face fallback must happen before face/offset enumeration");
   }

   @Test
   void hugeDiagonalExtrusionFallsBackBeforeQuadraticSpanScanningAndKeepsLimitAtomic() {
      List<Vec3> base = parallelogram(
         center(0, 0, 0),
         new Vec3(1.0, 0.0, 0.0),
         new Vec3(0.0, 1.0, 0.0)
      );
      Vec3 extrusion = new Vec3(500_000.0, 500_000.0, 500_000.0);
      GuardedObserver solidObserver = new GuardedObserver(1_600_100L);
      GuardedObserver boundaryObserver = new GuardedObserver(1_600_100L);

      BresenhamColumnVolume.Result solid = BresenhamColumnVolume.generate(
         base, extrusion, 16, solidObserver, LineTieBias.DEFAULT
      );
      BresenhamColumnVolume.Result boundary = BresenhamColumnVolume.generateBoundary(
         base, extrusion, 16, boundaryObserver, LineTieBias.DEFAULT
      );

      assertFalse(solid.complete());
      assertFalse(boundary.complete());
      assertTrue(GenerationLimitExceeded.is(solid.blocks()));
      assertTrue(GenerationLimitExceeded.is(boundary.blocks()));
      assertEquals(16, solid.blocks().size());
      assertEquals(16, boundary.blocks().size());
      assertTrue(solidObserver.generated.isEmpty(), "solid fallback published a prefix");
      assertTrue(boundaryObserver.generated.isEmpty(), "boundary fallback published a prefix");
      assertEquals(0L, solidObserver.scanned, "solid fallback materialized face, edge, or span work");
      assertEquals(0L, boundaryObserver.scanned, "boundary fallback materialized face, edge, or span work");
      assertTrue(solidObserver.checks > 0L);
      assertTrue(boundaryObserver.checks > 0L);
   }

   @Test
   void cappedSixFaceFallbackDoesNotStretchAnEmptyCoreColumnAcrossTheVolume() {
      Vec3 first = new Vec3(0.0, 900.0, 0.0);
      Vec3 second = new Vec3(1.0, 0.0, 1.0);
      Vec3 extrusion = new Vec3(1_201.0, 0.0, 1_200.0);
      List<Vec3> base = parallelogram(center(0, 0, 0), first, second);
      List<Vec3> hull = new ArrayList<>(base);
      base.stream().map(point -> point.add(extrusion)).forEach(hull::add);

      assertTrue(
         ArbitraryConvexPolyhedronGenerator.estimateSolidSpanScanColumns(
            hull, 0, 1_000_000L, BlockGenerationObserver.NONE
         ) > 1_000_000L,
         "the first extrusion-slope axis must exercise the work-budget skip"
      );
      assertTrue(
         ArbitraryConvexPolyhedronGenerator.estimateSolidSpanScanColumns(
            hull, 1, 1_000_000L, BlockGenerationObserver.NONE
         ) <= 1_000_000L,
         "the later Y axis must remain eligible"
      );

      int exactLimit = 9_608;
      RecordingObserver observer = new RecordingObserver();
      BresenhamColumnVolume.Result result = BresenhamColumnVolume.generate(
         base,
         extrusion,
         exactLimit,
         observer
      );

      assertTrue(
         !result.complete() && GenerationLimitExceeded.is(result.blocks()),
         "an independently rasterized million-cell side must fail atomically at the placement budget"
      );
      assertEquals(exactLimit, result.blocks().size());
      assertTrue(observer.generated.isEmpty(), "an over-limit independent face published a prefix");
   }

   @Test
   void formalAndCanonicalPhaseOverlayDoesNotLeaveAFiveSidedOpenGap() {
      List<Vec3> base = parallelogram(
         center(0, 0, 0),
         new Vec3(0.0, -1.0, 4.0),
         new Vec3(1.0, -1.0, 1.0)
      );
      Vec3 extrusion = new Vec3(-2.0, -1.0, -1.0);
      BlockPos gap = new BlockPos(-1, -1, 2);
      BresenhamColumnVolume.Result result = generate(base, extrusion, 100_000);
      Set<BlockPos> target = canonicalTarget(base, extrusion);

      assertTrue(result.complete());
      assertTrue(result.blocks().containsAll(corners(base, extrusion)));
      assertFalse(isFiveSidedOpenGap(target, gap, Direction.WEST), "canonical target already has the same five-sided gap");
      assertFalse(
         isFiveSidedOpenGap(result.blocks(), gap, Direction.WEST),
         () -> "formal/canonical phase overlay left an open gap in a " + result.blocks().size() + "-block solid"
      );
      assertTrue(fiveSidedOpenGaps(result.blocks()).isEmpty(), () -> "five-sided gaps " + fiveSidedOpenGaps(result.blocks()));
      assertFalse(hasEnclosedAir(result.blocks()));
   }

   @Test
   void intervalCavityCheckDistinguishesAClosedCubeFromAnOpenOne() {
      LinkedHashSet<BlockPos> sideWalls = new LinkedHashSet<>();
      for (int x = 0; x <= 2; x++) {
         for (int y = 0; y <= 2; y++) {
            for (int z = 0; z <= 2; z++) {
               if (y == 0 || y == 2 || z == 0 || z == 2) {
                  sideWalls.add(new BlockPos(x, y, z));
               }
            }
         }
      }
      Set<BlockPos> closedCaps = Set.of(
         new BlockPos(0, 1, 1),
         new BlockPos(2, 1, 1)
      );
      Set<BlockPos> openCap = Set.of(new BlockPos(2, 1, 1));

      assertTrue(BresenhamColumnVolume.hasEnclosedAirFromColumnPartsForTesting(
         sideWalls, closedCaps, 0
      ));
      assertFalse(BresenhamColumnVolume.hasEnclosedAirFromColumnPartsForTesting(
         sideWalls, openCap, 0
      ));
   }

   @Test
   void dominantColumnFillDoesNotCreateFirstRandomFiveNeighborCandidate() {
      assertNoFiveNeighborOpenings(
         new Vec3(2.0, -1.0, 4.0),
         new Vec3(-1.0, 4.0, -1.0),
         new Vec3(-2.0, -4.0, 2.0),
         new BlockPos(0, -1, 1),
         "first-random-candidate"
      );
   }

   @Test
   void dominantColumnFillDoesNotCreateSecondRandomFiveNeighborCandidate() {
      assertNoFiveNeighborOpenings(
         new Vec3(-1.0, -4.0, 4.0),
         new Vec3(-4.0, 0.0, 1.0),
         new Vec3(2.0, 3.0, -1.0),
         new BlockPos(1, 1, 0),
         "second-random-candidate"
      );
   }

   @Test
   void spanBoundaryAndFiveNeighborChecksMatchBruteForceOnRandomColumnConvexSolids() {
      Random random = new Random(0x5350414E42524635L);
      for (int axis = 0; axis < 3; axis++) {
         for (int sample = 0; sample < 256; sample++) {
            LinkedHashSet<BlockPos> solid = new LinkedHashSet<>();
            for (int first = -3; first <= 3; first++) {
               for (int second = -3; second <= 3; second++) {
                  if (random.nextInt(4) == 0) {
                     continue;
                  }
                  int minimum = random.nextInt(-4, 4);
                  int maximum = minimum + random.nextInt(0, 6);
                  for (int coordinate = minimum; coordinate <= maximum; coordinate++) {
                     solid.add(columnPosition(axis, first, second, coordinate));
                  }
               }
            }

            Set<BlockPos> expectedBoundary = sixNeighborBoundary(solid);
            Set<BlockPos> actualBoundary = BresenhamColumnVolume.boundaryFromSpansForTesting(solid, axis);
            long expectedOpenings = fiveSidedOpenGaps(solid).size();
            long actualOpenings = BresenhamColumnVolume.fiveNeighborOpeningsFromSpansForTesting(solid, axis);

            assertEquals(expectedBoundary, actualBoundary, "axis=" + axis + " sample=" + sample);
            assertTrue(BresenhamColumnVolume.usesLazyColumnStorageForTesting(actualBoundary));
            assertEquals(expectedOpenings, actualOpenings, "axis=" + axis + " sample=" + sample);
         }
      }
   }

   private static void assertNoFiveNeighborOpenings(
      Vec3 first,
      Vec3 second,
      Vec3 extrusion,
      BlockPos candidate,
      String name
   ) {
      List<Vec3> base = parallelogram(center(0, 0, 0), first, second);
      BresenhamColumnVolume.Result result = generate(base, extrusion, 100_000);
      assertTrue(result.complete(), name);
      assertFalse(hasEnclosedAir(result.blocks()), name);
      assertTrue(
         fiveSidedOpenGaps(result.blocks()).isEmpty(),
         () -> name + " candidate=" + candidate + " gaps=" + fiveSidedOpenGaps(result.blocks())
      );
   }

   private static void assertVolumeContract(List<Vec3> base, Vec3 extrusion, String name) {
      try {
         BresenhamColumnVolume.Result result = generate(base, extrusion, 100_000);
         Set<BlockPos> expectedShell = independentlyRasterizedShell(base, extrusion);
         Set<BlockPos> target = canonicalTarget(base, extrusion);
         Set<BlockPos> derivedHollow = sixNeighborBoundary(result.blocks());

         assertTrue(result.complete());
         assertFalse(result.blocks().isEmpty());
         assertEquals(expectedShell, result.shell(), "result shell differs from six independent face rasterizations");
         assertTrue(result.blocks().containsAll(expectedShell), "solid lost an independently rasterized face voxel");
         assertTrue(result.blocks().containsAll(corners(base, extrusion)), "solid lost a box corner");
         assertTrue(result.blocks().containsAll(target), "solid contains an open canonical-volume gap");
         assertTrue(result.blocks().containsAll(result.shell()), "shell escaped solid");
         assertTrue(result.blocks().containsAll(derivedHollow));
         assertFalse(derivedHollow.isEmpty());
         assertFalse(hasEnclosedAir(result.blocks()));
         assertTrue(fiveSidedOpenGaps(result.blocks()).isEmpty(), () -> "five-sided open gaps " + fiveSidedOpenGaps(result.blocks()));
      } catch (AssertionError error) {
         throw new AssertionError(name + " base=" + base + " extrusion=" + extrusion + ": " + error.getMessage(), error);
      }
   }

   private static BresenhamColumnVolume.Result generate(List<Vec3> base, Vec3 extrusion, int maxBlocks) {
      return BresenhamColumnVolume.generate(
         base,
         extrusion,
         maxBlocks,
         BlockGenerationObserver.NONE
      );
   }

   private static Set<BlockPos> independentlyRasterizedShell(List<Vec3> base, Vec3 extrusion) {
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      for (List<Vec3> face : boxFaces(base, extrusion)) {
         ProjectedBresenhamFace.Frame frame = ProjectedBresenhamFace.Frame.create(face);
         assertNotNull(frame, "volume shell contains a degenerate face " + face);
         BresenhamFaceSweep.Attempt attempt = BoundaryInterpolatedFaceRasterizer.attempt(
            frame,
            LineTieBias.DEFAULT,
            100_000,
            BlockGenerationObserver.NONE
         );
         assertTrue(attempt.succeeded(), "independent volume face failed " + face + " status=" + attempt.status());
         BresenhamFaceSweep.Result rasterized = attempt.result();
         assertFalse(rasterized.fill().isEmpty(), "volume shell face is empty " + face);
         Set<BlockPos> clipped = clipToOwnedBoundary(frame, rasterized.fill(), rasterized.outline());
         assertTrue(clipped.containsAll(rasterized.outline()), "a face lost its natural outline " + face);
         assertTrue(clipped.containsAll(face.stream().map(BlockPos::containing).toList()), "a face lost a corner " + face);
         result.addAll(clipped);
      }
      return result;
   }

   private static Set<BlockPos> clipToOwnedBoundary(
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

   private static Set<BlockPos> canonicalTarget(List<Vec3> base, Vec3 extrusion) {
      java.util.ArrayList<Vec3> vertices = new java.util.ArrayList<>(8);
      vertices.addAll(base);
      base.forEach(point -> vertices.add(point.add(extrusion)));
      return ArbitraryConvexPolyhedronGenerator.generate(vertices, FillMode.SOLID, 100_000);
   }

   private static Set<BlockPos> corners(List<Vec3> base, Vec3 extrusion) {
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      for (Vec3 vertex : base) {
         result.add(BlockPos.containing(vertex));
         result.add(BlockPos.containing(vertex.add(extrusion)));
      }
      return result;
   }

   private static Set<BlockPos> sixNeighborBoundary(Set<BlockPos> solid) {
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

   private static Set<BlockPos> difference(Set<BlockPos> expected, Set<BlockPos> actual) {
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>(expected);
      result.removeAll(actual);
      return result;
   }

   private static boolean isFiveSidedOpenGap(
      Set<BlockPos> blocks,
      BlockPos gap,
      Direction openDirection
   ) {
      return !blocks.contains(gap)
         && !blocks.contains(gap.relative(openDirection))
         && java.util.Arrays.stream(Direction.values())
            .filter(direction -> direction != openDirection)
            .allMatch(direction -> blocks.contains(gap.relative(direction)));
   }

   private static boolean hasEnclosedAir(Set<BlockPos> solid) {
      if (solid.isEmpty()) {
         return false;
      }
      int minimumX = solid.stream().mapToInt(BlockPos::getX).min().orElseThrow() - 1;
      int minimumY = solid.stream().mapToInt(BlockPos::getY).min().orElseThrow() - 1;
      int minimumZ = solid.stream().mapToInt(BlockPos::getZ).min().orElseThrow() - 1;
      int maximumX = solid.stream().mapToInt(BlockPos::getX).max().orElseThrow() + 1;
      int maximumY = solid.stream().mapToInt(BlockPos::getY).max().orElseThrow() + 1;
      int maximumZ = solid.stream().mapToInt(BlockPos::getZ).max().orElseThrow() + 1;
      HashSet<BlockPos> exterior = new HashSet<>();
      ArrayDeque<BlockPos> open = new ArrayDeque<>();
      open.add(new BlockPos(minimumX, minimumY, minimumZ));
      while (!open.isEmpty()) {
         BlockPos current = open.removeFirst();
         if (!exterior.add(current)) {
            continue;
         }
         for (Direction direction : Direction.values()) {
            BlockPos neighbor = current.relative(direction);
            if (neighbor.getX() >= minimumX && neighbor.getX() <= maximumX
               && neighbor.getY() >= minimumY && neighbor.getY() <= maximumY
               && neighbor.getZ() >= minimumZ && neighbor.getZ() <= maximumZ
               && !solid.contains(neighbor)
               && !exterior.contains(neighbor)) {
               open.addLast(neighbor);
            }
         }
      }
      for (int x = minimumX; x <= maximumX; x++) {
         for (int y = minimumY; y <= maximumY; y++) {
            for (int z = minimumZ; z <= maximumZ; z++) {
               BlockPos position = new BlockPos(x, y, z);
               if (!solid.contains(position) && !exterior.contains(position)) {
                  return true;
               }
            }
         }
      }
      return false;
   }

   private static List<Vec3> parallelogram(Vec3 origin, Vec3 first, Vec3 second) {
      return List.of(origin, origin.add(first), origin.add(first).add(second), origin.add(second));
   }

   private static BlockPos columnPosition(int axis, int first, int second, int coordinate) {
      int[] values = new int[3];
      values[axis] = coordinate;
      values[(axis + 1) % 3] = first;
      values[(axis + 2) % 3] = second;
      return new BlockPos(values[0], values[1], values[2]);
   }

   private static Vec3 transform(Vec3 vector, int[] order, int[] signs) {
      double[] coordinates = {vector.x, vector.y, vector.z};
      return new Vec3(
         signs[0] * coordinates[order[0]],
         signs[1] * coordinates[order[1]],
         signs[2] * coordinates[order[2]]
      );
   }

   private static BlockPos transformCenter(BlockPos position, int[] order, int[] signs) {
      return BlockPos.containing(transform(Vec3.atCenterOf(position), order, signs));
   }

   private static Vec3 center(int x, int y, int z) {
      return new Vec3(x + 0.5, y + 0.5, z + 0.5);
   }

   private static Vec3 randomCenter(Random random) {
      return center(random.nextInt(-8, 9), random.nextInt(-8, 9), random.nextInt(-8, 9));
   }

   private static Vec3 fullyTiltedVector(Random random, int minimum, int maximum) {
      return new Vec3(
         nonZero(random, minimum, maximum),
         nonZero(random, minimum, maximum),
         nonZero(random, minimum, maximum)
      );
   }

   private static Vec3[] partialTiltBasis(int zeroAxis, int first, int second, int height) {
      return switch (zeroAxis) {
         case 0 -> new Vec3[]{new Vec3(0.0, second, -first), new Vec3(height, 0.0, 0.0)};
         case 1 -> new Vec3[]{new Vec3(second, 0.0, -first), new Vec3(0.0, height, 0.0)};
         default -> new Vec3[]{new Vec3(second, -first, 0.0), new Vec3(0.0, 0.0, height)};
      };
   }

   private static boolean allComponentsNonZero(Vec3 vector) {
      return vector.x != 0.0 && vector.y != 0.0 && vector.z != 0.0;
   }

   private static int nonZero(Random random, int minimum, int maximum) {
      int magnitude = random.nextInt(minimum, maximum + 1);
      return random.nextBoolean() ? magnitude : -magnitude;
   }

   private record FaceColumn(int u, int v) {
   }

   private static final class RecordingObserver implements BlockGenerationObserver {
      private final List<BlockPos> generated = new ArrayList<>();
      private long scanned;

      @Override
      public void onScanned(long amount) {
         this.scanned += amount;
      }

      @Override
      public void onGenerated(BlockPos position) {
         this.generated.add(position);
      }
   }

   private static final class GuardedObserver implements BlockGenerationObserver {
      private final long maximumChecks;
      private final List<BlockPos> generated = new ArrayList<>();
      private long scanned;
      private long checks;

      private GuardedObserver(long maximumChecks) {
         this.maximumChecks = maximumChecks;
      }

      @Override
      public void onScanned(long amount) {
         this.scanned += amount;
      }

      @Override
      public void onGenerated(BlockPos position) {
         this.generated.add(position);
      }

      @Override
      public void checkCancelled() {
         assertTrue(++this.checks <= this.maximumChecks, "generation exceeded its bounded preflight work");
      }
   }

   private static Vec3 vector(int firstAxis, int firstValue, int secondAxis, int secondValue) {
      double[] values = {0.0, 0.0, 0.0};
      values[firstAxis] = firstValue;
      values[secondAxis] = secondValue;
      return new Vec3(values[0], values[1], values[2]);
   }

}
