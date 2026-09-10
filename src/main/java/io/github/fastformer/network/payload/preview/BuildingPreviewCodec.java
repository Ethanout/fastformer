package io.github.fastformer.network.payload.preview;

import io.github.fastformer.fastplace.PlacementContextSnapshot;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;

/** Shared wire helpers for the split building preview payloads. */
final class BuildingPreviewCodec {
   static final int MAX_PREVIEW_POINTS = 1024;

   private BuildingPreviewCodec() {
   }

   static List<BlockPos> readPoints(FriendlyByteBuf buffer) {
      return buffer.readCollection(
         FriendlyByteBuf.limitValue(size -> new ArrayList<>(size), MAX_PREVIEW_POINTS),
         reader -> reader.readBlockPos()
      );
   }

   static void writePoints(FriendlyByteBuf buffer, List<BlockPos> points) {
      buffer.writeCollection(points, (writer, point) -> writer.writeBlockPos(point));
   }

   static Vec3 readVec3(FriendlyByteBuf buffer) {
      return new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
   }

   static void writeVec3(FriendlyByteBuf buffer, Vec3 value) {
      buffer.writeDouble(value.x);
      buffer.writeDouble(value.y);
      buffer.writeDouble(value.z);
   }

   static PlacementContextSnapshot readPlacementContext(FriendlyByteBuf buffer) {
      if (!buffer.readBoolean()) {
         return null;
      }
      BlockPos hitBlock = buffer.readBlockPos();
      Vec3 hitLocation = readVec3(buffer);
      Direction clickedFace = buffer.readEnum(Direction.class);
      boolean inside = buffer.readBoolean();
      boolean replacing = buffer.readBoolean();
      float rotation = buffer.readFloat();
      Direction horizontal = buffer.readEnum(Direction.class);
      Direction vertical = buffer.readEnum(Direction.class);
      List<Direction> nearest = new ArrayList<>(Direction.values().length);
      for (int index = 0; index < Direction.values().length; index++) {
         nearest.add(buffer.readEnum(Direction.class));
      }
      boolean secondary = buffer.readBoolean();
      return new PlacementContextSnapshot(
         hitBlock, hitLocation, clickedFace, inside, replacing, rotation,
         horizontal, vertical, nearest, secondary
      );
   }

   static void writePlacementContext(FriendlyByteBuf buffer, PlacementContextSnapshot snapshot) {
      buffer.writeBoolean(snapshot != null);
      if (snapshot == null) {
         return;
      }
      buffer.writeBlockPos(snapshot.hitBlock());
      writeVec3(buffer, snapshot.hitLocation());
      buffer.writeEnum(snapshot.clickedFace());
      buffer.writeBoolean(snapshot.inside());
      buffer.writeBoolean(snapshot.replacingClickedBlock());
      buffer.writeFloat(snapshot.rotation());
      buffer.writeEnum(snapshot.horizontalDirection());
      buffer.writeEnum(snapshot.verticalDirection());
      snapshot.nearestDirections().forEach(buffer::writeEnum);
      buffer.writeBoolean(snapshot.secondaryUseActive());
   }
}
