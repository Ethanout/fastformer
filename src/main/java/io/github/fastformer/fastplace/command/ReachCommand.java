package io.github.fastformer.fastplace.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.fastplace.settings.ReachThresholds;
import io.github.fastformer.fastplace.text.FastPlaceMessages;
import io.github.fastformer.network.sync.PlayerPreviewSync;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/** Configures the near threshold and the vanilla interaction attributes. */
final class ReachCommand {
   private ReachCommand() { }

   static LiteralArgumentBuilder<CommandSourceStack> create() {
      return Commands.literal("reach")
         .then(Commands.literal("close").then(Commands.argument("distance", distance()).executes(context -> {
            var player = context.getSource().getPlayerOrException();
            return setClose(player, IntegerArgumentType.getInteger(context, "distance"));
         })))
         .then(Commands.literal("far").then(Commands.argument("distance", distance()).executes(context -> {
            var player = context.getSource().getPlayerOrException();
            return set(player, FastPlaceSettings.load(player).reachThresholds().close(), IntegerArgumentType.getInteger(context, "distance"));
         })))
         .then(Commands.argument("closeDistance", distance()).then(Commands.argument("farDistance", distance()).executes(context ->
            set(context.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(context, "closeDistance"),
               IntegerArgumentType.getInteger(context, "farDistance")))))
         .then(Commands.literal("reset").executes(context -> set(context.getSource().getPlayerOrException(),
            ReachThresholds.DEFAULT.close(), ReachThresholds.DEFAULT.far())));
   }

   private static IntegerArgumentType distance() {
      return IntegerArgumentType.integer(0, 64);
   }

   private static int setClose(ServerPlayer player, int close) {
      var current = FastPlaceSettings.load(player).reachThresholds();
      FastPlaceSettings.load(player).setReachThresholds(player, new ReachThresholds(close, Math.max(close + 1, current.far())));
      PlayerPreviewSync.syncReachSettings(player);
      FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.reach_status", close, player.blockInteractionRange()));
      return 1;
   }

   private static int set(ServerPlayer player, int close, int far) {
      if (!ReachThresholds.valid(close, far)) {
         FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.reach_invalid"));
         return 0;
      }
      FastPlaceSettings.load(player).setReachThresholds(player, new ReachThresholds(close, far));
      io.github.fastformer.fastplace.settings.PlayerReachAttributes.set(player, far);
      PlayerPreviewSync.syncReachSettings(player);
      FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.reach_status", close, far));
      return 1;
   }
}
