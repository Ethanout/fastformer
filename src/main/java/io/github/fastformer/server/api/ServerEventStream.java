package io.github.fastformer.server.api;

import com.mojang.logging.LogUtils;
import io.github.fastformer.common.api.EventSource;
import io.github.fastformer.common.api.FastFormerEvent;
import io.github.fastformer.core.lifecycle.Scoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/** Server-owned subscriptions and the NeoForge bridge; notifications cannot undo accepted work. */
public final class ServerEventStream implements EventSource, Scoped {
   private static final Logger LOGGER = LogUtils.getLogger();
   private final List<Listener<?>> listeners = new ArrayList<>();
   private final Consumer<FastFormerEvent> bridge;
   private final EventSource view = this::subscribe;
   private boolean closed;

   public ServerEventStream() { this(event -> NeoForge.EVENT_BUS.post(event)); }

   /** Allows an isolated owner to supply its own external event bus. */
   public ServerEventStream(Consumer<FastFormerEvent> bridge) {
      this.bridge = Objects.requireNonNull(bridge, "bridge");
   }

   public EventSource source() { return view; }

   @Override public <E extends FastFormerEvent> Subscription subscribe(Class<E> type, Consumer<? super E> callback) {
      if (closed) throw new IllegalStateException("Server event stream is closed");
      Listener<E> listener = new Listener<>(Objects.requireNonNull(type, "type"), Objects.requireNonNull(callback, "listener"));
      listeners.add(listener);
      return () -> { listener.active = false; listeners.remove(listener); };
   }

   public void publish(FastFormerEvent event) {
      Objects.requireNonNull(event, "event");
      if (closed) return;
      for (Listener<?> listener : List.copyOf(listeners)) {
         if (closed) return;
         if (listener.active) notifyQuietly(() -> listener.accept(event));
      }
      if (!closed) notifyQuietly(() -> bridge.accept(event));
   }

   @Override public void onDiscard() {
      closed = true;
      listeners.forEach(listener -> listener.active = false);
      listeners.clear();
   }

   private void notifyQuietly(Runnable notification) {
      try { notification.run(); }
      catch (RuntimeException | Error failure) {
         LOGGER.error("FastFormer event listener failed after an accepted change", failure);
      }
   }

   private static final class Listener<E extends FastFormerEvent> {
      private final Class<E> type;
      private final Consumer<? super E> callback;
      private boolean active = true;
      private Listener(Class<E> type, Consumer<? super E> callback) { this.type = type; this.callback = callback; }
      private void accept(FastFormerEvent event) { if (type.isInstance(event)) callback.accept(type.cast(event)); }
   }
}
