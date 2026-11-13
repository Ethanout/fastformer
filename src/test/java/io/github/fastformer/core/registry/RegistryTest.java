package io.github.fastformer.core.registry;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

class RegistryTest {
   @Test
   void duplicateAndNullRegistrationLeaveExistingValuesIntact() {
      Registry<String, String> registry = Registry.create("test");
      registry.register("first", "original");
      assertThrows(IllegalArgumentException.class, () -> registry.register("first", "replacement"));
      assertThrows(NullPointerException.class, () -> registry.register(null, "value"));
      assertThrows(NullPointerException.class, () -> registry.register("other", null));
      assertEquals("original", registry.get("first").orElseThrow());
      assertTrue(registry.get("other").isEmpty());
   }

   @Test
   void freezeKeepsQueriesAvailableAndRejectsNewRegistration() {
      Registry<String, Integer> registry = Registry.create("test");
      registry.register("first", 1);
      assertSame(registry, registry.freeze());
      assertThrows(IllegalStateException.class, () -> registry.register("second", 2));
      assertEquals(1, registry.get("first").orElseThrow());
   }

   @Test
   void equalPrioritiesRetainRegistrationOrder() {
      Registry<String, String> registry = Registry.create("test");
      registry.register("a", "bbb");
      registry.register("b", "aa");
      registry.register("c", "cc");
      assertEquals(List.of("aa", "cc", "bbb"), registry.ordered(Comparator.comparingInt(String::length)).toList());
   }
}
