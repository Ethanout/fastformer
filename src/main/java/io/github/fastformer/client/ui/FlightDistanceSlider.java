package io.github.fastformer.client.ui;

import io.github.fastformer.client.FastFormerClientConfig;
import io.github.fastformer.fastplace.settings.FlightSettings;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

final class FlightDistanceSlider extends AbstractSliderButton {
   FlightDistanceSlider(int x, int y, int width) {
      super(x, y, width, 20, Component.empty(),
         (double)FastFormerClientConfig.flightSettings().dashDistance() / FlightSettings.MAX_DASH_DISTANCE);
      setTooltip(Tooltip.create(Component.translatable("fastformer.settings.flight_dash_distance.help")));
      updateMessage();
   }

   @Override protected void updateMessage() {
      setMessage(distance() == 0 ? Component.translatable("options.off")
         : Component.translatable("fastformer.settings.flight_dash_distance.value", distance()));
   }

   @Override protected void applyValue() {
      if (distance() != FastFormerClientConfig.flightSettings().dashDistance()) {
         FastFormerClientConfig.setDashDistance(distance());
      }
   }

   private int distance() {
      return (int)Math.round(value * FlightSettings.MAX_DASH_DISTANCE);
   }
}
