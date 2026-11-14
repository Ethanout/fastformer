package io.github.fastformer.client.interaction.binding.tool;

import io.github.fastformer.client.interaction.binding.action.BindingCommands;
import io.github.fastformer.client.interaction.binding.action.BindingAction;
import io.github.fastformer.client.interaction.binding.context.SelectionBindingContext;
import io.github.fastformer.client.interaction.binding.context.BindingContext;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Matches resource bindings against one frozen press and always settles physical releases. */
public final class SelectionInputController {
   private static final int PRESS = 1;
   private static final int RELEASE = 0;
   private SelectionInputController() { }

   /** The input owner supplies commands and facts without exposing its mutable session. */
   public interface Host {
      void synchronize();
      boolean ownsPointer(int button);
      boolean allowsInteraction();
      SelectionBindingInput.Result interaction(int button);
      FrozenPress freeze(int button, long time, boolean press);
      BindingAction.Result signal(String name, String gesture, BindingContext context, BindingCommands commands);
      void pressed(int button, SelectionBindingInput.Result result);
      void finishRelease(int button, long time, boolean handled);
   }

   public record FrozenPress(SelectionBindingContext context, BindingCommands commands, BooleanSupplier releaseHandled) {
      public FrozenPress {
         Objects.requireNonNull(context, "context");
         Objects.requireNonNull(commands, "commands");
         Objects.requireNonNull(releaseHandled, "releaseHandled");
      }
   }

   public static SelectionBindingInput.Result captureBindings(Host host, int action, int button, long time) {
      host.synchronize();
      if (!host.ownsPointer(button)) return new SelectionBindingInput.Result(false, false);
      FrozenPress frozen = null;
      try {
         frozen = host.freeze(button, time, action == PRESS);
         var commands = frozen.commands();
         var result = SelectionBindingInput.mouse(true, frozen.context(), action, button, time,
            (name, gesture, facts, now) -> host.signal(name, gesture, facts, commands));
         if (action == PRESS) host.pressed(button, result);
         return result;
      } finally {
         if (action == RELEASE) host.finishRelease(button, time, frozen != null && frozen.releaseHandled().getAsBoolean());
      }
   }

   /** Probes the same resource decision when vanilla asks who owns an interaction. */
   public static SelectionBindingInput.Result interactionBindings(Host host, int button) {
      if (!host.allowsInteraction()) return new SelectionBindingInput.Result(false, false);
      host.synchronize();
      var captured = host.interaction(button);
      if (captured != null) return captured;
      var frozen = host.freeze(button, 0L, true);
      var probe = new BindingCommands() {
         public boolean selection(SelectionBindingCommand command, int durationMillis) { return true; }
         public void replayPress(long time) { throw new IllegalStateException("Selection probe cannot replay input"); }
      };
      return SelectionBindingInput.mouse(true, frozen.context(), PRESS, button, 0L,
         (name, gesture, facts, now) -> host.signal(name, gesture, facts, probe));
   }
}
