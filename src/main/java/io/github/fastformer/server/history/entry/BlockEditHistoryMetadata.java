package io.github.fastformer.server.history.entry;

import java.io.IOException;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Shared grouping metadata for wrench edits and ordinary block placement/breaking. */
public record BlockEditHistoryMetadata(String cluster, int actions, int compressionStage) {
   public static final int MAX_ACTIONS = Integer.MAX_VALUE;
   private static final String VANILLA_CLUSTER = "vanilla";

   public BlockEditHistoryMetadata {
      if (cluster == null || cluster.isBlank() || cluster.length() > 1024 || actions < 1 || compressionStage < 0 || compressionStage > 3) {
         throw new IllegalArgumentException("Invalid block edit history metadata");
      }
   }

   public BlockEditHistoryMetadata(String cluster, int actions) { this(cluster, actions, actions == 1 ? 0 : 1); }

   public static BlockEditHistoryMetadata fromVanillaEdit() {
      return new BlockEditHistoryMetadata(VANILLA_CLUSTER, 1);
   }

   public boolean vanillaEdit() {
      return cluster.equals(VANILLA_CLUSTER);
   }

   public boolean sharesGroup(BlockEditHistoryMetadata other) {
      return cluster.equals(other.cluster);
   }

   public BlockEditHistoryMetadata compressed(int actions, int stage) {
      return new BlockEditHistoryMetadata(cluster, actions, stage);
   }

   public static BlockEditHistoryMetadata fromCluster(Optional<String> cluster) {
      return cluster.filter(key -> key.length() <= 1024).map(key -> new BlockEditHistoryMetadata(key, 1)).orElse(null);
   }

   CompoundTag encode() {
      CompoundTag tag = new CompoundTag();
      tag.putString("Cluster", cluster);
      tag.putInt("Actions", actions);
      tag.putInt("CompressionStage", compressionStage);
      return tag;
   }

   static BlockEditHistoryMetadata decode(CompoundTag root) throws IOException {
      if (!root.contains("Tinker", Tag.TAG_COMPOUND)) throw new IOException("Invalid block edit history metadata");
      CompoundTag tag = root.getCompound("Tinker");
      if (!tag.contains("Cluster", Tag.TAG_STRING) || !tag.contains("Actions", Tag.TAG_INT)
         || !tag.contains("CompressionStage", Tag.TAG_INT)) {
         throw new IOException("Incomplete block edit history metadata");
      }
      try {
         return new BlockEditHistoryMetadata(tag.getString("Cluster"), tag.getInt("Actions"),
            tag.getInt("CompressionStage"));
      } catch (IllegalArgumentException exception) {
         throw new IOException("Invalid block edit history metadata", exception);
      }
   }
}
