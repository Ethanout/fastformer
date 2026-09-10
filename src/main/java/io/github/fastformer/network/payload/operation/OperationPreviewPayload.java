package io.github.fastformer.network.payload.operation;

import io.github.fastformer.fastplace.OperationMode;
import io.github.fastformer.fastplace.OperationSelectionMode;
import io.github.fastformer.fastplace.OperationStageMode;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public record OperationPreviewPayload(
   boolean enabled,
   boolean active,
   boolean hasFirst,
   boolean hasSecond,
   List<BlockPos> points,
   BlockPos selectionMin,
   BlockPos selectionMax,
   BlockPos minOffset,
   BlockPos maxOffset,
   OperationSelectionMode selectionMode,
   int prismBasePointCount,
   int selectedPointIndex,
   int hullInflation,
   OperationMode mode,
   OperationStageMode stageMode,
   BlockPos translation,
   BlockPos stackMin,
   BlockPos stackMax,
   Vec3 rotation,
   boolean adjustmentStarted,
   boolean copy,
   boolean ctrlHeld,
   long revision
) implements CustomPacketPayload {
   private static final int MAX_PREVIEW_POINTS = 1024;
   public static final Type<OperationPreviewPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "operation_preview")
   );
   public static final StreamCodec<FriendlyByteBuf, OperationPreviewPayload> STREAM_CODEC =
      CustomPacketPayload.codec(OperationPreviewPayload::write, OperationPreviewPayload::new);

   public OperationPreviewPayload {
      points = List.copyOf(points);
   }

   private OperationPreviewPayload(FriendlyByteBuf buffer) {
      this(
         buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(),
         readPoints(buffer), buffer.readNullable(b -> b.readBlockPos()), buffer.readNullable(b -> b.readBlockPos()),
         buffer.readBlockPos(), buffer.readBlockPos(),
         buffer.readEnum(OperationSelectionMode.class), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
         buffer.readEnum(OperationMode.class), buffer.readEnum(OperationStageMode.class),
         buffer.readBlockPos(), buffer.readBlockPos(), buffer.readBlockPos(), readVec3(buffer),
         buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(), buffer.readVarLong()
      );
   }

   public static OperationPreviewPayload active(
      boolean hasFirst, boolean hasSecond, List<BlockPos> points,
      BlockPos minOffset, BlockPos maxOffset, OperationSelectionMode selectionMode,
      int prismBasePointCount, int selectedPointIndex, int hullInflation,
      OperationMode mode, OperationStageMode stageMode, BlockPos translation,
      BlockPos stackMin, BlockPos stackMax, Vec3 rotation,
      boolean adjustmentStarted, boolean copy, boolean ctrlHeld
   ) {
      return active(
         0L, hasFirst, hasSecond, points, null, null, minOffset, maxOffset, selectionMode,
         prismBasePointCount, selectedPointIndex, hullInflation, mode, stageMode,
         translation, stackMin, stackMax, rotation, adjustmentStarted, copy, ctrlHeld
      );
   }

   public static OperationPreviewPayload active(
      long revision,
      boolean hasFirst, boolean hasSecond, List<BlockPos> points,
      BlockPos selectionMin, BlockPos selectionMax,
      BlockPos minOffset, BlockPos maxOffset, OperationSelectionMode selectionMode,
      int prismBasePointCount, int selectedPointIndex, int hullInflation,
      OperationMode mode, OperationStageMode stageMode, BlockPos translation,
      BlockPos stackMin, BlockPos stackMax, Vec3 rotation,
      boolean adjustmentStarted, boolean copy, boolean ctrlHeld
   ) {
      return new OperationPreviewPayload(
         true, true, hasFirst, hasSecond, points,
         selectionMin, selectionMax, minOffset, maxOffset, selectionMode,
         prismBasePointCount, selectedPointIndex, hullInflation, mode, stageMode, translation,
         stackMin, stackMax, rotation, adjustmentStarted, copy, ctrlHeld, revision
      );
   }

   public static OperationPreviewPayload active(
      long revision,
      boolean hasFirst, boolean hasSecond, List<BlockPos> points,
      BlockPos minOffset, BlockPos maxOffset, OperationSelectionMode selectionMode,
      int prismBasePointCount, int selectedPointIndex, int hullInflation,
      OperationMode mode, OperationStageMode stageMode, BlockPos translation,
      BlockPos stackMin, BlockPos stackMax, Vec3 rotation,
      boolean adjustmentStarted, boolean copy, boolean ctrlHeld
   ) {
      return active(revision, hasFirst, hasSecond, points, null, null, minOffset, maxOffset,
         selectionMode, prismBasePointCount, selectedPointIndex, hullInflation, mode, stageMode,
         translation, stackMin, stackMax, rotation, adjustmentStarted, copy, ctrlHeld);
   }

   public static OperationPreviewPayload inactive() {
      return inactive(0L);
   }

   public static OperationPreviewPayload inactive(long revision) {
      return new OperationPreviewPayload(
         false, false, false, false, List.of(), null, null, BlockPos.ZERO, BlockPos.ZERO,
         OperationSelectionMode.CUBOID, 0, -1, 0, OperationMode.MOVE,
         OperationStageMode.TRANSFORM, BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO,
         Vec3.ZERO, false, false, false, revision
      );
   }

   public BlockPos operationMinOffset() { return this.minOffset; }
   public BlockPos operationMaxOffset() { return this.maxOffset; }
   public OperationSelectionMode operationSelectionMode() { return this.selectionMode; }
   public int operationPrismBasePointCount() { return this.prismBasePointCount; }
   public int operationSelectedPointIndex() { return this.selectedPointIndex; }
   public int operationHullInflation() { return this.hullInflation; }
   public OperationMode operationMode() { return this.mode; }
   public OperationStageMode operationStageMode() { return this.stageMode; }
   public BlockPos operationTranslation() { return this.translation; }
   public BlockPos operationStackMin() { return this.stackMin; }
   public BlockPos operationStackMax() { return this.stackMax; }
   public boolean operationAdjustmentStarted() { return this.adjustmentStarted; }
   public Vec3 operationRotation() { return this.rotation; }
   public boolean operationCopy() { return this.copy; }
   public long operationRevision() { return this.revision; }

   /** Compatibility accessor for render code while it migrates to the interval model. */
   public BlockPos operationStackVector() {
      return new BlockPos(
         this.stackMax.getX() != 0 ? this.stackMax.getX() : this.stackMin.getX(),
         this.stackMax.getY() != 0 ? this.stackMax.getY() : this.stackMin.getY(),
         this.stackMax.getZ() != 0 ? this.stackMax.getZ() : this.stackMin.getZ()
      );
   }

   public BlockPos stackVector() { return this.operationStackVector(); }

   /** Compatibility accessor; adjustment, not Enter confirmation, is now the phase signal. */
   public boolean operationSelectionConfirmed() { return this.adjustmentStarted; }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeBoolean(this.enabled);
      buffer.writeBoolean(this.active);
      buffer.writeBoolean(this.hasFirst);
      buffer.writeBoolean(this.hasSecond);
      buffer.writeCollection(this.points, (writer, point) -> writer.writeBlockPos(point));
      buffer.writeNullable(this.selectionMin, (b, value) -> b.writeBlockPos(value));
      buffer.writeNullable(this.selectionMax, (b, value) -> b.writeBlockPos(value));
      buffer.writeBlockPos(this.minOffset);
      buffer.writeBlockPos(this.maxOffset);
      buffer.writeEnum(this.selectionMode);
      buffer.writeVarInt(this.prismBasePointCount);
      buffer.writeVarInt(this.selectedPointIndex);
      buffer.writeVarInt(this.hullInflation);
      buffer.writeEnum(this.mode);
      buffer.writeEnum(this.stageMode);
      buffer.writeBlockPos(this.translation);
      buffer.writeBlockPos(this.stackMin);
      buffer.writeBlockPos(this.stackMax);
      writeVec3(buffer, this.rotation);
      buffer.writeBoolean(this.adjustmentStarted);
      buffer.writeBoolean(this.copy);
      buffer.writeBoolean(this.ctrlHeld);
      buffer.writeVarLong(this.revision);
   }

   @Override
   public Type<OperationPreviewPayload> type() { return TYPE; }

   private static List<BlockPos> readPoints(FriendlyByteBuf buffer) {
      return buffer.readCollection(
         FriendlyByteBuf.limitValue(size -> new ArrayList<>(size), MAX_PREVIEW_POINTS),
         reader -> reader.readBlockPos()
      );
   }

   private static Vec3 readVec3(FriendlyByteBuf buffer) {
      return new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
   }

   private static void writeVec3(FriendlyByteBuf buffer, Vec3 value) {
      buffer.writeDouble(value.x);
      buffer.writeDouble(value.y);
      buffer.writeDouble(value.z);
   }
}
