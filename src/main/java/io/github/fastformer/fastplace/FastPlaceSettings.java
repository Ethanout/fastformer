package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

import io.github.fastformer.fastplace.geometry.GeometryNumbers;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

public final class FastPlaceSettings {
   private static final String KEY = "fastformer";
   public static final int DEFAULT_WORLD_UNDO_HISTORY_LIMIT = 200;
   public static final int DEFAULT_SESSION_UNDO_HISTORY_LIMIT = 100;
   public static final int MAX_UNDO_HISTORY_LIMIT = 800;
   private boolean enabled = true;
   private boolean middleConfirmEnabled = true;
   private FaceRasterizationMode faceRasterizationMode = FaceRasterizationMode.POINT_SWEEP;
   private PointMode pointMode = PointMode.RAYCAST;
   private RaycastPlacement raycastPlacement = RaycastPlacement.EMBEDDED;
   private LineMode lineMode = LineMode.AXIS;
   private FaceMode faceMode = FaceMode.POLYGON;
   private VolumeMode volumeMode = VolumeMode.PERPENDICULAR_TO_FACE;
   private ConePlaneMode conePlaneMode = ConePlaneMode.RADIUS;
   private FillMode fillMode = FillMode.OUTLINE;
   private OperationConflictMode placementConflictMode = OperationConflictMode.REPLACE;
   private OperationSelectionMode operationSelectionMode = OperationSelectionMode.CUBOID;
   private PlacementUpdateMode placementUpdateMode = PlacementUpdateMode.NORMAL;
   private boolean smartWoodFrame = true;
   private boolean emptyHandWrench = true;
   private int maxPlacement = 20972152;
   private int worldUndoHistoryLimit = DEFAULT_WORLD_UNDO_HISTORY_LIMIT;
   private int sessionUndoHistoryLimit = DEFAULT_SESSION_UNDO_HISTORY_LIMIT;
   private double angleDegrees = 0.0;

   private FastPlaceSettings() {
   }

   public static FastPlaceSettings load(ServerPlayer player) {
      CompoundTag root = player.getPersistentData();
      if (!root.contains(KEY, 10)) {
         FastPlaceSettings settings = new FastPlaceSettings();
         settings.save(player);
         return settings;
      }
      return fromTag(root.getCompound(KEY));
   }

   static FastPlaceSettings fromTag(CompoundTag tag) {
      FastPlaceSettings settings = new FastPlaceSettings();
      settings.enabled = !tag.contains("enabled") || tag.getBoolean("enabled");
      settings.middleConfirmEnabled = !tag.contains("middleConfirmEnabled") || tag.getBoolean("middleConfirmEnabled");
      settings.faceRasterizationMode = readEnum(
         tag, "faceRasterizationMode", FaceRasterizationMode.POINT_SWEEP
      );
      settings.pointMode = readEnum(tag, "pointMode", PointMode.RAYCAST);
      settings.raycastPlacement = readEnum(tag, "raycastPlacement", RaycastPlacement.EMBEDDED);
      settings.lineMode = readEnum(tag, "lineMode", LineMode.AXIS);
      settings.faceMode = readEnum(tag, "faceMode", FaceMode.POLYGON);
      settings.volumeMode = readEnum(tag, "volumeMode", VolumeMode.PERPENDICULAR_TO_FACE);
      settings.conePlaneMode = readEnum(tag, "conePlaneMode", ConePlaneMode.RADIUS);
      settings.fillMode = readEnum(tag, "fillMode", FillMode.OUTLINE);
      settings.placementConflictMode = readEnum(tag, "placementConflictMode", OperationConflictMode.REPLACE);
      settings.operationSelectionMode = readEnum(tag, "operationSelectionMode", OperationSelectionMode.CUBOID);
      if (settings.operationSelectionMode == OperationSelectionMode.CONVEX_HULL) {
         settings.operationSelectionMode = OperationSelectionMode.CUBOID;
      }
      settings.placementUpdateMode = readEnum(tag, "placementUpdateMode", PlacementUpdateMode.NORMAL);
      settings.smartWoodFrame = !tag.contains("smartWoodFrame") || tag.getBoolean("smartWoodFrame");
      settings.emptyHandWrench = !tag.contains("emptyHandWrench") || tag.getBoolean("emptyHandWrench");
      settings.maxPlacement = tag.contains("maxPlacement")
         ? Math.clamp((long)tag.getInt("maxPlacement"), 1, 20972152)
         : 20972152;
      settings.worldUndoHistoryLimit = tag.contains("undoHistoryLimit")
         ? Math.clamp((long)tag.getInt("undoHistoryLimit"), 1, MAX_UNDO_HISTORY_LIMIT)
         : DEFAULT_WORLD_UNDO_HISTORY_LIMIT;
      settings.sessionUndoHistoryLimit = tag.contains("sessionUndoHistoryLimit")
         ? Math.clamp((long)tag.getInt("sessionUndoHistoryLimit"), 1, MAX_UNDO_HISTORY_LIMIT)
         : DEFAULT_SESSION_UNDO_HISTORY_LIMIT;
      double savedAngle = tag.getDouble("angleDegrees");
      settings.angleDegrees = GeometryNumbers.finite(savedAngle)
         ? GeometryNumbers.cleanZero(Math.IEEEremainder(savedAngle, 360.0))
         : 0.0;
      return settings;
   }

