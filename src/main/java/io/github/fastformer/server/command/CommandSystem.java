package io.github.fastformer.server.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import io.github.fastformer.server.command.edit.QuickShapeCommands;
import io.github.fastformer.server.command.edit.GeometryCommands;
import io.github.fastformer.server.command.edit.RotationCommands;
import io.github.fastformer.server.command.edit.SelectionCommands;

/** Assembles both public command roots from feature-owned declarations. */
public final class CommandSystem {
   private CommandSystem() {}

   public static void bootstrap() {
      NeoForge.EVENT_BUS.addListener(CommandSystem::registerCommands);
   }

   private static void registerCommands(RegisterCommandsEvent event) {
      register(event.getDispatcher(), "ff");
      register(event.getDispatcher(), "fastformer");
   }

   public static void register(CommandDispatcher<CommandSourceStack> dispatcher, String name) {
      dispatcher.register(
         Commands.literal(name)
            .then(QuickShapeCommands.contextValueCommand())
            .then(QuickShapeCommands.lineCommand())
            .then(QuickShapeCommands.faceCommand())
            .then(QuickShapeCommands.volumeCommand())
            .then(SelectionCommands.operationCommand())
            .then(GeometryCommands.geometryCommand())
            .then(ActionCommands.actionCommand())
            .then(RotationCommands.rotateCommand())
            .then(SettingsCommands.placementCommand())
            .then(ReachCommand.create())
            .then(SettingsCommands.forcePlacementCommand())
            .then(HistoryCommands.undoCommand())
            .then(HistoryCommands.redoCommand())
            .then(SettingsCommands.fillModeCommand())
            .then(SettingsCommands.maxPlacementCommand())
            .then(SettingsCommands.undoHistoryLimitCommand())
            .then(SettingsCommands.sessionUndoHistoryLimitCommand())
            .then(HistoryCommands.historyCleanupCommand())
            .then(SettingsCommands.middleConfirmCommand())
            .then(SettingsCommands.settingsCommand())
            .then(SettingsCommands.toggleCommand())
            .then(ActionCommands.cancelCommand())
            .then(ActionCommands.modeCommand())
            .then(SettingsCommands.statusCommand())
      );
   }
}
