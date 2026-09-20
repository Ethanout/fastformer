package io.github.fastformer.client.operation.selection;

import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.fastplace.geometry.BlockPositionMaps;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

public final class SelectionBlockCapture {
   private SelectionBlockCapture() {}

   public static Map<BlockPos, ClientBlockSnapshot> capture(OperationSelectionVolume selection) {
      return captureMissing(selection, Set.of());
   }

   public static Map<BlockPos, ClientBlockSnapshot> resize(
      ClientSelectionPart baseline, OperationSelectionVolume selection
   ) {
      Map<BlockPos, ClientBlockSnapshot> result = new LinkedHashMap<>();
      baseline.blocks().forEach((pos, snapshot) -> {
         if (selection.intersects(new AABB(pos))) result.put(pos.immutable(), snapshot);
      });
      if (baseline.source() == ClientSelectionPart.Source.WORLD) {
         result.putAll(captureMissing(selection, baseline.blocks().keySet()));
      }
      return BlockPositionMaps.copyOf(result);
   }

   private static Map<BlockPos, ClientBlockSnapshot> captureMissing(
      OperationSelectionVolume selection, Set<BlockPos> alreadyCaptured
   ) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft == null || minecraft.level == null) return Map.of();
      AABB bounds = selection.bounds();
      Map<BlockPos, ClientBlockSnapshot> result = new LinkedHashMap<>();
      for (int x = Mth.floor(bounds.minX); x < Mth.ceil(bounds.maxX); x++) {
         for (int y = Mth.floor(bounds.minY); y < Mth.ceil(bounds.maxY); y++) {
            for (int z = Mth.floor(bounds.minZ); z < Mth.ceil(bounds.maxZ); z++) {
               BlockPos pos = new BlockPos(x, y, z);
               if (alreadyCaptured.contains(pos) || !selection.intersects(new AABB(pos))) continue;
               var state = minecraft.level.getBlockState(pos);
               if (state.isAir()) continue;
               CompoundTag blockEntityTag = null;
               try {
                  var entity = minecraft.level.getBlockEntity(pos);
                  if (entity != null) blockEntityTag = entity.saveWithFullMetadata(minecraft.level.registryAccess());
               } catch (RuntimeException ignored) {
                  // The server captures authoritative data before it changes the world.
               }
               result.put(pos, new ClientBlockSnapshot(state, blockEntityTag));
            }
         }
      }
      return BlockPositionMaps.copyOf(result);
   }
}
