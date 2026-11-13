package io.github.fastformer.server.command.edit;

import io.github.fastformer.core.command.IntentSource;
import io.github.fastformer.server.intent.PlayerIntentSystem;
import io.github.fastformer.server.intent.GeometryIntents;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.fastformer.fastplace.geometry.GeometryMode;
import io.github.fastformer.fastplace.geometry.cone.ConePlaneMode;
import io.github.fastformer.fastplace.geometry.polyhedron.PolyhedronSizeMode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import static io.github.fastformer.server.gate.CommandAdmission.available;
import static io.github.fastformer.server.command.CommandChecks.validNumber;
import static io.github.fastformer.server.command.edit.RotationCommands.rotateCommand;
import io.github.fastformer.server.command.syntax.CommandChoices;
import io.github.fastformer.server.command.syntax.CommandParameter;
import io.github.fastformer.server.command.syntax.IntentCommands;
import java.util.Map;

/** Feature-owned command declarations and execution adapters. */
public final class GeometryCommands {
   private GeometryCommands() {}

   public static LiteralArgumentBuilder<CommandSourceStack> geometryCommand() {
      return Commands.literal("geometry")
         .then(geometryModeCommand())
         .then(geometryConePlaneCommand())
         .then(geometryPolyhedronSizeCommand())
         .then(geometryEllipseCommand())
         .then(rotateCommand());
   }

   private static LiteralArgumentBuilder<CommandSourceStack> geometryModeCommand() {
      return new CommandChoices<>(Map.of(
         "wall", GeometryMode.WALL,
         "polyhedron", GeometryMode.POLYHEDRON,
         "cone_prism", GeometryMode.CONE_PRISM,
         "compound", GeometryMode.COMPOUND,
         "convex_polyhedron", GeometryMode.CONVEX_POLYHEDRON),
         "fastformer.message.geometry_mode_usage").intent("mode", GeometryIntents.SelectMode::new);
   }

   private static LiteralArgumentBuilder<CommandSourceStack> geometryConePlaneCommand() {
      return new CommandChoices<>(Map.of(
         "radius", ConePlaneMode.RADIUS,
         "diameter", ConePlaneMode.DIAMETER,
         "three_point", ConePlaneMode.THREE_POINT),
         "fastformer.message.cone_plane_usage").intent("cone_plane", GeometryIntents.ConePlane::new);
   }

   private static LiteralArgumentBuilder<CommandSourceStack> geometryPolyhedronSizeCommand() {
      return new CommandChoices<>(Map.of(
         "radius", PolyhedronSizeMode.RADIUS,
         "diameter", PolyhedronSizeMode.DIAMETER),
         "fastformer.message.polyhedron_size_usage").intent("polyhedron_size", GeometryIntents.PolyhedronSize::new);
   }

   private static LiteralArgumentBuilder<CommandSourceStack> geometryEllipseCommand() {
      var x = CommandParameter.number("x", 0.125, 8.0);
      var z = CommandParameter.number("z", 0.125, 8.0);
      return IntentCommands.routeCommand("ellipse", context -> {
         var player = context.getSource().getPlayerOrException();
         if (!available(player) || !validNumber(player, x.read(context), z.read(context))) return 0;
         return PlayerIntentSystem.dispatch(player, IntentSource.COMMAND,
            new GeometryIntents.ConeEllipse(x.read(context), z.read(context))).handled() ? 1 : 0;
      }, x, z);
   }
}
