package io.github.fastformer.server.history.replay;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.protocol.s2c.world.HistoryConflictPayload;
import io.github.fastformer.server.history.entry.HistoryBounds;
import io.github.fastformer.server.history.entry.HistoryEntry;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class HistoryConflictPromptTest {
   @Test
   void highlightsUseAnImmutableConflictSnapshotAndBoundedPackets() {
      var audience = new TestAudience();
      BitSet conflicts = conflicts(HistoryConflictPayload.MAX_POSITIONS + 3);
      var prompt = HistoryConflictPrompt.begin(audience, new TestEntry(), conflicts).orElseThrow();
      conflicts.clear();

      assertEquals(HistoryConflictPayload.MAX_POSITIONS, audience.packets.getFirst().positions().size());
      assertEquals(HistoryConflictPayload.MAX_POSITIONS + 3, audience.packets.getFirst().total());
      assertTrue(prompt.tick());
      assertEquals(3, audience.packets.getLast().positions().size());
      assertEquals(new BlockPos(HistoryConflictPayload.MAX_POSITIONS, 0, 0), audience.packets.getLast().positions().getFirst());
      assertTrue(prompt.tick());
      assertEquals(2, audience.packets.size());
   }

   @Test
   void clearedAndPreviousTokensCannotAcceptResponsesOrClearAnotherPrompt() {
      var audience = new TestAudience();
      var first = HistoryConflictPrompt.begin(audience, new TestEntry(), conflicts(1)).orElseThrow();
      UUID oldToken = audience.packets.getFirst().token();
      assertTrue(first.accepts(oldToken));
      assertFalse(first.accepts(null));
      assertFalse(first.accepts(UUID.randomUUID()));
      first.clear();
      assertEquals(0, audience.packets.getLast().total());
      assertEquals(oldToken, audience.packets.getLast().token());
      assertFalse(first.accepts(oldToken));
      int packetsAfterClear = audience.packets.size();
      first.clear();
      assertFalse(first.tick());
      assertEquals(packetsAfterClear, audience.packets.size());

      var second = HistoryConflictPrompt.begin(audience, new TestEntry(), conflicts(1)).orElseThrow();
      assertFalse(second.accepts(oldToken));
      assertTrue(second.accepts(audience.packets.getLast().token()));
   }

   @Test
   void disconnectDimensionChangeAndLostChannelEndThePrompt() {
      for (int boundary = 0; boundary < 3; boundary++) {
         var audience = new TestAudience();
         var prompt = HistoryConflictPrompt.begin(audience, new TestEntry(), conflicts(1)).orElseThrow();
         UUID token = audience.packets.getFirst().token();
         if (boundary == 0) audience.dimension = null;
         if (boundary == 1) audience.dimension = Level.NETHER;
         if (boundary == 2) audience.supported = false;
         assertFalse(prompt.tick());
         assertFalse(prompt.accepts(token));
         assertEquals(0, audience.packets.getLast().total());
      }
   }

   @Test
   void unavailableAudienceAndEmptyConflictsDoNotOpenPrompt() {
      var audience = new TestAudience();
      assertTrue(HistoryConflictPrompt.begin(audience, new TestEntry(), conflicts(0)).isEmpty());
      audience.supported = false;
      assertTrue(HistoryConflictPrompt.begin(audience, new TestEntry(), conflicts(1)).isEmpty());
      audience.supported = true;
      audience.dimension = null;
      assertTrue(HistoryConflictPrompt.begin(audience, new TestEntry(), conflicts(1)).isEmpty());
      assertTrue(audience.packets.isEmpty());
      assertTrue(audience.reminders.isEmpty());
   }

   @Test
   void remindersKeepTheirExistingFortyTickCountdown() {
      var audience = new TestAudience();
      var prompt = HistoryConflictPrompt.begin(audience, new TestEntry(), conflicts(4)).orElseThrow();
      for (int tick = 0; tick < 40; tick++) assertTrue(prompt.tick());
      assertEquals(List.of(4), audience.reminders);
      assertTrue(prompt.tick());
      assertEquals(List.of(4, 4), audience.reminders);
   }

   private static BitSet conflicts(int count) {
      var conflicts = new BitSet();
      conflicts.set(0, count);
      return conflicts;
   }

   private static final class TestAudience implements HistoryConflictPrompt.Audience {
      private ResourceKey<Level> dimension = Level.OVERWORLD;
      private boolean supported = true;
      private final List<HistoryConflictPayload> packets = new ArrayList<>();
      private final List<Integer> reminders = new ArrayList<>();
      @Override public ResourceKey<Level> dimension() { return dimension; }
      @Override public boolean supportsPrompt() { return supported; }
      @Override public void send(HistoryConflictPayload payload) { packets.add(payload); }
      @Override public void remind(int count) { reminders.add(count); }
   }

   private record TestEntry() implements HistoryEntry {
      @Override public ResourceLocation type() { return ResourceLocation.fromNamespaceAndPath("test", "conflict"); }
      @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
      @Override public UUID operationId() { return null; }
      @Override public HistoryEntry withOperationId(UUID id) { return this; }
      @Override public int size() { return HistoryConflictPayload.MAX_POSITIONS + 3; }
      @Override public int estimatedBytes() { return 32; }
      @Override public HistoryBounds bounds() { return new HistoryBounds(0, 0, 0, size() - 1, 0, 0); }
      @Override public BlockPos position(int index) { return new BlockPos(index, 0, 0); }
      @Override public int match(ServerLevel level, int index, boolean undo) { throw new AssertionError(); }
      @Override public boolean apply(ServerLevel level, int index, boolean undo, int flags) { throw new AssertionError(); }
   }
}
