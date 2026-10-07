package io.github.fastformer.fastplace.history;

import java.io.IOException;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;
import io.github.fastformer.fastplace.history.TinkerHistoryMergeLimits.DistanceBand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Semantic cluster key and original action count, retained through persistence and compaction. */
public record TinkerHistoryMetadata(String cluster, int actions, DistanceBand distanceBand) {
   public static final int MAX_LIMIT = 256;

   public TinkerHistoryMetadata {
      if (cluster == null || cluster.isBlank() || cluster.length() > 1024 || actions < 1 || actions > MAX_LIMIT || distanceBand == null) {
         throw new IllegalArgumentException("Invalid wrench history metadata");
      }
   }

   public TinkerHistoryMetadata(String cluster, int actions) { this(cluster, actions, DistanceBand.FAR); }

   public static TinkerHistoryMetadata capture(Player player, BlockHitResult hit, Map<BlockPos, BlockState> changes,
      TinkerHistoryMergeLimits limits) {
      var cluster = io.github.fastformer.fastplace.interaction.TinkerHistoryClassifier.cluster(player.level(), hit, changes,
         HistoryStorageConfig.compactionPolicy().grouping());
      return cluster.filter(key -> key.length() <= 1024).map(key -> new TinkerHistoryMetadata(key, 1,
         DistanceBand.at(player.getEyePosition().distanceTo(hit.getLocation()), limits))).orElse(null);
   }

   CompoundTag encode() {
      CompoundTag tag = new CompoundTag();
      tag.putString("Cluster", cluster);
      tag.putInt("Actions", actions);
      tag.putString("DistanceBand", distanceBand.name());
      return tag;
   }

   static TinkerHistoryMetadata decode(CompoundTag root) throws IOException {
      if (!root.contains("Tinker", Tag.TAG_COMPOUND)) throw new IOException("Invalid wrench history metadata");
      CompoundTag tag = root.getCompound("Tinker");
      if (!tag.contains("Cluster", Tag.TAG_STRING) || !tag.contains("Actions", Tag.TAG_INT)) {
         throw new IOException("Incomplete wrench history metadata");
      }
      try {
         if (tag.contains("DistanceBand") && !tag.contains("DistanceBand", Tag.TAG_STRING)) {
            throw new IllegalArgumentException("Invalid wrench distance band");
         }
         return new TinkerHistoryMetadata(tag.getString("Cluster"), tag.getInt("Actions"),
            tag.contains("DistanceBand") ? DistanceBand.valueOf(tag.getString("DistanceBand")) : DistanceBand.FAR);
      } catch (IllegalArgumentException exception) {
         throw new IOException("Invalid wrench history metadata", exception);
      }
   }
}
