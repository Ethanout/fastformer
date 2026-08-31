package io.github.fastformer.network.payload.geometry;

import io.github.fastformer.fastplace.GeometryMode;
import io.github.fastformer.fastplace.ConePlaneMode;
import io.github.fastformer.fastplace.FillMode;
import io.github.fastformer.fastplace.PolyhedronSizeMode;
import io.github.fastformer.fastplace.geometry.GeometryNumbers;
import io.github.fastformer.fastplace.geometry.ControlPointRole;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public record GeometryPreviewPayload(
   boolean active,
   GeometryMode mode,
   List<BlockPos> points,
   List<Vec3> pointLocations,
   List<ControlPointRole> pointRoles,
   boolean closed,
   boolean ctrlHeld,
   BlockPos extrusion,
   int polyhedronShapeVariant,
   int coneShapeVariant,
   int compoundShapeVariant,
   PolyhedronSizeMode polyhedronSizeMode,
   FillMode fillMode,
   ConePlaneMode conePlaneMode,
   double coneRadius,
   double coneScaleX,
   double coneScaleZ,
   double coneTopScaleOffset,
   Vec3 coneTopOffset,
   double coneRotationRadians,
   boolean coneGizmoLocal,
   double[] rotation,
   Vec3 polyhedronLocalScale,
   Vec3 polyhedronWorldScale,
   boolean polyhedronGizmoLocal,
   int selectedPointIndex
)
   implements CustomPacketPayload {
   private static final int MAX_PREVIEW_POINTS = 1024;
   public static final Type<GeometryPreviewPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "geometry_preview"));
   public static final StreamCodec<FriendlyByteBuf, GeometryPreviewPayload> STREAM_CODEC = CustomPacketPayload.codec(
      GeometryPreviewPayload::write, GeometryPreviewPayload::new
   );

   public GeometryPreviewPayload {
      points = List.copyOf(points);
      pointLocations = pointLocations == null
         ? points.stream().map(Vec3::atCenterOf).toList()
         : pointLocations.stream().map(GeometryNumbers::finiteOrZero).toList();
      if (pointLocations.size() != points.size()) {
         pointLocations = points.stream().map(Vec3::atCenterOf).toList();
      }
      pointRoles = pointRoles == null ? List.of() : List.copyOf(pointRoles);
      if (pointRoles.size() != points.size()) {
         ArrayList<ControlPointRole> fallbackRoles = new ArrayList<>(points.size());
         for (int index = 0; index < points.size(); index++) {
            fallbackRoles.add(index == 0 ? ControlPointRole.PRIMARY : ControlPointRole.SECONDARY);
         }
         pointRoles = List.copyOf(fallbackRoles);
      }
      fillMode = fillMode == null ? FillMode.OUTLINE : fillMode;
      polyhedronSizeMode = polyhedronSizeMode == null ? PolyhedronSizeMode.RADIUS : polyhedronSizeMode;
      coneTopOffset = GeometryNumbers.finiteOrZero(coneTopOffset);
      coneRotationRadians = GeometryNumbers.finiteOr(coneRotationRadians, 0.0);
      coneRadius = Math.clamp(Math.round(GeometryNumbers.finiteOr(coneRadius, 1.0) * 2.0) * 0.5, 0.5, 256.0);
      coneScaleX = GeometryNumbers.finiteOr(coneScaleX, 1.0);
      coneScaleZ = GeometryNumbers.finiteOr(coneScaleZ, 1.0);
      coneTopScaleOffset = GeometryNumbers.finiteOr(coneTopScaleOffset, 0.0);
      rotation = normalizedRotation(rotation);
      polyhedronLocalScale = normalizedScale(polyhedronLocalScale);
      polyhedronWorldScale = normalizedScale(polyhedronWorldScale);
      selectedPointIndex = selectedPointIndex >= 0 && selectedPointIndex < points.size() ? selectedPointIndex : -1;
   }

   private GeometryPreviewPayload(FriendlyByteBuf buffer) {
      this(
         buffer.readBoolean(),
         buffer.readEnum(GeometryMode.class),
         readPoints(buffer),
         readLocations(buffer),
         readRoles(buffer),
         buffer.readBoolean(),
         buffer.readBoolean(),
         buffer.readBlockPos(),
         buffer.readVarInt(),
         buffer.readVarInt(),
         buffer.readVarInt(),
         buffer.readEnum(PolyhedronSizeMode.class),
         buffer.readEnum(FillMode.class),
         buffer.readEnum(ConePlaneMode.class),
         buffer.readDouble(),
         buffer.readDouble(),
         buffer.readDouble(),
         buffer.readDouble(),
         readVec3(buffer),
         buffer.readDouble(),
         buffer.readBoolean(),
         readRotation(buffer),
         readVec3(buffer),
         readVec3(buffer),
         buffer.readBoolean(),
         buffer.readVarInt()
      );
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeBoolean(this.active);
      buffer.writeEnum(this.mode);
      buffer.writeCollection(this.points, (writeBuffer, point) -> writeBuffer.writeBlockPos(point));
      buffer.writeCollection(this.pointLocations, GeometryPreviewPayload::writeVec3);
      buffer.writeCollection(this.pointRoles, (writeBuffer, role) -> writeBuffer.writeEnum(role));
      buffer.writeBoolean(this.closed);
      buffer.writeBoolean(this.ctrlHeld);
      buffer.writeBlockPos(this.extrusion);
      buffer.writeVarInt(this.polyhedronShapeVariant);
      buffer.writeVarInt(this.coneShapeVariant);
      buffer.writeVarInt(this.compoundShapeVariant);
      buffer.writeEnum(this.polyhedronSizeMode);
      buffer.writeEnum(this.fillMode);
      buffer.writeEnum(this.conePlaneMode);
      buffer.writeDouble(this.coneRadius);
      buffer.writeDouble(this.coneScaleX);
      buffer.writeDouble(this.coneScaleZ);
      buffer.writeDouble(this.coneTopScaleOffset);
      writeVec3(buffer, this.coneTopOffset);
      buffer.writeDouble(this.coneRotationRadians);
      buffer.writeBoolean(this.coneGizmoLocal);
      for (double value : this.rotation) {
         buffer.writeDouble(value);
      }
      writeVec3(buffer, this.polyhedronLocalScale);
      writeVec3(buffer, this.polyhedronWorldScale);
      buffer.writeBoolean(this.polyhedronGizmoLocal);
      buffer.writeVarInt(this.selectedPointIndex);
   }

   public static GeometryPreviewPayload inactive() {
      return new GeometryPreviewPayload(
         false,
         GeometryMode.WALL,
         List.of(),
         List.of(),
         List.of(),
         false,
         false,
         BlockPos.ZERO,
         0,
         0,
         0,
         PolyhedronSizeMode.RADIUS,
         FillMode.OUTLINE,
         ConePlaneMode.RADIUS,
         1.0,
         1.0,
         1.0,
         0.0,
         Vec3.ZERO,
         0.0,
         true,
         identityRotation(),
         new Vec3(1.0, 1.0, 1.0),
         new Vec3(1.0, 1.0, 1.0),
         false,
         -1
      );
   }

   @Override
   public Type<GeometryPreviewPayload> type() {
      return TYPE;
   }

   private static double[] readRotation(FriendlyByteBuf buffer) {
      double[] values = new double[9];
      for (int i = 0; i < values.length; i++) {
         values[i] = buffer.readDouble();
      }
      return values;
   }

   private static List<BlockPos> readPoints(FriendlyByteBuf buffer) {
      return buffer.readCollection(FriendlyByteBuf.limitValue(size -> new ArrayList<>(size), MAX_PREVIEW_POINTS), readBuffer -> readBuffer.readBlockPos());
   }

   private static List<Vec3> readLocations(FriendlyByteBuf buffer) {
      return buffer.readCollection(
         FriendlyByteBuf.limitValue(size -> new ArrayList<>(size), MAX_PREVIEW_POINTS),
         GeometryPreviewPayload::readVec3
      );
   }

   private static List<ControlPointRole> readRoles(FriendlyByteBuf buffer) {
      return buffer.readCollection(
         FriendlyByteBuf.limitValue(size -> new ArrayList<>(size), MAX_PREVIEW_POINTS),
         readBuffer -> readBuffer.readEnum(ControlPointRole.class)
      );
   }

   private static Vec3 readVec3(FriendlyByteBuf buffer) {
      return GeometryNumbers.finiteOrZero(new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()));
   }

   private static void writeVec3(FriendlyByteBuf buffer, Vec3 value) {
      Vec3 clean = GeometryNumbers.finiteOrZero(value);
      buffer.writeDouble(clean.x);
      buffer.writeDouble(clean.y);
      buffer.writeDouble(clean.z);
   }

   private static double[] normalizedRotation(double[] values) {
      if (values == null || values.length != 9 || !GeometryNumbers.finite(values)) {
         return identityRotation();
      }
      return GeometryNumbers.finiteOr(values, identityRotation());
   }

   private static double[] identityRotation() {
      return new double[]{1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0};
   }

   private static Vec3 normalizedScale(Vec3 value) {
      if (value == null) {
         return new Vec3(1.0, 1.0, 1.0);
      }
      return new Vec3(
         Math.clamp(GeometryNumbers.finiteOr(value.x, 1.0), 0.125, 8.0),
         Math.clamp(GeometryNumbers.finiteOr(value.y, 1.0), 0.125, 8.0),
         Math.clamp(GeometryNumbers.finiteOr(value.z, 1.0), 0.125, 8.0)
      );
   }
}
