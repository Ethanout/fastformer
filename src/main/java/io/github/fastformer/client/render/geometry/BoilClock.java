package io.github.fastformer.client.render.geometry;

import io.github.fastformer.client.render.theme.VisualThemes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** One drawing sheet per rendered frame, shared by geometry and material passes. */
@EventBusSubscriber(modid = "fastformer", value = Dist.CLIENT)
public final class BoilClock {
   private static long tick = Long.MIN_VALUE;
   private static int current = 1;
   private static int sheets;

   private BoilClock() { }

   @SubscribeEvent
   public static void beginFrame(RenderFrameEvent.Pre event) {
      int count = Math.max(2, (int)VisualThemes.value("boil_sheets", 3));
      long nextTick = (long)Math.floor(System.nanoTime() / 1.0E9 * VisualThemes.value("boil_fps", 8));
      if (tick != nextTick || sheets != count) {
         current = next(current, count, nextTick);
         tick = nextTick;
         sheets = count;
      }
   }

   public static int sheet(boolean dynamic) { return dynamic ? current : 0; }

   static int next(int previous, int count, long tick) {
      count = Math.max(2, count);
      boolean validPrevious = previous >= 1 && previous <= count;
      int choice = 1 + (int)Math.floorMod(BoilJitter.mix(tick), (long)(validPrevious ? count - 1 : count));
      return validPrevious && choice >= previous ? choice + 1 : choice;
   }
}
