package io.github.fastformer.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
