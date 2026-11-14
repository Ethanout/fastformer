package io.github.fastformer.server.feedback;

import io.github.fastformer.core.lifecycle.Scoped;
import io.github.fastformer.server.feedback.PlayerMessages;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Offline task feedback retained by one server, independently of a player's connection. */
public final class TaskFeedback implements Scoped {
   private static final int MAX_PENDING_CHAT = 4;
   private final Map<UUID, Component> actionBars = new HashMap<>();
   private final Map<UUID, ArrayDeque<Component>> chats = new HashMap<>();
   private boolean closed;

   public void deferActionBar(UUID owner, Component message) {
      if (!closed && owner != null && message != null) actionBars.put(owner, message);
   }

   public void deferChat(UUID owner, Component message) {
      if (closed || owner == null || message == null) return;
      var messages = chats.computeIfAbsent(owner, ignored -> new ArrayDeque<>());
      while (messages.size() >= MAX_PENDING_CHAT) messages.removeFirst();
      messages.addLast(message);
   }

   public void attach(ServerPlayer player) {
      deliver(player.getUUID(), message -> PlayerMessages.actionBar(player, message),
         message -> PlayerMessages.chat(player, message));
   }

   void deliver(UUID owner, Consumer<Component> actionBar, Consumer<Component> chat) {
      if (closed) return;
      Component progress = actionBars.remove(owner);
      var messages = chats.remove(owner);
      if (progress != null) actionBar.accept(progress);
      if (messages != null) messages.forEach(chat);
   }

   @Override
   public void onDiscard() {
      closed = true;
      actionBars.clear();
      chats.clear();
   }
}
