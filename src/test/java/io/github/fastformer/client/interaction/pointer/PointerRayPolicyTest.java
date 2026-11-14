package io.github.fastformer.client.interaction.pointer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PointerRayPolicyTest {
   @Test
   void preservesPreviewTransitionPrioritiesForEveryCombination() {
      for (int mask = 0; mask < 512; mask++) {
         boolean geometry = bit(mask, 0), building = bit(mask, 1), remote = bit(mask, 2);
         boolean workspace = bit(mask, 3), retained = bit(mask, 4), draft = bit(mask, 5);
         boolean empty = bit(mask, 6), placeable = bit(mask, 7), remoteActive = bit(mask, 8);
         int owner = geometry ? 3 : building ? 2 : remote || workspace ? 1 : 0;
         boolean session = remoteActive || retained;
         boolean placement = owner == 2 || owner == 0 && !session && !draft && placeable;
         boolean selection = owner == 1 || session || draft || owner == 0 && empty;
         var expected = selection ? PointerRayPolicy.SELECTION
            : placement ? PointerRayPolicy.PLACEMENT : PointerRayPolicy.OUTLINE;
         // Remote preview and accepted selection activity are published together.
         if (remote != remoteActive) continue;
         assertEquals(expected, PointerRayPolicy.select(geometry, building,
            workspace, session || draft, empty, placeable), "facts=" + mask);
      }
   }

   private static boolean bit(int mask, int bit) { return (mask & (1 << bit)) != 0; }
}