   public boolean enabled() {
      return this.enabled;
   }

   public void toggleEnabled(ServerPlayer player) {
      this.enabled = !this.enabled;
      this.save(player);
   }

   public boolean middleConfirmEnabled() {
      return this.middleConfirmEnabled;
   }

   public void setMiddleConfirmEnabled(ServerPlayer player, boolean value) {
      this.middleConfirmEnabled = value;
      this.save(player);
   }

   public FaceRasterizationMode faceRasterizationMode() {
      return this.faceRasterizationMode;
   }

   public void setFaceRasterizationMode(ServerPlayer player, FaceRasterizationMode value) {
      this.faceRasterizationMode = value == null ? FaceRasterizationMode.POINT_SWEEP : value;
      this.save(player);
   }

   public FastPlaceMode modeFor(FastPlaceStage stage) {
      return FastPlaceStateMachine.validMode(stage, this);
   }

   FastPlaceMode storedModeFor(FastPlaceStage stage) {
      return (FastPlaceMode)(switch (stage) {
         case POINT -> this.pointMode;
         case LINE -> this.lineMode;
         case FACE -> this.faceMode;
         case VOLUME -> this.volumeMode;
      });
   }

   LineMode storedLineMode() {
      return this.lineMode;
   }

   FaceMode storedFaceMode() {
      return this.faceMode;
   }

   public PointMode pointMode() {
      return (PointMode)this.modeFor(FastPlaceStage.POINT);
   }

   public LineMode lineMode() {
      return (LineMode)this.modeFor(FastPlaceStage.LINE);
   }

   public RaycastPlacement raycastPlacement() {
      return this.raycastPlacement;
   }

   public RaycastPlacement cycleRaycastPlacement(ServerPlayer player) {
      this.raycastPlacement = this.raycastPlacement == RaycastPlacement.EMBEDDED ? RaycastPlacement.SURFACE : RaycastPlacement.EMBEDDED;
      this.save(player);
      return this.raycastPlacement;
   }

   public FaceMode faceMode() {
      return (FaceMode)this.modeFor(FastPlaceStage.FACE);
   }

   public VolumeMode volumeMode() {
      return (VolumeMode)this.modeFor(FastPlaceStage.VOLUME);
   }

   public ConePlaneMode conePlaneMode() {
      return this.conePlaneMode;
   }

   public void setConePlaneMode(ServerPlayer player, ConePlaneMode mode) {
      if (mode == null) {
         return;
      }
      this.conePlaneMode = mode;
      this.save(player);
   }

   public FastPlaceMode cycleMode(ServerPlayer player, FastPlaceStage stage) {
      this.setStoredMode(stage, FastPlaceStateMachine.nextMode(stage, this));
      this.save(player);
      return this.modeFor(stage);
   }

   public void setMode(ServerPlayer player, FastPlaceMode mode) {
      this.setStoredMode(mode.stage(), mode);
      this.save(player);
   }

   public FillMode fillMode() {
      return this.fillMode;
   }

   public FastPlaceGeometry.Modes modes() {
      return new FastPlaceGeometry.Modes(
         this.pointMode(), this.raycastPlacement, this.lineMode(), this.faceMode(), this.volumeMode(), this.fillMode,
         this.angleDegrees, false, io.github.fastformer.fastplace.geometry.generation.LineTieBias.DEFAULT,
         this.faceRasterizationMode
      );
   }

   public void setFillMode(ServerPlayer player, FillMode value) {
      this.fillMode = value;
      this.save(player);
   }

   public FillMode cycleFillMode(ServerPlayer player) {
      FillMode[] values = FillMode.values();
      this.fillMode = values[(this.fillMode.ordinal() + 1) % values.length];
      this.save(player);
      return this.fillMode;
   }

   public double angleDegrees() {
      return this.angleDegrees;
   }

   public int maxPlacement() {
      return this.maxPlacement;
   }

   public int worldUndoHistoryLimit() {
      return this.worldUndoHistoryLimit;
   }

   public void setWorldUndoHistoryLimit(ServerPlayer player, int value) {
      this.worldUndoHistoryLimit = Math.clamp((long)value, 1, MAX_UNDO_HISTORY_LIMIT);
      this.save(player);
      WorldHistoryManager.trimToSetting(player, this.worldUndoHistoryLimit);
   }

   public int sessionUndoHistoryLimit() {
      return this.sessionUndoHistoryLimit;
   }

