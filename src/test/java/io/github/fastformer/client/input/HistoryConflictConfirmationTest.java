package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;
import io.github.fastformer.network.payload.world.HistoryConflictPayload;
import io.github.fastformer.network.payload.world.HistoryConflictResponsePayload.Choice;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HistoryConflictConfirmationTest {
   @AfterEach void clear() { HistoryConflictConfirmation.clear(); }

   @Test void enterAndControlEnterSelectDifferentConflictPolicies() {
      for (int key : List.of(257, 335)) {
         assertEquals(Choice.OVERWRITE, HistoryConflictConfirmation.choice(key, false));
         assertEquals(Choice.SKIP, HistoryConflictConfirmation.choice(key, true));
      }
      assertEquals(Choice.CANCEL, HistoryConflictConfirmation.choice(81, false));
      assertNull(HistoryConflictConfirmation.choice(90, true));
   }

   @Test void lateClearCannotDismissANewerPrompt() {
      UUID old = UUID.randomUUID(); UUID current = UUID.randomUUID();
      HistoryConflictConfirmation.receive(payload(old, 1, List.of(BlockPos.ZERO)));
      HistoryConflictConfirmation.receive(payload(current, 1, List.of(new BlockPos(2, 0, 0))));
      HistoryConflictConfirmation.receive(payload(old, 0, List.of()));
      assertEquals(current, HistoryConflictConfirmation.pending().token());
      assertEquals(List.of(new BlockPos(2, 0, 0)), HistoryConflictConfirmation.positions());
      HistoryConflictConfirmation.receive(payload(current, 0, List.of()));
      assertNull(HistoryConflictConfirmation.pending());
      assertTrue(HistoryConflictConfirmation.positions().isEmpty());
   }

   @Test void highlightChunksAccumulateWithinOneConfirmation() {
      UUID token = UUID.randomUUID();
      HistoryConflictConfirmation.receive(payload(token, 2, List.of(BlockPos.ZERO)));
      HistoryConflictConfirmation.receive(payload(token, 2, List.of(new BlockPos(2, 0, 0))));
      assertEquals(2, HistoryConflictConfirmation.positions().size());
      HistoryConflictConfirmation.clear();
      assertTrue(HistoryConflictConfirmation.positions().isEmpty());
   }

   private static HistoryConflictPayload payload(UUID token, int total, List<BlockPos> positions) {
      return new HistoryConflictPayload(token, ResourceLocation.withDefaultNamespace("overworld"), total, positions);
   }
}
