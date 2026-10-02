package io.github.fastformer.fastplace.placement.context;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.material.Fluids;

/** A literal property value or a value derived from the frozen placement context. */
public record PlacementFallbackValue(String expression) {
   private static final Set<String> SOURCES = Set.of(
      "$player_horizontal", "$player_horizontal_opposite", "$clicked_face", "$clicked_face_opposite",
      "$nearest_look", "$nearest_look_opposite", "$attachment_face", "$attachment_facing", "$waterlogged"
   );
   public static final Codec<PlacementFallbackValue> CODEC = Codec.STRING.comapFlatMap(
      PlacementFallbackValue::parse, PlacementFallbackValue::expression
   );

   private static DataResult<PlacementFallbackValue> parse(String expression) {
      if (expression.isBlank() || (expression.startsWith("$") && !SOURCES.contains(expression))) {
         return DataResult.error(() -> "Unknown placement fallback value: " + expression);
      }
      return DataResult.success(new PlacementFallbackValue(expression));
   }

   public String resolve(BlockPlaceContext context) {
      return switch (expression) {
         case "$player_horizontal" -> context.getHorizontalDirection().getSerializedName();
         case "$player_horizontal_opposite" -> context.getHorizontalDirection().getOpposite().getSerializedName();
         case "$clicked_face" -> context.getClickedFace().getSerializedName();
         case "$clicked_face_opposite" -> context.getClickedFace().getOpposite().getSerializedName();
         case "$nearest_look" -> context.getNearestLookingDirection().getSerializedName();
         case "$nearest_look_opposite" -> context.getNearestLookingDirection().getOpposite().getSerializedName();
         case "$attachment_face" -> switch (context.getClickedFace()) {
            case UP -> "floor";
            case DOWN -> "ceiling";
            default -> "wall";
         };
         case "$attachment_facing" -> attachmentFacing(context).getSerializedName();
         case "$waterlogged" -> Boolean.toString(
            context.getLevel().getFluidState(context.getClickedPos()).getType() == Fluids.WATER
         );
         default -> expression;
      };
   }

   private static Direction attachmentFacing(BlockPlaceContext context) {
      Direction face = context.getClickedFace();
      return face.getAxis().isHorizontal() ? face : context.getHorizontalDirection();
   }
}
