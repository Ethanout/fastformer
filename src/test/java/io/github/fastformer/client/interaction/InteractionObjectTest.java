package io.github.fastformer.client.interaction;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InteractionObjectTest {
   private static final InteractionObject.Id ID = new InteractionObject.Id(UUID.randomUUID(), "test", 1);

   @Test
   void componentKeysKeepValuesWithTheSameClassSeparate() {
      var text = new ComponentType<>("text", String.class);
      var tooltip = new ComponentType<>("tooltip", String.class);
      var object = InteractionObject.builder(ID).with(text, "label").with(tooltip, "hint").build();

      assertEquals("label", object.require(text));
      assertEquals("hint", object.require(tooltip));
      assertTrue(object.component(new ComponentType<>("text", String.class)).isEmpty());
   }

   @Test
   void snapshotsDoNotShareTheMutableBuilderOrInputMap() {
      var text = new ComponentType<>("text", String.class);
      var builder = InteractionObject.builder(ID).with(text, "first");
      var first = builder.build();
      builder.with(text, "second");
      assertEquals("first", first.require(text));

      Map<ComponentType<?>, Object> components = new HashMap<>();
      components.put(text, "third");
      var third = new InteractionObject(ID, components);
      components.clear();
      assertEquals("third", third.require(text));
      assertThrows(UnsupportedOperationException.class, () -> third.components().clear());
   }

   @Test
   void rawConstructionCannotBypassComponentTypeChecks() {
      var text = new ComponentType<>("text", String.class);
      assertThrows(ClassCastException.class, () -> new InteractionObject(ID, Map.of(text, 2)));
      assertThrows(NullPointerException.class, () -> InteractionObject.builder(ID).with(text, null));
      assertThrows(IllegalStateException.class, () -> InteractionObject.builder(ID).build().require(text));
   }

   @Test
   void stableLocalIdsStayIsolatedByOwnerAndObjectKind() {
      UUID session = UUID.randomUUID();
      var id = new InteractionObject.Id(session, "part_label", 2);
      assertEquals(id, new InteractionObject.Id(session, "part_label", 2));
      assertNotEquals(id, new InteractionObject.Id(UUID.randomUUID(), "part_label", 2));
      assertNotEquals(id, new InteractionObject.Id(session, "handle", 2));
      assertThrows(IllegalArgumentException.class, () -> new InteractionObject.Id(session, "part_label", 0));
   }
}
