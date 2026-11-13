package io.github.fastformer.server.history.replay;

import io.github.fastformer.fastplace.text.FastPlaceMessages;
import io.github.fastformer.server.task.WorldTaskContext;
import io.github.fastformer.protocol.s2c.world.HistoryConflictPayload;
import io.github.fastformer.server.history.entry.HistoryEntry;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

/** Owns one conflict prompt, its highlight stream, and its cancellation token. */
final class HistoryConflictPrompt {
   private static final int REMINDER_TICKS = 40;
   private final Audience audience;
   private final HistoryEntry entry;
   private final BitSet conflicts;
   private final ResourceKey<Level> playerDimension;
   private final UUID token = UUID.randomUUID();
   private int displayIndex;
   private int reminderTicks = REMINDER_TICKS;
   private boolean active = true;

   interface Audience {
      ResourceKey<Level> dimension();
      boolean supportsPrompt();
      void send(HistoryConflictPayload payload);
      void remind(int count);
   }

   private HistoryConflictPrompt(Audience audience, HistoryEntry entry, BitSet conflicts, ResourceKey<Level> dimension) {
      this.audience = audience;
      this.entry = entry;
      this.conflicts = (BitSet) conflicts.clone();
      this.playerDimension = dimension;
   }

   static Optional<HistoryConflictPrompt> begin(WorldTaskContext context, HistoryEntry entry, BitSet conflicts) {
      return begin(new Audience() {
         @Override
         public ResourceKey<Level> dimension() {
            var player = context.onlinePlayer();
            return player == null ? null : player.level().dimension();
         }

         @Override
         public boolean supportsPrompt() {
            var player = context.onlinePlayer();
            return player != null && player.connection.hasChannel(HistoryConflictPayload.TYPE);
         }

         @Override
         public void send(HistoryConflictPayload payload) {
            var player = context.onlinePlayer();
            if (player != null && player.connection.hasChannel(HistoryConflictPayload.TYPE)) {
               PacketDistributor.sendToPlayer(player, payload);
            }
         }

         @Override
         public void remind(int count) {
            context.actionBar(FastPlaceMessages.text("fastformer.message.history_conflict_confirm", count));
         }
      }, entry, conflicts);
   }

   static Optional<HistoryConflictPrompt> begin(Audience audience, HistoryEntry entry, BitSet conflicts) {
      ResourceKey<Level> dimension = audience.dimension();
      if (dimension == null || !audience.supportsPrompt() || conflicts.isEmpty()) {
         return Optional.empty();
      }
      HistoryConflictPrompt prompt = new HistoryConflictPrompt(audience, entry, conflicts, dimension);
      prompt.sendHighlights();
      audience.remind(prompt.conflicts.cardinality());
      return Optional.of(prompt);
   }

   boolean tick() {
      if (!active) {
         return false;
      }
      if (!playerDimension.equals(audience.dimension()) || !audience.supportsPrompt()) {
         clear();
         return false;
      }
      sendHighlights();
      if (reminderTicks-- <= 0) {
         audience.remind(conflicts.cardinality());
         reminderTicks = REMINDER_TICKS;
      }
      return true;
   }

   boolean accepts(UUID responseToken) {
      return active && token.equals(responseToken);
   }

   void clear() {
      if (!active) {
         return;
      }
      active = false;
      audience.send(new HistoryConflictPayload(token, entry.dimension().location(), 0, List.of()));
   }

   private void sendHighlights() {
      if (displayIndex < 0) {
         return;
      }
      List<BlockPos> positions = new ArrayList<>();
      int next = conflicts.nextSetBit(displayIndex);
      while (next >= 0 && positions.size() < HistoryConflictPayload.MAX_POSITIONS) {
         positions.add(entry.position(next));
         next = conflicts.nextSetBit(next + 1);
      }
      displayIndex = next;
      if (!positions.isEmpty()) {
         audience.send(new HistoryConflictPayload(token, entry.dimension().location(), conflicts.cardinality(), positions));
      }
   }
}
