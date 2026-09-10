package io.github.fastformer.fastplace.placement.effect.woodframe;

import io.github.fastformer.fastplace.FastPlaceSettings;
import io.github.fastformer.fastplace.FillMode;
import io.github.fastformer.fastplace.SmartWoodFrame;
import io.github.fastformer.fastplace.placement.effect.PlacementEffect;
import io.github.fastformer.fastplace.placement.effect.PlacementEffectContext;
import io.github.fastformer.fastplace.placement.effect.ResolvedPlacementEffect;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Applies per-edge log axes to quick-shape frame blocks. */
public final class WoodFramePlacementEffect implements PlacementEffect {
   public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
      "fastformer", "wood_frame"
   );

   @Override
   public ResourceLocation id() {
      return ID;
   }

   @Override
   public String translationKey() {
      return "fastformer.settings.smart_wood_frame";
   }

   @Override
   public int priority() {
      return 100;
   }

   @Override
   public boolean enabled(FastPlaceSettings settings) {
      return settings.isPlacementEffectEnabled(ID);
   }

   @Override
   public boolean matches(PlacementEffectContext context) {
      return context.prototype() != null
         && context.modes().fillMode() == FillMode.OUTLINE
         && context.prototype().hasProperty(BlockStateProperties.AXIS);
   }

   @Override
   public ResolvedPlacementEffect resolve(PlacementEffectContext context) {
      SmartWoodFrame.Config config = SmartWoodFrame.config(
         context.baseAxis(),
         context.points(),
         context.modes(),
         context.polygonHeightConfirmed(),
         context.polygonVolumeShape()
      );
      var prototype = context.prototype();
      return new ResolvedPlacementEffect(
         ID,
         positions -> SmartWoodFrame.resolve(positions, prototype, config)
      );
   }
}
