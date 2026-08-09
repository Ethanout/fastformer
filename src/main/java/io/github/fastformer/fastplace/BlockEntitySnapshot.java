package io.github.fastformer.fastplace;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

public record BlockEntitySnapshot(CompoundTag data) {
   public BlockEntitySnapshot {
      data = data.copy();
   }

   @Override
   public CompoundTag data() {
      return this.data.copy();
   }

   public CompoundTag dataAt(BlockPos pos) {
      CompoundTag copy = this.data();
      copy.putInt("x", pos.getX());
      copy.putInt("y", pos.getY());
      copy.putInt("z", pos.getZ());
      return copy;
   }

   /** Approximate retained NBT size without constructing a textual copy. */
   public int estimatedBytes() {
      return Math.max(16, this.data.sizeInBytes());
   }

   /**
    * Compares a live block entity with this snapshot while ignoring the
    * position fields.  Position fields are rewritten when a snapshot is
    * restored at a different position (for example while stacking).
    */
   public boolean matches(BlockEntity entity, ServerLevel level, BlockPos pos) {
      if (entity == null) {
         return false;
      }
      try {
         CompoundTag actual = entity.saveWithFullMetadata(level.registryAccess());
         CompoundTag expected = this.dataAt(pos);
         normalizePosition(actual);
         normalizePosition(expected);
         return actual.equals(expected);
      } catch (RuntimeException exception) {
         return false;
      }
   }

   private static void normalizePosition(CompoundTag tag) {
      tag.remove("x");
      tag.remove("y");
      tag.remove("z");
   }
}
