package io.github.fastformer.client.input;

import io.github.fastformer.client.input.state.ClientInputStateMachine.InputKind;
import io.github.fastformer.client.interaction.binding.context.ReplacementContext;
import io.github.fastformer.client.placement.QuickReplaceMode;
import io.github.fastformer.client.session.ClientSessionSystem;
import net.minecraft.client.Minecraft;

/** Adapts replacement events to bindings; the tool owns stroke state and bindings own policy. */
final class QuickReplaceBindingInput {
   record Result(boolean owns, boolean consumesVanilla) {}

   private QuickReplaceBindingInput() {}

   static Result mouse(Minecraft minecraft, ClientInputSession owner, int action, int button, long now) {
      boolean active = QuickReplaceMode.active();
      boolean consumes = false;
      boolean validPress = minecraft.player != null && minecraft.screen == null && minecraft.getConnection() != null;
      if (active && (action == 0 || validPress)) {
         String name = minecraft.options.keyUse.matchesMouse(button) ? "@use" : "mouse." + button;
         consumes = owner.bindings.onToolSignalResult(minecraft, owner, name, action == 0 ? "release" : "press",
            context(minecraft, owner), now).consumesVanilla();
      }
      return new Result(active, consumes);
   }

   static Result interaction(Minecraft minecraft, ClientInputSession owner, boolean useItem) {
      boolean active = QuickReplaceMode.active();
      boolean consumes = active && owner.bindings.onToolSignalResult(minecraft, owner, useItem ? "use" : "attack",
         "interaction", context(minecraft, owner), System.nanoTime()).consumesVanilla();
      return new Result(active, consumes);
   }

   static boolean tick(Minecraft minecraft, ClientInputSession owner) {
      boolean active = QuickReplaceMode.active();
      if (active) owner.bindings.onToolSignal(minecraft, owner, "client", "tick", context(minecraft, owner), System.nanoTime());
      return active;
   }

   static Result scroll(Minecraft minecraft, ClientInputSession owner, double delta) {
      boolean active = QuickReplaceMode.active();
      boolean consumes = active && owner.bindings.onToolSignalResult(minecraft, owner, "scroll", delta > 0 ? "scroll_up" : "scroll_down",
         context(minecraft, owner), System.nanoTime()).consumesVanilla();
      return new Result(active, consumes);
   }

   private static io.github.fastformer.client.interaction.binding.context.BindingContext context(
      Minecraft minecraft, ClientInputSession owner) {
      return new ReplacementContext(QuickReplaceMode.active(), QuickReplaceMode.canReplace(minecraft),
         minecraft.player != null && minecraft.player.isCreative(), ClientSessionSystem.instance().previews().building().enabled(),
         minecraft.screen == null, minecraft.isWindowActive(), owner.routing.routesToVanilla(InputKind.INTERACTION),
         minecraft.options.keyUse.isDown()).freeze();
   }
}
