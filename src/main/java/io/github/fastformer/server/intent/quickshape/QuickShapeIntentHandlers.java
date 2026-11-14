package io.github.fastformer.server.intent.quickshape;

import io.github.fastformer.core.command.IntentBus;
import io.github.fastformer.core.command.IntentContext;
import io.github.fastformer.core.command.IntentResult;
import io.github.fastformer.core.gate.Gate;
import io.github.fastformer.server.feedback.PlayerMessages;
import io.github.fastformer.server.gate.ServerOperationGates;
import io.github.fastformer.server.session.QuickShapeSystem;
import net.minecraft.server.level.ServerPlayer;

/** Quick-shape intent admission and the existing command failure feedback. */
public final class QuickShapeIntentHandlers {
   private static final Gate<IntentContext<ServerPlayer>> AVAILABLE =
      context -> ServerOperationGates.AVAILABLE.test(context.actor());

   private QuickShapeIntentHandlers() {}

   public static void register(IntentBus<ServerPlayer> bus) {
      bus.register(QuickShapeIntents.ContextValue.class, AVAILABLE, (context, intent) -> {
         var player = context.actor();
         if (!finite(player, intent.value())) return IntentResult.pass();
         return result(player, QuickShapeSystem.of(player).setContextValue(player, intent.value()),
            "fastformer.message.context_value_unavailable");
      });
      bus.register(QuickShapeIntents.FaceAngle.class, AVAILABLE, (context, intent) -> {
         var player = context.actor();
         if (!finite(player, intent.degrees())) return IntentResult.pass();
         return result(player, QuickShapeSystem.of(player).setFaceAngle(player, intent.degrees()),
            "fastformer.message.face_angle_unavailable");
      });
      bus.register(QuickShapeIntents.LineDistance.class, AVAILABLE, (context, intent) -> {
         if (intent.blocks() < 1 || intent.blocks() > 128) return IntentResult.pass();
         var player = context.actor();
         return result(player, QuickShapeSystem.of(player).setLineAngleDistance(player, intent.blocks()),
            "fastformer.message.line_free_distance_unavailable");
      });
      bus.register(QuickShapeIntents.VolumeDistance.class, AVAILABLE, (context, intent) -> {
         if (intent.blocks() < -128 || intent.blocks() > 128) return IntentResult.pass();
         var player = context.actor();
         return result(player, QuickShapeSystem.of(player).setVolumeDistance(player, intent.blocks()),
            "fastformer.message.volume_distance_unavailable");
      });
      bus.register(QuickShapeIntents.SelectStageMode.class, AVAILABLE, (context, intent) -> {
         QuickShapeSystem.of(context.actor()).setStageMode(context.actor(), intent.mode());
         return IntentResult.success();
      });
   }

   private static boolean finite(ServerPlayer player, double value) {
      if (Double.isFinite(value)) return true;
      PlayerMessages.chat(player, PlayerMessages.text("fastformer.message.number_invalid"));
      return false;
   }

   private static IntentResult result(ServerPlayer player, boolean applied, String failure) {
      if (!applied) PlayerMessages.chat(player, PlayerMessages.text(failure));
      return IntentResult.handled(applied);
   }
}
