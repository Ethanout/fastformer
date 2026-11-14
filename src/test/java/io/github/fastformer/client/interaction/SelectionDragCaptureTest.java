package io.github.fastformer.client.interaction;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.interaction.selectiongesture.SelectionDragCapture;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SelectionDragCaptureTest {
   @Test
   void sourceMutationCannotChangeFrozenTargets() {
      var targets = new HashMap<Integer, Long>();
      targets.put(1, 7L);
      var capture = new SelectionDragCapture(UUID.randomUUID(), targets, 0, 1);
      targets.put(1, 8L);
      targets.put(2, 9L);
      assertEquals(Map.of(1, 7L), capture.targets());
      assertThrows(UnsupportedOperationException.class, () -> capture.targets().clear());
   }

   @Test
   void snapshotRetainsOwnerButtonAndPointerIdentity() {
      UUID owner = UUID.randomUUID();
      var capture = new SelectionDragCapture(owner, Map.of(1, 7L), 2, 8);
      assertEquals(2, capture.mouseButton());
      assertEquals(owner, capture.owner());
      assertEquals(8, capture.gestureToken());
   }

   @Test
   void invalidAuthorityFactsCannotBeCaptured() {
      UUID owner = UUID.randomUUID();
      assertThrows(NullPointerException.class, () -> new SelectionDragCapture(null, Map.of(1, 7L), 0, 1));
      assertThrows(IllegalArgumentException.class, () -> new SelectionDragCapture(owner, Map.of(), 0, 1));
      assertThrows(IllegalArgumentException.class, () -> new SelectionDragCapture(owner, Map.of(1, 7L), -1, 1));
      assertThrows(IllegalArgumentException.class, () -> new SelectionDragCapture(owner, Map.of(1, 7L), 0, 0));
   }
}
