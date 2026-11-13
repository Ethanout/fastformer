package io.github.fastformer.server.history;

import io.github.fastformer.fastplace.world.WorldTaskContext;
import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import io.github.fastformer.protocol.s2c.world.WorldHistoryEventPayload;
import io.github.fastformer.server.history.entry.BlockEditHistoryMetadata;
import io.github.fastformer.server.history.entry.HistoryBatchComposition;
import io.github.fastformer.server.history.entry.HistoryEntry;
import io.github.fastformer.server.history.entry.HistoryReplacement;
import io.github.fastformer.server.history.entry.WorldChangeBatch;
import io.github.fastformer.server.history.memory.BlockEditHistoryGesture;
import io.github.fastformer.server.history.vanilla.WorldHistoryEvents;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Block-only gesture composition and inverse detection at the recording boundary. */
final class BlockHistoryRecorder {
   private final HistoryService service;

   BlockHistoryRecorder(HistoryService service) { this.service = service; }

   boolean tinker(ServerPlayer player, ServerLevel level, ArrayDeque<ReversibleBlockSnapshot> before,
      Map<BlockPos, ReversibleBlockSnapshot> after, BlockEditHistoryMetadata metadata) {
      var batch = WorldChangeBatch.capturePairsByPos(level, before, after);
      if (batch.isEmpty()) return false;
      service.recordEntry(player, batch.orElseThrow().withTinker(metadata));
      return true;
   }

   boolean gesture(ServerPlayer player, ServerLevel level, ArrayDeque<ReversibleBlockSnapshot> before,
      Map<BlockPos, ReversibleBlockSnapshot> after, BlockEditHistoryGesture gesture) {
      var captured = WorldChangeBatch.capturePairsByPos(level, before, after);
      if (captured.isEmpty()) return false;
      var owner = service.ownerState(player.getUUID());
      var history = owner.history();
      WorldChangeBatch step = captured.orElseThrow();
      HistoryEntry latest = history.peek(true);
      if (!gesture.canExtend(history) || !(latest instanceof WorldChangeBatch previous)
         || !previous.dimension().equals(step.dimension())) {
         service.recordEntry(player, step);
         gesture.recorded(history, true);
         return true;
      }
      var composition = HistoryBatchComposition.compose(List.of(step, previous));
      if (composition.isEmpty()) {
         service.recordEntry(player, step);
         gesture.recorded(history, true);
         return true;
      }
      var combined = composition.orElseThrow().batch().map(batch -> batch.withOperationId(UUID.randomUUID()));
      var context = new WorldTaskContext(player.getServer(), player.getUUID());
      service.applyCompaction(context, owner,
         new HistoryReplacement<HistoryEntry>(0, List.of(previous.operationId()), combined.map(entry -> entry)));
      WorldHistoryEvents.send(context, combined.orElse(previous), combined.isPresent()
         ? WorldHistoryEventPayload.Kind.RECORD : WorldHistoryEventPayload.Kind.UNDO, true);
      gesture.recorded(history, combined.isPresent());
      return true;
   }

   boolean restoration(ServerPlayer player, ServerLevel level, ArrayDeque<ReversibleBlockSnapshot> before,
      Map<BlockPos, ReversibleBlockSnapshot> after, UUID operationId) {
      OwnerHistory owner = service.ownerState(player.getUUID());
      HistoryEntry latest = owner.history().peek(true);
      if (!(latest instanceof WorldChangeBatch original) || !original.operationId().equals(operationId)
         || !original.dimension().equals(level.dimension()) || original.size() != before.size() || original.size() != after.size()) {
         return service.record(player, level, before, after);
      }
      Map<BlockPos, ReversibleBlockSnapshot> source = new LinkedHashMap<>();
      for (var snapshot : before) source.put(snapshot.pos(), snapshot);
      var originals = original.targetSnapshots(true);
      var changed = original.sourceSnapshots(true);
      for (int i = 0; i < original.size(); i++) {
         if (!changed.get(i).sameContents(source.get(original.position(i)))
            || !originals.get(i).sameContents(after.get(original.position(i)))) return service.record(player, level, before, after);
      }
      owner.history().removeNewestUndo();
      owner.order().remove(operationId);
      var context = new WorldTaskContext(player.getServer(), player.getUUID());
      owner.publishIndex(context, service.repository());
      WorldHistoryEvents.send(context, original, WorldHistoryEventPayload.Kind.UNDO, true);
      return true;
   }
}
