package io.github.fastformer.server.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class TaskFeedbackTest {
   @Test
   void deliveryKeepsLatestProgressAndLastFourResultsInOrder() {
      var feedback = new TaskFeedback();
      UUID owner = UUID.randomUUID();
      feedback.deferActionBar(owner, Component.literal("first"));
      feedback.deferActionBar(owner, Component.literal("latest"));
      for (int i = 0; i < 10; i++) feedback.deferChat(owner, Component.literal(Integer.toString(i)));
      var progress = new ArrayList<String>();
      var results = new ArrayList<String>();
      feedback.deliver(owner, message -> progress.add(message.getString()), message -> results.add(message.getString()));
      assertEquals(List.of("latest"), progress);
      assertEquals(List.of("6", "7", "8", "9"), results);
      feedback.deliver(owner, message -> progress.add(message.getString()), message -> results.add(message.getString()));
      assertEquals(1, progress.size());
      assertEquals(4, results.size());
   }

   @Test
   void sameOwnerOnDifferentServersKeepsSeparateMessages() {
      var first = new TaskFeedback();
      var second = new TaskFeedback();
      UUID owner = UUID.randomUUID();
      first.deferActionBar(owner, Component.literal("first"));
      second.deferActionBar(owner, Component.literal("second"));
      first.onDiscard();
      var messages = new ArrayList<String>();
      second.deliver(owner, message -> messages.add(message.getString()), message -> messages.add(message.getString()));
      assertEquals(List.of("second"), messages);
   }

   @Test
   void closedFeedbackDropsPendingAndLateMessages() {
      var feedback = new TaskFeedback();
      UUID owner = UUID.randomUUID();
      feedback.deferActionBar(owner, Component.literal("pending"));
      feedback.deferChat(owner, Component.literal("pending"));
      feedback.onDiscard();
      feedback.onDiscard();
      feedback.deferActionBar(owner, Component.literal("late"));
      feedback.deferChat(owner, Component.literal("late"));
      var messages = new ArrayList<Component>();
      feedback.deliver(owner, messages::add, messages::add);
      assertTrue(messages.isEmpty());
   }

   @Test
   void attachingOneOwnerDoesNotDrainAnotherOwnersResults() {
      var feedback = new TaskFeedback();
      UUID owner = UUID.randomUUID();
      UUID other = UUID.randomUUID();
      feedback.deferChat(owner, Component.literal("owner"));
      feedback.deferChat(other, Component.literal("other"));
      var messages = new ArrayList<String>();
      feedback.deliver(owner, ignored -> {}, message -> messages.add(message.getString()));
      assertEquals(List.of("owner"), messages);
      messages.clear();
      feedback.deliver(other, ignored -> {}, message -> messages.add(message.getString()));
      assertEquals(List.of("other"), messages);
   }
}
