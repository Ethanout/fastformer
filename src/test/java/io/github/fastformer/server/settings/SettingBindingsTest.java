package io.github.fastformer.server.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.fastformer.core.command.IntentResult;
import io.github.fastformer.protocol.settings.SettingCatalog;
import io.github.fastformer.protocol.settings.SettingKey;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class SettingBindingsTest {
   private final SettingCatalog catalog = new SettingCatalog();
   private final SettingKey<Integer> count = catalog.register(ResourceLocation.parse("test:count"), Integer.class, ByteBufCodecs.VAR_INT);

   @Test
   void queryDoesNotInventAValueAndUsesTheSameTypedHandler() {
      var bindings = SettingBindings.<List<Integer>>create(registry -> registry.register(count, (values, value) -> {
         value.ifPresent(values::add);
         return IntentResult.success();
      }));
      var values = new ArrayList<Integer>();
      bindings.apply(values, count.set(42));
      bindings.apply(values, count.query());
      assertEquals(List.of(42), values);
   }

   @Test
   void unknownBindingPassesWithoutRunningAnotherHandler() {
      var missing = catalog.register(ResourceLocation.parse("test:missing"), Integer.class, ByteBufCodecs.VAR_INT);
      var bindings = SettingBindings.<List<Integer>>create(registry -> registry.register(count, (values, value) -> {
         values.add(1);
         return IntentResult.success();
      }));
      var values = new ArrayList<Integer>();
      assertEquals(IntentResult.Status.PASS, bindings.apply(values, missing.set(9)).status());
      assertEquals(List.of(), values);
   }

   @Test
   void duplicateAndLateHandlersAreRejected() {
      assertThrows(IllegalArgumentException.class, () -> SettingBindings.create(registry -> {
         registry.register(count, (context, value) -> IntentResult.success());
         registry.register(count, (context, value) -> IntentResult.success());
      }));
      var bindings = SettingBindings.create(registry -> {});
      assertThrows(IllegalStateException.class, () -> bindings.register(count, (context, value) -> IntentResult.success()));
   }
}
