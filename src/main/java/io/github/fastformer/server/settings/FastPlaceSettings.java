package io.github.fastformer.server.settings;

import io.github.fastformer.common.history.SessionHistoryLimits;

import io.github.fastformer.server.history.HistorySystem;

import io.github.fastformer.common.geometry.model.FillMode;
import io.github.fastformer.common.geometry.GeometryNumbers;
import io.github.fastformer.common.geometry.ConePlaneMode;
import io.github.fastformer.common.geometry.generation.FastPlaceGeometry;
import io.github.fastformer.server.history.HistoryService;
import io.github.fastformer.common.placement.PlacementUpdateMode;
import io.github.fastformer.common.quickshape.mode.FaceMode;
import io.github.fastformer.common.quickshape.mode.LineMode;
import io.github.fastformer.common.quickshape.mode.PointMode;
import io.github.fastformer.common.quickshape.mode.QuickShapeMode;
import io.github.fastformer.common.quickshape.mode.QuickShapeModeRules;
import io.github.fastformer.common.quickshape.mode.QuickShapeStage;
import io.github.fastformer.common.quickshape.mode.RaycastPlacement;
import io.github.fastformer.common.quickshape.mode.VolumeMode;
import io.github.fastformer.common.selection.OperationSelectionMode;
import io.github.fastformer.server.session.OperationSystem;
import io.github.fastformer.common.workspace.model.OperationConflictMode;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public final class FastPlaceSettings {
   private static final String KEY = "fastformer";
   /** Existing gameplay cap, centralized without changing accepted placement sizes. */
   public static final int MAX_PLACEMENT = 20_972_152;
   public static final int DEFAULT_WORLD_UNDO_HISTORY_LIMIT = 200;
   private boolean enabled = true;
   private boolean middleConfirmEnabled = true;
   private PointMode pointMode = PointMode.RAYCAST;
   private LineMode lineMode = LineMode.AXIS;
   private FaceMode faceMode = FaceMode.POLYGON;
   private VolumeMode volumeMode = VolumeMode.PERPENDICULAR_TO_FACE;
   private ConePlaneMode conePlaneMode = ConePlaneMode.RADIUS;
   private FillMode fillMode = FillMode.OUTLINE;
   private OperationConflictMode placementConflictMode = OperationConflictMode.REPLACE;
   private OperationSelectionMode operationSelectionMode = OperationSelectionMode.CUBOID;
   /** Suppress neighbor updates by default; users can opt into vanilla updates. */
   private PlacementUpdateMode placementUpdateMode = PlacementUpdateMode.CLIENT_ONLY;
   private final Set<ResourceLocation> enabledPlacementEffects = new HashSet<>();
   private boolean emptyHandWrench = true;
   private boolean skipRepeatedCropStages = true;
   private boolean forcePlacement = true;
   private boolean vanillaBlockHistory;
   private int maxPlacement = MAX_PLACEMENT;
   private int worldUndoHistoryLimit = DEFAULT_WORLD_UNDO_HISTORY_LIMIT;
   private int sessionUndoHistoryLimit = SessionHistoryLimits.DEFAULT_SESSION_UNDO_HISTORY_LIMIT;
   private io.github.fastformer.common.history.HistoryCompressionLimits tinkerHistoryMergeLimits = io.github.fastformer.server.history.HistoryStorageConfig.defaultMergeLimits();
   private double angleDegrees = 0.0;

   private FastPlaceSettings() {
      this.enabledPlacementEffects.add(
         io.github.fastformer.common.placement.effect.woodframe.WoodFramePlacementEffect.ID
      );
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

   public static FastPlaceSettings fromTag(CompoundTag tag) {
      FastPlaceSettings settings = new FastPlaceSettings();
      settings.enabled = !tag.contains("enabled") || tag.getBoolean("enabled");
      settings.middleConfirmEnabled = !tag.contains("middleConfirmEnabled") || tag.getBoolean("middleConfirmEnabled");
      settings.pointMode = readEnum(tag, "pointMode", PointMode.RAYCAST);
      // Raycast placement is now an input policy. Legacy preferences must not
      // override the surface-by-default and Alt-to-embed behavior.
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
      settings.placementUpdateMode = readEnum(tag, "placementUpdateMode", PlacementUpdateMode.CLIENT_ONLY);
      if (tag.contains("enabledPlacementEffects", Tag.TAG_LIST)) {
         settings.enabledPlacementEffects.clear();
         ListTag effectIds = tag.getList("enabledPlacementEffects", Tag.TAG_STRING);
         for (int index = 0; index < effectIds.size(); index++) {
            ResourceLocation id = ResourceLocation.tryParse(effectIds.getString(index));
            if (id != null && io.github.fastformer.common.placement.effect.PlacementEffectRegistry.contains(id)) {
               settings.enabledPlacementEffects.add(id);
            }
         }
      }
      settings.emptyHandWrench = !tag.contains("emptyHandWrench") || tag.getBoolean("emptyHandWrench");
      settings.skipRepeatedCropStages = !tag.contains("skipRepeatedCropStages") || tag.getBoolean("skipRepeatedCropStages");
      settings.forcePlacement = !tag.contains("forcePlacement") || tag.getBoolean("forcePlacement");
      settings.vanillaBlockHistory = tag.getBoolean("vanillaBlockHistory");
      settings.maxPlacement = tag.contains("maxPlacement")
         ? Math.clamp((long)tag.getInt("maxPlacement"), 1, MAX_PLACEMENT)
         : MAX_PLACEMENT;
      settings.worldUndoHistoryLimit = tag.contains("undoHistoryLimit")
         ? Math.clamp((long)tag.getInt("undoHistoryLimit"), 1, SessionHistoryLimits.MAX_UNDO_HISTORY_LIMIT)
         : DEFAULT_WORLD_UNDO_HISTORY_LIMIT;
      settings.sessionUndoHistoryLimit = tag.contains("sessionUndoHistoryLimit")
         ? Math.clamp((long)tag.getInt("sessionUndoHistoryLimit"), 1, SessionHistoryLimits.MAX_UNDO_HISTORY_LIMIT)
         : SessionHistoryLimits.DEFAULT_SESSION_UNDO_HISTORY_LIMIT;
      double savedAngle = tag.getDouble("angleDegrees");
      settings.tinkerHistoryMergeLimits = readTinkerHistoryLimits(tag);
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

   public QuickShapeMode modeFor(QuickShapeStage stage) {
      return QuickShapeModeRules.validMode(stage, this.storedLineMode(), this.storedModeFor(stage));
   }

   QuickShapeMode storedModeFor(QuickShapeStage stage) {
      return (QuickShapeMode)(switch (stage) {
         case POINT -> this.pointMode;
         case LINE -> this.lineMode;
         case FACE -> this.faceMode;
         case VOLUME -> this.volumeMode;
      });
   }

   public LineMode storedLineMode() {
      return this.lineMode;
   }

   FaceMode storedFaceMode() {
      return this.faceMode;
   }

   public PointMode pointMode() {
      return (PointMode)this.modeFor(QuickShapeStage.POINT);
   }

   public LineMode lineMode() {
      return (LineMode)this.modeFor(QuickShapeStage.LINE);
   }

   public FaceMode faceMode() {
      return (FaceMode)this.modeFor(QuickShapeStage.FACE);
   }

   public VolumeMode volumeMode() {
      return (VolumeMode)this.modeFor(QuickShapeStage.VOLUME);
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

   public QuickShapeMode cycleMode(ServerPlayer player, QuickShapeStage stage) {
      this.setStoredMode(stage, QuickShapeModeRules.nextMode(stage, this.storedLineMode(), this.storedModeFor(stage)));
      this.save(player);
      return this.modeFor(stage);
   }

   public void setMode(ServerPlayer player, QuickShapeMode mode) {
      this.setStoredMode(mode.stage(), mode);
      this.save(player);
   }

   public FillMode fillMode() {
      return this.fillMode;
   }

   public FastPlaceGeometry.Modes modes() {
      return new FastPlaceGeometry.Modes(
         this.pointMode(), RaycastPlacement.SURFACE, this.lineMode(), this.faceMode(), this.volumeMode(), this.fillMode,
         this.angleDegrees, false, io.github.fastformer.common.geometry.LineTieBias.DEFAULT
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
      this.worldUndoHistoryLimit = Math.clamp((long)value, 1, SessionHistoryLimits.MAX_UNDO_HISTORY_LIMIT);
      this.save(player);
      HistorySystem.of(player).trimToSetting(player, this.worldUndoHistoryLimit);
   }

   public int sessionUndoHistoryLimit() {
      return this.sessionUndoHistoryLimit;
   }

   public void setSessionUndoHistoryLimit(ServerPlayer player, int value) {
      this.sessionUndoHistoryLimit = Math.clamp((long)value, 1, SessionHistoryLimits.MAX_UNDO_HISTORY_LIMIT);
      this.save(player);
      OperationSystem.of(player).updateSessionHistoryLimit(player, this.sessionUndoHistoryLimit);
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

   public boolean isPlacementEffectEnabled(ResourceLocation effectId) {
      return effectId != null && this.enabledPlacementEffects.contains(effectId);
   }

   public Set<ResourceLocation> enabledPlacementEffects() {
      return Set.copyOf(this.enabledPlacementEffects);
   }

   public void setPlacementEffectEnabled(ServerPlayer player, ResourceLocation effectId, boolean enabled) {
      if (effectId == null) {
         return;
      }
      if (enabled) {
         this.enabledPlacementEffects.add(effectId);
      } else {
         this.enabledPlacementEffects.remove(effectId);
      }
      this.save(player);
   }

   public boolean emptyHandWrench() {
      return this.emptyHandWrench;
   }

   public boolean skipRepeatedCropStages() { return this.skipRepeatedCropStages; }

   public void toggleSkipRepeatedCropStages(ServerPlayer player) {
      setSkipRepeatedCropStages(player, !skipRepeatedCropStages);
   }

   public void setSkipRepeatedCropStages(ServerPlayer player, boolean value) {
      this.skipRepeatedCropStages = value;
      this.save(player);
   }

   public io.github.fastformer.common.history.HistoryCompressionLimits tinkerHistoryMergeLimits() { return tinkerHistoryMergeLimits; }

   public void setTinkerHistoryMergeLimit(ServerPlayer player, io.github.fastformer.common.history.CompressionSetting setting, int value) {
      tinkerHistoryMergeLimits = tinkerHistoryMergeLimits.with(setting, value);
      save(player);
      HistorySystem.of(player).requestCompaction(player);
   }

   public boolean forcePlacement() { return forcePlacement; }

   public boolean vanillaBlockHistory() { return vanillaBlockHistory; }

   public void toggleVanillaBlockHistory(ServerPlayer player) {
      setVanillaBlockHistory(player, !vanillaBlockHistory);
   }

   public void setVanillaBlockHistory(ServerPlayer player, boolean value) {
      vanillaBlockHistory = value;
      save(player);
   }

   public void setForcePlacement(ServerPlayer player, boolean value) {
      forcePlacement = value;
      save(player);
   }

   public void toggleEmptyHandWrench(ServerPlayer player) {
      setEmptyHandWrench(player, !emptyHandWrench);
   }

   public void setEmptyHandWrench(ServerPlayer player, boolean value) {
      this.emptyHandWrench = value;
      this.save(player);
   }

   public void setPlacementUpdateMode(ServerPlayer player, PlacementUpdateMode mode) {
      this.placementUpdateMode = mode;
      this.save(player);
   }

   public void setMaxPlacement(ServerPlayer player, int value) {
      this.maxPlacement = Math.clamp((long)value, 1, MAX_PLACEMENT);
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
      tag.putString("pointMode", this.pointMode.name());
      tag.putString("lineMode", this.lineMode.name());
      tag.putString("faceMode", this.faceMode.name());
      tag.putString("volumeMode", this.volumeMode.name());
      tag.putString("conePlaneMode", this.conePlaneMode.name());
      tag.putString("fillMode", this.fillMode.name());
      tag.putString("placementConflictMode", this.placementConflictMode.name());
      tag.putString("operationSelectionMode", this.operationSelectionMode.name());
      tag.putString("placementUpdateMode", this.placementUpdateMode.name());
      ListTag effectIds = new ListTag();
      this.enabledPlacementEffects.stream().map(ResourceLocation::toString).sorted()
         .forEach(id -> effectIds.add(net.minecraft.nbt.StringTag.valueOf(id)));
      tag.put("enabledPlacementEffects", effectIds);
      tag.putBoolean("emptyHandWrench", this.emptyHandWrench);
      tag.putBoolean("skipRepeatedCropStages", this.skipRepeatedCropStages);
      tag.putBoolean("forcePlacement", forcePlacement);
      tag.putBoolean("vanillaBlockHistory", vanillaBlockHistory);
      tag.putInt("maxPlacement", this.maxPlacement);
      tag.putInt("undoHistoryLimit", this.worldUndoHistoryLimit);
      tag.putInt("sessionUndoHistoryLimit", this.sessionUndoHistoryLimit);
      CompoundTag tinkerHistory = new CompoundTag();
      tinkerHistory.putInt("recentAfter", tinkerHistoryMergeLimits.recentAfter());
      tinkerHistory.putInt("recentGroup", tinkerHistoryMergeLimits.recentGroup());
      tinkerHistory.putInt("olderAfter", tinkerHistoryMergeLimits.olderAfter());
      tinkerHistory.putInt("olderGroup", tinkerHistoryMergeLimits.olderGroup());
      tinkerHistory.putInt("oldestAfter", tinkerHistoryMergeLimits.oldestAfter());
      tinkerHistory.putInt("oldestGroup", tinkerHistoryMergeLimits.oldestGroup());
      tag.put("tinkerHistoryMerge", tinkerHistory);
      tag.putDouble("angleDegrees", this.angleDegrees);
      return tag;
   }

   public static void copy(ServerPlayer from, ServerPlayer to) {
      CompoundTag root = from.getPersistentData();
      if (root.contains("fastformer", 10)) {
         to.getPersistentData().put("fastformer", root.getCompound("fastformer").copy());
      }
   }

   private static io.github.fastformer.common.history.HistoryCompressionLimits readTinkerHistoryLimits(CompoundTag tag) {
      var defaults = io.github.fastformer.server.history.HistoryStorageConfig.defaultMergeLimits();
      if (tag.contains("tinkerHistoryMerge", Tag.TAG_COMPOUND)) {
         CompoundTag limits = tag.getCompound("tinkerHistoryMerge");
         return new io.github.fastformer.common.history.HistoryCompressionLimits(
            limits.contains("recentAfter") ? limits.getInt("recentAfter") : defaults.recentAfter(),
            limits.contains("recentGroup") ? limits.getInt("recentGroup") : defaults.recentGroup(),
            limits.contains("olderAfter") ? limits.getInt("olderAfter") : defaults.olderAfter(),
            limits.contains("olderGroup") ? limits.getInt("olderGroup") : defaults.olderGroup(),
            limits.contains("oldestAfter") ? limits.getInt("oldestAfter") : defaults.oldestAfter(),
            limits.contains("oldestGroup") ? limits.getInt("oldestGroup") : defaults.oldestGroup());
      }
      return defaults;
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

   private void setStoredMode(QuickShapeStage stage, QuickShapeMode mode) {
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
