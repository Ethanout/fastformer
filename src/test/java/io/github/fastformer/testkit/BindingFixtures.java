package io.github.fastformer.testkit;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import io.github.fastformer.client.interaction.binding.BindingSystem;
import io.github.fastformer.client.interaction.binding.action.BuiltinBindingActions;
import io.github.fastformer.client.interaction.binding.context.ContextFields;
import io.github.fastformer.client.interaction.binding.context.ContextMatcher;
import io.github.fastformer.client.interaction.binding.data.BindingFile;
import io.github.fastformer.client.interaction.binding.data.InteractionBinding;
import io.github.fastformer.client.interaction.binding.option.BindingOption;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;

/** Loads the same default binding resources and action registry used by the client. */
public final class BindingFixtures {
   private BindingFixtures() {}

   public static BindingFile defaults(String... names) throws Exception {
      var bindings = new ArrayList<InteractionBinding>();
      var options = new ArrayList<BindingOption>();
      var diagnostics = new ArrayList<String>();
      for (String name : names) {
         try (var stream = BindingFixtures.class.getResourceAsStream("/assets/fastformer/fastformer/bindings/default/" + name + ".json")) {
            assertNotNull(stream, name);
            var file = BindingFile.parse(JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)));
            bindings.addAll(file.bindings());
            options.addAll(file.options());
            diagnostics.addAll(file.diagnostics());
         }
      }
      return new BindingFile(List.copyOf(bindings), List.copyOf(options), List.copyOf(diagnostics));
   }

   public static BindingSystem system(BindingFile file) {
      assertEquals(List.of(), file.diagnostics());
      var actions = BuiltinBindingActions.create();
      var rules = file.bindings().stream().sorted(InteractionBinding.ORDER)
         .map(binding -> new BindingSystem.Rule(binding, actions.resolve(binding.action()).getOrThrow())).toList();
      return new BindingSystem(new ContextMatcher(ContextFields.defaults().freeze()), () -> rules, id -> true);
   }
}
