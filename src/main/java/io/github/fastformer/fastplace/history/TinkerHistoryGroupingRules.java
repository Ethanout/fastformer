package io.github.fastformer.fastplace.history;

import java.util.Set;

/** The config selects which semantic dimensions form a history group. */
public record TinkerHistoryGroupingRules(Set<KeyPart> shapeParts, Set<KeyPart> generalParts, Set<String> ignoredProperties,
   TinkerHistoryTags tags) {
   public enum KeyPart { MATERIAL, EDITED_HALF, EDIT_DIRECTION, FACE, BLOCK_TYPE, PROPERTIES, CONVERSION }
   public static final Set<KeyPart> SHAPE_PARTS = Set.of(KeyPart.MATERIAL, KeyPart.EDITED_HALF, KeyPart.EDIT_DIRECTION, KeyPart.FACE);
   public static final Set<KeyPart> GENERAL_PARTS = Set.of(KeyPart.FACE, KeyPart.BLOCK_TYPE, KeyPart.PROPERTIES, KeyPart.CONVERSION);

   public static final TinkerHistoryGroupingRules DEFAULTS = new TinkerHistoryGroupingRules(
      Set.of(KeyPart.MATERIAL, KeyPart.EDITED_HALF),
      Set.of(KeyPart.FACE, KeyPart.BLOCK_TYPE, KeyPart.PROPERTIES, KeyPart.CONVERSION), Set.of());

   public TinkerHistoryGroupingRules {
      shapeParts = Set.copyOf(shapeParts);
      generalParts = Set.copyOf(generalParts);
      ignoredProperties = Set.copyOf(ignoredProperties);
      java.util.Objects.requireNonNull(tags);
      if (!SHAPE_PARTS.containsAll(shapeParts) || !GENERAL_PARTS.containsAll(generalParts)) {
         throw new IllegalArgumentException("Unsupported history grouping dimension");
      }
   }

   public TinkerHistoryGroupingRules(Set<KeyPart> shapeParts, Set<KeyPart> generalParts, Set<String> ignoredProperties) {
      this(shapeParts, generalParts, ignoredProperties, TinkerHistoryTags.DEFAULTS);
   }
}
