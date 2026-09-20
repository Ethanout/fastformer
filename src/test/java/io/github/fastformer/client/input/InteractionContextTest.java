package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InteractionContextTest {
   @Test
   void ordinaryBuildingPreviewUsesDistanceVisibility() {
      assertFalse(InteractionContext.shouldForceFullPreviewVisibility(true, false, false, false));
   }

   @Test
   void operationAndSelectionSessionsStayFullyVisible() {
      assertTrue(InteractionContext.shouldForceFullPreviewVisibility(true, true, false, false));
      assertTrue(InteractionContext.shouldForceFullPreviewVisibility(true, false, true, false));
      assertTrue(InteractionContext.shouldForceFullPreviewVisibility(true, false, false, true));
   }

   @Test
   void missingPlayerUsesSafeFullVisibility() {
      assertTrue(InteractionContext.shouldForceFullPreviewVisibility(false, false, false, false));
   }

   @Test
   void aServerPreviewAndALocalDraftBothOwnThePointer() {
      assertTrue(InteractionContext.selectionOwnsPointer(true, false));
      assertTrue(InteractionContext.selectionOwnsPointer(false, true));
      assertTrue(InteractionContext.selectionOwnsPointer(true, true));
      assertFalse(InteractionContext.selectionOwnsPointer(false, false));
   }

   @Test
   void anEmptyHandYieldsToVanillaForABlockOrAnEntityInReach() {
      assertTrue(InteractionContext.vanillaOwnsEmptyHandClick(true, false));
      assertTrue(InteractionContext.vanillaOwnsEmptyHandClick(false, true));
      assertTrue(InteractionContext.vanillaOwnsEmptyHandClick(true, true));
      assertFalse(InteractionContext.vanillaOwnsEmptyHandClick(false, false));
   }
}
