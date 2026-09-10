package io.github.fastformer.client.placement.effect;

import com.mojang.logging.LogUtils;
import io.github.fastformer.fastplace.FastPlaceGeometry;
import io.github.fastformer.fastplace.PlacementContextSnapshot;
import io.github.fastformer.fastplace.geometry.generation.GenerationFailed;
import io.github.fastformer.fastplace.geometry.generation.GenerationLimitExceeded;
import io.github.fastformer.fastplace.geometry.generation.GeneratedBlockSets;
import io.github.fastformer.fastplace.placement.effect.PlacementEffectContext;
import io.github.fastformer.fastplace.placement.effect.PlacementEffectRegistry;
import io.github.fastformer.fastplace.placement.effect.ResolvedPlacementEffect;
import io.github.fastformer.network.payload.preview.BuildingPreviewPayload;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

/** Replays the server-selected placement effect against client preview data. */
public final class PlacementEffectPreview {
   private static final Logger LOGGER = LogUtils.getLogger();

   private PlacementEffectPreview() {
   }

   public static ResolvedPlacementEffect resolve(
      BuildingPreviewPayload snapshot,
      LocalPlayer player,
      BlockState prototype,
      List<BlockPos> points,
      boolean polygonHeightConfirmed,
      FastPlaceGeometry.Modes modes,
      PlacementContextSnapshot placementContext
   ) {
      if (snapshot.activePlacementEffect() == null || prototype == null) {
         return null;
      }
      Direction.Axis baseAxis = placementContext == null
         ? Direction.Axis.Y
         : placementContext.clickedFace().getAxis();
      try {
         return PlacementEffectRegistry.resolve(
            snapshot.activePlacementEffect(),
            new PlacementEffectContext(
               player,
               player.getMainHandItem(),
               prototype,
               baseAxis,
               points,
               modes,
               polygonHeightConfirmed,
               snapshot.polygonVolumeShape(),
               placementContext
            )
         ).orElse(null);
      } catch (RuntimeException exception) {
         LOGGER.error("Unable to resolve FastFormer placement effect preview", exception);
         return null;
      }
   }

   public static Set<BlockPos> applyToTargets(
      ResolvedPlacementEffect effect, Set<BlockPos> blocks
   ) {
      if (effect == null || GenerationLimitExceeded.is(blocks) || GenerationFailed.is(blocks)) {
         return blocks;
      }
      try {
         return effect.applyToTargets(blocks);
      } catch (RuntimeException exception) {
         LOGGER.error("Unable to apply FastFormer placement effect preview", exception);
         return blocks;
      }
   }

   public static Map<BlockPos, BlockState> resolveStates(
      ResolvedPlacementEffect effect,
      Set<BlockPos> previewBlocks
   ) {
      if (effect == null || previewBlocks.isEmpty() || GenerationLimitExceeded.is(previewBlocks)
         || GenerationFailed.is(previewBlocks)) {
         return Map.of();
      }
      try {
         Map<BlockPos, BlockState> resolved = effect.stateOverrides().apply(
            GeneratedBlockSets.readOnly(previewBlocks)
         );
         HashMap<BlockPos, BlockState> visible = new HashMap<>(resolved);
         visible.keySet().retainAll(previewBlocks);
         return Map.copyOf(visible);
      } catch (RuntimeException exception) {
         LOGGER.error("Unable to resolve FastFormer placement effect preview states", exception);
         return Map.of();
      }
   }
}
