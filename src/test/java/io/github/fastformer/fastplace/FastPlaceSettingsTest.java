package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.quickshape.LineMode;
import io.github.fastformer.fastplace.quickshape.RaycastPlacement;

import io.github.fastformer.fastplace.selection.OperationSelectionMode;

import io.github.fastformer.fastplace.world.*;

import io.github.fastformer.fastplace.session.*;
import io.github.fastformer.fastplace.workflow.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import io.github.fastformer.fastplace.placement.effect.woodframe.WoodFramePlacementEffect;
import org.junit.jupiter.api.Test;

class FastPlaceSettingsTest {
   @Test
   void placementEffectsDefaultToWoodFrameAndRoundTripAsIds() {
      FastPlaceSettings settings = FastPlaceSettings.fromTag(new CompoundTag());

      assertTrue(settings.isPlacementEffectEnabled(WoodFramePlacementEffect.ID));
      assertTrue(FastPlaceSettings.fromTag(settings.toTag())
         .isPlacementEffectEnabled(WoodFramePlacementEffect.ID));
      CompoundTag disabled = settings.toTag();
      disabled.put("enabledPlacementEffects", new ListTag());
      assertFalse(FastPlaceSettings.fromTag(disabled)
         .isPlacementEffectEnabled(WoodFramePlacementEffect.ID));
   }

   @Test
   void legacyWoodFrameFlagMigratesIntoEffectSet() {
      CompoundTag tag = new CompoundTag();
      tag.putBoolean("smartWoodFrame", false);

      assertFalse(FastPlaceSettings.fromTag(tag)
         .isPlacementEffectEnabled(WoodFramePlacementEffect.ID));
   }

   @Test
   void faceRasterizationDefaultsToPointSweepAndExperimentalModeRoundTrips() {
      assertEquals(
         FaceRasterizationMode.POINT_SWEEP,
         FastPlaceSettings.fromTag(new CompoundTag()).faceRasterizationMode()
      );
      CompoundTag tag = new CompoundTag();
      tag.putString(
         "faceRasterizationMode",
         FaceRasterizationMode.GRADIENT_CROSS_INTERPOLATED_EXPERIMENTAL.name()
      );
      FastPlaceSettings settings = FastPlaceSettings.fromTag(tag);

      assertEquals(
         FaceRasterizationMode.GRADIENT_CROSS_INTERPOLATED_EXPERIMENTAL,
         settings.faceRasterizationMode()
      );
      assertEquals(
         FaceRasterizationMode.GRADIENT_CROSS_INTERPOLATED_EXPERIMENTAL,
         settings.modes().faceRasterizationMode()
      );
      assertEquals(
         FaceRasterizationMode.GRADIENT_CROSS_INTERPOLATED_EXPERIMENTAL,
         FastPlaceSettings.fromTag(settings.toTag()).faceRasterizationMode()
      );
   }

   @Test
   void middleConfirmDefaultsToEnabledAndPersistsWhenDisabled() {
      FastPlaceSettings settings = FastPlaceSettings.fromTag(new CompoundTag());

      assertEquals(true, settings.middleConfirmEnabled());
      CompoundTag tag = settings.toTag();
      tag.putBoolean("middleConfirmEnabled", false);
      assertEquals(false, FastPlaceSettings.fromTag(tag).middleConfirmEnabled());
   }

   @Test
   void placementUpdatesDefaultToClientOnlyAndKeepAnExplicitNormalSetting() {
      FastPlaceSettings defaults = FastPlaceSettings.fromTag(new CompoundTag());
      CompoundTag normal = new CompoundTag();
      normal.putString("placementUpdateMode", PlacementUpdateMode.NORMAL.name());

      assertEquals(PlacementUpdateMode.CLIENT_ONLY, defaults.placementUpdateMode());
      assertEquals(PlacementUpdateMode.CLIENT_ONLY.name(), defaults.toTag().getString("placementUpdateMode"));
      assertEquals(PlacementUpdateMode.NORMAL, FastPlaceSettings.fromTag(normal).placementUpdateMode());
   }

   @Test
   void operationSelectionModePersistsAcrossSessions() {
      CompoundTag tag = new CompoundTag();
      tag.putString("operationSelectionMode", OperationSelectionMode.PRISM.name());

      FastPlaceSettings settings = FastPlaceSettings.fromTag(tag);

      assertEquals(OperationSelectionMode.PRISM, settings.operationSelectionMode());
      assertEquals(
         OperationSelectionMode.PRISM,
         FastPlaceSettings.fromTag(settings.toTag()).operationSelectionMode()
      );
   }