   public void setSessionUndoHistoryLimit(ServerPlayer player, int value) {
      this.sessionUndoHistoryLimit = Math.clamp((long)value, 1, MAX_UNDO_HISTORY_LIMIT);
      this.save(player);
      OperationManager.updateSessionHistoryLimit(player, this.sessionUndoHistoryLimit);
   }

   public OperationConflictMode placementConflictMode() {
      return this.placementConflictMode;
   }

   public OperationSelectionMode operationSelectionMode() {
      return this.operationSelectionMode;
   }

   public void setOperationSelectionMode(ServerPlayer player, OperationSelectionMode mode) {
      this.operationSelectionMode = mode == null || mode == OperationSelectionMode.CONVEX_HULL
         ? OperationSelectionMode.CUBOID
         : mode;
      this.save(player);
   }

   public void setPlacementConflictMode(ServerPlayer player, OperationConflictMode mode) {
      this.placementConflictMode = mode;
      this.save(player);
   }

   public PlacementUpdateMode placementUpdateMode() {
      return this.placementUpdateMode;
   }

   public boolean smartWoodFrame() {
      return this.smartWoodFrame;
   }

   public void toggleSmartWoodFrame(ServerPlayer player) {
      this.smartWoodFrame = !this.smartWoodFrame;
      this.save(player);
   }

   public boolean emptyHandWrench() {
      return this.emptyHandWrench;
   }

   public void toggleEmptyHandWrench(ServerPlayer player) {
      this.emptyHandWrench = !this.emptyHandWrench;
      this.save(player);
   }

   public void setPlacementUpdateMode(ServerPlayer player, PlacementUpdateMode mode) {
      this.placementUpdateMode = mode;
      this.save(player);
   }

   public void setMaxPlacement(ServerPlayer player, int value) {
      this.maxPlacement = Math.clamp((long)value, 1, 20972152);
      this.save(player);
   }

   public void adjustAngle(ServerPlayer player, int steps) {
      this.setAngle(player, this.angleDegrees + (double)Integer.signum(steps) * 5.0);
   }

   public void setAngle(ServerPlayer player, double value) {
      if (!GeometryNumbers.finite(value)) {
         return;
      }
      this.angleDegrees = GeometryNumbers.cleanZero(Math.IEEEremainder(value, 360.0));
      this.save(player);
   }

   public void save(ServerPlayer player) {
      player.getPersistentData().put(KEY, this.toTag());
   }

   CompoundTag toTag() {
      CompoundTag tag = new CompoundTag();
      tag.putBoolean("enabled", this.enabled);
      tag.putBoolean("middleConfirmEnabled", this.middleConfirmEnabled);
      tag.putString("faceRasterizationMode", this.faceRasterizationMode.name());
      tag.putString("pointMode", this.pointMode.name());
      tag.putString("raycastPlacement", this.raycastPlacement.name());
      tag.putString("lineMode", this.lineMode.name());
      tag.putString("faceMode", this.faceMode.name());
      tag.putString("volumeMode", this.volumeMode.name());
      tag.putString("conePlaneMode", this.conePlaneMode.name());
      tag.putString("fillMode", this.fillMode.name());
      tag.putString("placementConflictMode", this.placementConflictMode.name());
      tag.putString("operationSelectionMode", this.operationSelectionMode.name());
      tag.putString("placementUpdateMode", this.placementUpdateMode.name());
      tag.putBoolean("smartWoodFrame", this.smartWoodFrame);
      tag.putBoolean("emptyHandWrench", this.emptyHandWrench);
      tag.putInt("maxPlacement", this.maxPlacement);
      tag.putInt("undoHistoryLimit", this.worldUndoHistoryLimit);
      tag.putInt("sessionUndoHistoryLimit", this.sessionUndoHistoryLimit);
      tag.putDouble("angleDegrees", this.angleDegrees);
      return tag;
   }

   public static void copy(ServerPlayer from, ServerPlayer to) {
      CompoundTag root = from.getPersistentData();
      if (root.contains("fastformer", 10)) {
         to.getPersistentData().put("fastformer", root.getCompound("fastformer").copy());
      }
   }

   private static <E extends Enum<E>> E readEnum(CompoundTag tag, String key, E fallback) {
      if (!tag.contains(key)) {
         return fallback;
      } else {
         try {
            return Enum.valueOf(fallback.getDeclaringClass(), tag.getString(key));
         } catch (IllegalArgumentException var4) {
            return fallback;
         }
      }
   }

   private void setStoredMode(FastPlaceStage stage, FastPlaceMode mode) {
      switch (stage) {
         case POINT:
            this.pointMode = (PointMode)mode;
            break;
         case LINE:
            this.lineMode = (LineMode)mode;
            break;
         case FACE:
            this.faceMode = (FaceMode)mode;
            break;
         case VOLUME:
            this.volumeMode = (VolumeMode)mode;
      }
   }
}
