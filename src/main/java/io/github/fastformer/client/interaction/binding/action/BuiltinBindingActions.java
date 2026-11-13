package io.github.fastformer.client.interaction.binding.action;

import com.mojang.serialization.Codec;
import io.github.fastformer.common.interaction.binding.SessionProfiles;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;

/** Adapts data actions to the existing input owner's atomic operations. */
public final class BuiltinBindingActions {
   private BuiltinBindingActions() {}

   public static BindingActions create() {
      BindingActions actions = new BindingActions();
      actions.register(id("quick_shape/start"), ResourceLocation.CODEC.optionalFieldOf("profile", SessionProfiles.NONE).codec(),
         profile -> (context, commands) -> result(commands.start(profile)));
      register(actions, "quick_shape/point", BindingCommands::point);
      register(actions, "quick_shape/middle_confirm", BindingCommands::middleConfirm);
      register(actions, "quick_shape/undo_point", BindingCommands::undoPoint);
      register(actions, "session/cancel", BindingCommands::cancel);
      register(actions, "mode/cycle", BindingCommands::cycleMode);
      actions.register(id("pass"), Codec.unit(true), ignored -> (context, commands) -> BindingAction.Result.PASS);
      actions.register(id("block"), Codec.unit(true), ignored -> (context, commands) -> BindingAction.Result.HANDLED);
      return actions.freeze();
   }

   private static void register(BindingActions actions, String path, Predicate<BindingCommands> operation) {
      actions.register(id(path), Codec.unit(true), ignored -> (context, commands) -> result(operation.test(commands)));
   }

   private static BindingAction.Result result(boolean handled) { return handled ? BindingAction.Result.HANDLED : BindingAction.Result.PASS; }
   private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("fastformer", path); }
}