   @Test
   void conePlaneModeSurvivesSettingsSerialization() {
      CompoundTag tag = new CompoundTag();
      tag.putString("conePlaneMode", ConePlaneMode.DIAMETER.name());

      FastPlaceSettings settings = FastPlaceSettings.fromTag(tag);

      assertEquals(ConePlaneMode.DIAMETER, settings.conePlaneMode());
      assertEquals(ConePlaneMode.DIAMETER.name(), settings.toTag().getString("conePlaneMode"));
   }

   @Test
   void invalidSavedConePlaneModeFallsBackToRadius() {
      CompoundTag tag = new CompoundTag();
      tag.putString("conePlaneMode", "INVALID");

      assertEquals(ConePlaneMode.RADIUS, FastPlaceSettings.fromTag(tag).conePlaneMode());
   }

   @Test
   void worldAndSessionHistoryHaveIndependentDefaultsAndRoundTrip() {
      FastPlaceSettings settings = FastPlaceSettings.fromTag(new CompoundTag());

      assertEquals(200, settings.worldUndoHistoryLimit());
      assertEquals(100, settings.sessionUndoHistoryLimit());
      FastPlaceSettings restored = FastPlaceSettings.fromTag(settings.toTag());
      assertEquals(200, restored.worldUndoHistoryLimit());
      assertEquals(100, restored.sessionUndoHistoryLimit());
   }

   @Test
   void undoHistoryLimitIsClampedToSupportedRange() {
      CompoundTag low = new CompoundTag();
      low.putInt("undoHistoryLimit", 0);
      CompoundTag high = new CompoundTag();
      high.putInt("undoHistoryLimit", 801);

      assertEquals(1, FastPlaceSettings.fromTag(low).worldUndoHistoryLimit());
      assertEquals(800, FastPlaceSettings.fromTag(high).worldUndoHistoryLimit());
   }

   @Test
   void sessionUndoHistoryLimitIsClampedIndependently() {
      CompoundTag tag = new CompoundTag();
      tag.putInt("undoHistoryLimit", 300);
      tag.putInt("sessionUndoHistoryLimit", 900);

      FastPlaceSettings settings = FastPlaceSettings.fromTag(tag);

      assertEquals(300, settings.worldUndoHistoryLimit());
      assertEquals(800, settings.sessionUndoHistoryLimit());
   }

   @Test
   void quickRaycastDefaultsToSurfaceAndAltSelectsEmbedded() {
      CompoundTag tag = new CompoundTag();
      tag.putString("lineMode", LineMode.RAYCAST.name());
      FastPlaceSettings settings = FastPlaceSettings.fromTag(tag);
      assertEquals(RaycastPlacement.SURFACE, settings.modes().raycastPlacement());
      FastPlaceSession session = new FastPlaceSession();

      assertEquals(RaycastPlacement.SURFACE, FastPlaceManager.effectiveModes(settings, session).raycastPlacement());

      session.setModifierHeld(true);
      assertEquals(RaycastPlacement.EMBEDDED, FastPlaceManager.effectiveModes(settings, session).raycastPlacement());

      session.setModifierHeld(false);
      session.addPoint(
         new net.minecraft.core.BlockPos(0, 0, 0),
         net.minecraft.world.phys.Vec3.ZERO,
         new net.minecraft.world.phys.Vec3(0.0, 0.0, 1.0)
      );
      assertEquals(RaycastPlacement.SURFACE, FastPlaceManager.effectiveModes(settings, session).raycastPlacement());

      session.setModifierHeld(true);
      assertEquals(RaycastPlacement.EMBEDDED, FastPlaceManager.effectiveModes(settings, session).raycastPlacement());
   }

   @Test
   void legacyRaycastSettingDoesNotPersistOrOverrideInputSemantics() {
      CompoundTag tag = new CompoundTag();
      tag.putString("raycastPlacement", RaycastPlacement.EMBEDDED.name());
      FastPlaceSettings settings = FastPlaceSettings.fromTag(tag);
      FastPlaceSession session = new FastPlaceSession();

      assertFalse(settings.toTag().contains("raycastPlacement"));
      assertEquals(RaycastPlacement.SURFACE, settings.modes().raycastPlacement());
      assertEquals(RaycastPlacement.SURFACE, FastPlaceManager.effectiveModes(settings, session).raycastPlacement());
      session.setModifierHeld(true);
      assertEquals(RaycastPlacement.EMBEDDED, FastPlaceManager.effectiveModes(settings, session).raycastPlacement());
   }
}
