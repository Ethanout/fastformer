package io.github.fastformer.client.render.mask;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.function.ToIntFunction;
import net.minecraft.core.BlockPos;

/** Samples skylight above hidden source columns without changing world light data. */
public final class SourceMaskLighting {
   private final SourceMaskRenderFilter.Snapshot mask;
   private final Long2LongOpenHashMap columnExits = new Long2LongOpenHashMap();

   public SourceMaskLighting(SourceMaskRenderFilter.Snapshot mask) {
      this.mask = mask;
   }

   public int skyLight(BlockPos position, ToIntFunction<BlockPos> worldLight) {
      int original = worldLight.applyAsInt(position);
      if (!mask.hides(position.asLong())) return original;
      BlockPos above = columnExit(position);
      int sky = worldLight.applyAsInt(above);
      // Direct skylight travels down an open column without attenuation.
      int transmitted = sky == 15 ? 15 : Math.max(0, sky - (above.getY() - position.getY()));
      return Math.max(original, transmitted);
   }

   private BlockPos columnExit(BlockPos position) {
      long packed = position.asLong();
      if (columnExits.containsKey(packed)) return BlockPos.of(columnExits.get(packed));
      LongArrayList visited = new LongArrayList();
      BlockPos.MutableBlockPos cursor = position.mutable();
      while (mask.hides(cursor.asLong())) {
         long current = cursor.asLong();
         if (columnExits.containsKey(current)) {
            cursor.set(BlockPos.of(columnExits.get(current)));
            break;
         }
         visited.add(current);
         cursor.move(0, 1, 0);
      }
      long exit = cursor.asLong();
      for (int i = 0; i < visited.size(); i++) columnExits.put(visited.getLong(i), exit);
      return cursor.immutable();
   }
}
