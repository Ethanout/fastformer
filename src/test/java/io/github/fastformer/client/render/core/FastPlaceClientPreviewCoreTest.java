package io.github.fastformer.client.render.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class FastPlaceClientPreviewCoreTest {
   @Test
   void cachedShellAppliesPreviewOpacityExactlyOnce() {
      assertEquals(0.40F, FastPlaceClientPreviewCore.previewAlpha(0.80F, 0.50F), 1.0E-6F);
      assertEquals(0.80F, FastPlaceClientPreviewCore.previewAlpha(0.80F, 1.0F), 1.0E-6F);
   }

   @Test
   void initialLineFallbackKeepsTheConfirmedPointAndCurrentCandidate() {
      BlockPos confirmed = new BlockPos(2, 64, 3);
      BlockPos candidate = new BlockPos(8, 64, 3);

      assertEquals(
         Set.of(confirmed, candidate),
         FastPlaceClientPreviewCore.endpointFallbackBlocks(
            1, List.of(confirmed, candidate), FastPlaceClientPreviewCore.BuildingShellVisibility.NONE
         )
      );
   }

   @Test
   void detailedConfirmedShellDoesNotHideMissingCandidateFallback() {
      BlockPos confirmed = BlockPos.ZERO;
      BlockPos candidate = new BlockPos(1, 1, 1);

      assertEquals(
         Set.of(candidate),
         FastPlaceClientPreviewCore.endpointFallbackBlocks(
            1,
            List.of(confirmed, candidate),
            new FastPlaceClientPreviewCore.BuildingShellVisibility(true, false)
         )
      );
   }

   @Test
   void confirmedLineKeepsEndpointBlocksUntilItsDetailedShellAppears() {
      BlockPos first = BlockPos.ZERO;
      BlockPos second = new BlockPos(8, 0, 0);

      assertEquals(
         Set.of(first, second),
         FastPlaceClientPreviewCore.endpointFallbackBlocks(
            2, List.of(first, second), FastPlaceClientPreviewCore.BuildingShellVisibility.NONE
         )
      );
      assertEquals(
         Set.of(),
         FastPlaceClientPreviewCore.endpointFallbackBlocks(
            2,
            List.of(first, second),
            new FastPlaceClientPreviewCore.BuildingShellVisibility(true, false)
         )
      );
   }
}
