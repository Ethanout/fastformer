package io.github.fastformer.client.render.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PreviewRenderOwnerTest {
   @Test
   void activeBuildingWinsOverAStaleOperationSnapshot() {
      assertEquals(
         PreviewRenderOwner.BUILDING,
         PreviewRenderOwner.select(true, true, false, false)
      );
   }

   @Test
   void workspaceOwnsRenderingWithoutAnActiveBuildingSession() {
      assertEquals(
         PreviewRenderOwner.OPERATION,
         PreviewRenderOwner.select(false, false, true, false)
      );
   }

   @Test
   void geometryKeepsItsExistingHighestPriority() {
      assertEquals(
         PreviewRenderOwner.GEOMETRY,
         PreviewRenderOwner.select(true, true, true, true)
      );
   }
}
