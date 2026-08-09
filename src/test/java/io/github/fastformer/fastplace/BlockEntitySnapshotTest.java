package io.github.fastformer.fastplace;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class BlockEntitySnapshotTest {
   @Test
   void nbtIsDefensivelyCopied() {
      CompoundTag data = new CompoundTag();
      data.putInt("value", 7);
      BlockEntitySnapshot snapshot = new BlockEntitySnapshot(data);

      data.putInt("value", 9);
      CompoundTag returned = snapshot.data();
      returned.putInt("value", 11);

      assertEquals(7, snapshot.data().getInt("value"));
   }

   @Test
   void relocatedDataUsesTargetCoordinatesWithoutMutatingSnapshot() {
      CompoundTag data = new CompoundTag();
      data.putInt("x", 1);
      data.putInt("y", 2);
      data.putInt("z", 3);
      BlockEntitySnapshot snapshot = new BlockEntitySnapshot(data);

      CompoundTag relocated = snapshot.dataAt(new BlockPos(-4, 70, 9));

      assertEquals(-4, relocated.getInt("x"));
      assertEquals(70, relocated.getInt("y"));
      assertEquals(9, relocated.getInt("z"));
      assertEquals(1, snapshot.data().getInt("x"));
      assertEquals(2, snapshot.data().getInt("y"));
      assertEquals(3, snapshot.data().getInt("z"));
   }
}
