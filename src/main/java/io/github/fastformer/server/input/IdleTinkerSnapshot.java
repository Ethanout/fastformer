package io.github.fastformer.server.input;

import io.github.fastformer.fastplace.world.BlockEditTransaction;
import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** The original and post-click states of every block changed by one wrench click. */
record IdleTinkerSnapshot(Map<BlockPos, ReversibleBlockSnapshot> original,
   Map<BlockPos, ReversibleBlockSnapshot> changed, UUID operation) {
   IdleTinkerSnapshot {
      original = Map.copyOf(original);
      changed = Map.copyOf(changed);
   }

   static Map<BlockPos, ReversibleBlockSnapshot> capture(ServerLevel level, Set<BlockPos> positions) {
      var snapshots = new LinkedHashMap<BlockPos, ReversibleBlockSnapshot>();
      for (BlockPos position : positions) {
         var snapshot = ReversibleBlockSnapshot.capture(level, position);
         if (snapshot.isEmpty()) return null;
         snapshots.put(position.immutable(), snapshot.orElseThrow());
      }
      return Map.copyOf(snapshots);
   }

   boolean restore(ServerPlayer player) {
      if (original.isEmpty() || original.entrySet().stream().allMatch(entry -> entry.getValue().sameContents(changed.get(entry.getKey())))) return true;
      var outcome = BlockEditTransaction.restore(player, player.serverLevel(), original, changed, operation);
      BlockEditTransaction.report(outcome, player);
      if (outcome == BlockEditTransaction.Outcome.APPLIED) {
         // Chunk updates normally wait until tick end. Selection must read the restored shapes before its entry reply.
         for (BlockPos position : original.keySet()) {
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(player.serverLevel(), position));
            var entity = player.serverLevel().getBlockEntity(position);
            if (entity != null && entity.getUpdatePacket() != null) player.connection.send(entity.getUpdatePacket());
         }
      }
      return outcome == BlockEditTransaction.Outcome.APPLIED;
   }
}
