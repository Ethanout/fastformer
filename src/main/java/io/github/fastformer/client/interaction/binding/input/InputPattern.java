package io.github.fastformer.client.interaction.binding.input;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Set;

/** Physical trigger description; duration values come from binding data. */
public record InputPattern(String button, String gesture, int durationMillis, String holdStart) {
   private static final Set<String> GESTURES = Set.of("press", "release", "hold", "double", "scroll_up", "scroll_down", "key");
   public static final Codec<InputPattern> CODEC = RecordCodecBuilder.<InputPattern>create(instance -> instance.group(
      Codec.STRING.fieldOf("button").forGetter(InputPattern::button),
      Codec.STRING.fieldOf("gesture").forGetter(InputPattern::gesture),
      Codec.intRange(1, 60_000).optionalFieldOf("duration_ms", 200).forGetter(InputPattern::durationMillis),
      Codec.STRING.optionalFieldOf("hold_start", "after_vanilla_press").forGetter(InputPattern::holdStart)
   ).apply(instance, InputPattern::new)).validate(InputPattern::validate);

   public InputPattern {
      java.util.Objects.requireNonNull(button);
      java.util.Objects.requireNonNull(gesture);
      java.util.Objects.requireNonNull(holdStart);
   }

   private static DataResult<InputPattern> validate(InputPattern pattern) {
      if (pattern.button.isBlank() || !GESTURES.contains(pattern.gesture)) return DataResult.error(() -> "Invalid input trigger");
      if (!Set.of("after_vanilla_press", "deferred").contains(pattern.holdStart))
         return DataResult.error(() -> "Invalid hold_start policy");
      return DataResult.success(pattern);
   }
}
