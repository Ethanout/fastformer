package io.github.fastformer.client.mode;

import io.github.fastformer.client.mode.builtin.*;

/** Client mode assembly; definitions are immutable and shared, sessions are not. */
public final class ClientModes {
   private static final ClientModeRegistry REGISTRY = create();
   private ClientModes() {}

   public static ClientModeRegistry registry() { return REGISTRY; }

   private static ClientModeRegistry create() {
      var modes = new ClientModeRegistry();
      modes.register(new IdleMode());
      modes.register(new QuickShapeMode());
      modes.register(new GeometryMode());
      modes.register(new SelectionMode());
      modes.register(new QuickReplaceMode());
      modes.register(new SmartFillMode());
      modes.register(new BusyMode());
      return modes.freeze();
   }
}
