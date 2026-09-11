package io.github.fastformer.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class ShapeShellMeshTest {
   private static final ShapeShellMesh.Part CUBE = new ShapeShellMesh.Part(
      List.of(new AABB(0, 0, 0, 1, 1, 1)),
      ShapeShellMesh.Color.WHITE,
      ShapeShellMesh.Color.BLACK,
      true
   );

   @Test
   void singleCubeHasSixFacesAndTwelveMergedEdges() {
      ShapeShellMesh.Mesh mesh = ShapeShellMesh.build(List.of(CUBE));

      assertEquals(6, mesh.faces().size());
      assertEquals(12, mesh.edges().size());
   }

   @Test
   void adjacentCubesDeleteSharedFacesAndMergeCollinearEdges() {
      ShapeShellMesh.Part adjacent = new ShapeShellMesh.Part(
         List.of(new AABB(1, 0, 0, 2, 1, 1)),
         ShapeShellMesh.Color.WHITE,
         ShapeShellMesh.Color.BLACK,
         true
      );

      ShapeShellMesh.Mesh mesh = ShapeShellMesh.build(List.of(CUBE, adjacent));

      assertEquals(10, mesh.faces().size());
      assertEquals(12, mesh.edges().size());
      assertTrue(mesh.edges().stream().anyMatch(edge -> edge.from().x == 0.0 && edge.to().x == 2.0));
   }

   @Test
   void duplicateShellCancelsEvenFacesAndEdges() {
      ShapeShellMesh.Mesh mesh = ShapeShellMesh.build(List.of(CUBE, CUBE));

      assertEquals(0, mesh.faces().size());
      assertEquals(0, mesh.edges().size());
   }

   @Test
   void specialPartKeepsColoredFacesWithoutOutline() {
      ShapeShellMesh.Color cyan = new ShapeShellMesh.Color(0.05F, 0.95F, 1.0F);
      ShapeShellMesh.Part special = new ShapeShellMesh.Part(
         List.of(new AABB(0, 0, 0, 1, 1, 1)),
         cyan,
         ShapeShellMesh.Color.BLACK,
         false
      );

      ShapeShellMesh.Mesh mesh = ShapeShellMesh.build(List.of(special));

      assertEquals(6, mesh.faces().size());
      assertEquals(0, mesh.edges().size());
      assertTrue(mesh.faces().stream().allMatch(face -> face.color().equals(cyan)));
   }

   @Test
   void pendingPartPreservesWhiteFacesAndOutline() {
      ShapeShellMesh.Part pending = new ShapeShellMesh.Part(
         List.of(new AABB(0, 0, 0, 1, 1, 1)),
         ShapeShellMesh.Color.WHITE,
         ShapeShellMesh.Color.WHITE,
         true
      );

      ShapeShellMesh.Mesh mesh = ShapeShellMesh.build(List.of(pending));

      assertTrue(mesh.faces().stream().allMatch(face -> face.color().equals(ShapeShellMesh.Color.WHITE)));
      assertTrue(mesh.edges().stream().allMatch(edge -> edge.color().equals(ShapeShellMesh.Color.WHITE)));
   }

   @Test
   void incrementalBuilderMatchesSynchronousBuildWithOneUnitSteps() {
      ShapeShellMesh.Part adjacent = new ShapeShellMesh.Part(
         List.of(new AABB(1, 0, 0, 2, 1, 1)),
         ShapeShellMesh.Color.WHITE,
         ShapeShellMesh.Color.BLACK,
         true
      );
      List<ShapeShellMesh.Part> parts = List.of(CUBE, adjacent);

      ShapeShellMesh.Builder builder = ShapeShellMesh.builder(parts);
      while (!builder.step(1)) {
         // Each call simulates one frame with the smallest useful budget.
      }

      assertEquals(10, builder.mesh().faces().size());
      assertEquals(12, builder.mesh().edges().size());
      assertTrue(builder.mesh().edges().stream().anyMatch(edge -> edge.from().x == 0.0 && edge.to().x == 2.0));
   }

   @Test
   void largeCoplanarSubdivisionYieldsBeforeCompletion() {
      java.util.ArrayList<ShapeShellMesh.Part> parts = new java.util.ArrayList<>();
      for (int x = 0; x < 64; x++) {
         parts.add(new ShapeShellMesh.Part(
            List.of(new AABB(x, 0, 0, x + 1, 1, 1)),
            ShapeShellMesh.Color.WHITE,
            ShapeShellMesh.Color.BLACK,
            true
         ));
      }

      ShapeShellMesh.Builder builder = ShapeShellMesh.builder(parts);

      assertFalse(builder.step(64));
      assertFalse(builder.step(1));
      while (!builder.step(7)) {
         // Continue with a deliberately small per-frame budget.
      }
      assertEquals(258, builder.mesh().faces().size());
      assertEquals(12, builder.mesh().edges().size());
      assertTrue(builder.mesh().edges().stream().anyMatch(edge -> edge.from().x == 0.0 && edge.to().x == 64.0));
   }

   @Test
   void quantizedZeroAreaBoxesProduceNoGeometry() {
      ShapeShellMesh.Part zeroWidth = new ShapeShellMesh.Part(
         List.of(new AABB(0, 0, 0, 0.0000004, 1, 1)),
         ShapeShellMesh.Color.WHITE,
         ShapeShellMesh.Color.BLACK,
         true
      );
      ShapeShellMesh.Part zeroHeight = new ShapeShellMesh.Part(
         List.of(new AABB(2, 0, 0, 3, 0.0000004, 1)),
         ShapeShellMesh.Color.WHITE,
         ShapeShellMesh.Color.BLACK,
         true
      );
      ShapeShellMesh.Builder builder = ShapeShellMesh.builder(List.of(zeroWidth, zeroHeight));

      while (!builder.step(1)) {
         // Exercise the zero-area skip through every incremental stage.
      }

      assertTrue(builder.mesh().faces().isEmpty());
      assertTrue(builder.mesh().edges().isEmpty());
   }

   @Test
   void emptyPartsConsumeTheirOwnWorkUnits() {
      java.util.ArrayList<ShapeShellMesh.Part> parts = new java.util.ArrayList<>();
      for (int index = 0; index < 32; index++) {
         parts.add(new ShapeShellMesh.Part(
            List.of(), ShapeShellMesh.Color.WHITE, ShapeShellMesh.Color.BLACK, true
         ));
      }
      ShapeShellMesh.Builder builder = ShapeShellMesh.builder(parts);

      assertFalse(builder.step(31));
      assertFalse(builder.complete());
      assertFalse(builder.step(1));
      assertTrue(builder.step(1));
   }
}
