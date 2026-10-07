package io.github.fastformer.client.interaction.intent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InteractionContextTest {
   @Test void previewsDoNotFadeWithDistance() {
      assertTrue(InteractionContext.previewVisibility(null, 0) == 1);
      assertTrue(InteractionContext.previewVisibility(null, 1) == 1);
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
