package io.github.fastformer.client.session.tree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A state-tree cell with local signal handlers and enter/exit hooks. */
public final class ClientSessionNode {
   private final String key;
   private final ClientSessionNode parent;
   private final Map<String, ClientSessionNode> children = new LinkedHashMap<>();
   private final Map<String, Object> values = new LinkedHashMap<>();
   private final SessionSignalRegistry signals = new SessionSignalRegistry();
   private Runnable enterAction = () -> {
   };
   private Runnable exitAction = () -> {
   };
   private boolean active;

   public ClientSessionNode(String key) {
      this(key, null);
   }

   private ClientSessionNode(String key, ClientSessionNode parent) {
      if (key == null || key.isBlank()) {
         throw new IllegalArgumentException("Node key must not be blank");
      }
      this.key = key;
      this.parent = parent;
   }

   public String key() {
      return key;
   }

   public ClientSessionNode child(String childKey) {
      Objects.requireNonNull(childKey, "childKey");
      return children.computeIfAbsent(childKey, ignored -> new ClientSessionNode(childKey, this));
   }

   public Map<String, ClientSessionNode> children() {
      return Collections.unmodifiableMap(children);
   }

   public SessionSignalRegistry signals() {
      return signals;
   }

   /** Stores an object owned by this state cell. Values survive state transitions. */
   public ClientSessionNode put(String valueKey, Object value) {
      if (valueKey == null || valueKey.isBlank()) {
         throw new IllegalArgumentException("Value key must not be blank");
      }
      values.put(valueKey, Objects.requireNonNull(value, "value"));
      return this;
   }

   public boolean containsValue(String valueKey) {
      return values.containsKey(valueKey);
   }

   public <T> T value(String valueKey, Class<T> valueType) {
      Objects.requireNonNull(valueType, "valueType");
      Object value = values.get(valueKey);
      return value == null ? null : valueType.cast(value);
   }

   public Map<String, Object> values() {
      return Collections.unmodifiableMap(values);
   }

   public ClientSessionNode onEnter(Runnable action) {
      enterAction = Objects.requireNonNull(action, "action");
      return this;
   }

   public ClientSessionNode onExit(Runnable action) {
      exitAction = Objects.requireNonNull(action, "action");
      return this;
   }

   public boolean active() {
      return active;
   }

   public void enter() {
      if (active) {
         return;
      }
      active = true;
      enterAction.run();
   }

   public void exit() {
      if (!active) {
         return;
      }
      active = false;
      exitAction.run();
   }

   public int emit(SessionSignal signal) {
      return active ? signals.emit(signal) : 0;
   }

   public ClientSessionInspection inspect() {
      List<String> path = new ArrayList<>();
      ClientSessionNode node = this;
      while (node != null) {
         path.add(node.key);
         node = node.parent;
      }
      Collections.reverse(path);
      return new ClientSessionInspection(path);
   }
}
