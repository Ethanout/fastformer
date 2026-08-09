package io.github.fastformer.fastplace.geometry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public record GeometryPreviewPlan(
   List<ControlPoint> controlPoints,
   MutableComponent stage,
   MutableComponent variant,
   MutableComponent hint,
   GeometryStageDisplay stageDisplay,
   AxisGizmo gizmo,
   Set<BlockPos> ghostBlocks,
   Set<BlockPos> pendingBlocks,
   List<GuideLine> guideLines,
   List<GuidePlane> guidePlanes,
   List<GeometryInteractionTarget> interactionTargets,
   List<GeometryTextBlock> textBlocks
) {
   public GeometryPreviewPlan {
      controlPoints = List.copyOf(controlPoints);
      stage = stage == null ? Component.empty() : stage;
      variant = variant == null ? Component.empty() : variant;
      hint = hint == null ? Component.empty() : hint;
      stageDisplay = stageDisplay == null ? GeometryStageDisplay.empty() : stageDisplay;
      ghostBlocks = ghostBlocks == null ? Set.of() : Set.copyOf(ghostBlocks);
      pendingBlocks = pendingBlocks == null ? Set.of() : Set.copyOf(pendingBlocks);
      guideLines = guideLines == null ? List.of() : List.copyOf(guideLines);
      guidePlanes = guidePlanes == null ? List.of() : List.copyOf(guidePlanes);
      interactionTargets = interactionTargets == null ? List.of() : List.copyOf(interactionTargets);
      textBlocks = textBlocks == null ? List.of() : List.copyOf(textBlocks);
   }

   public GeometryTextBlock textBlock(String id) {
      if (id == null) {
         return null;
      }
      return this.textBlocks.stream()
         .filter(block -> id.equals(block.id()))
         .findFirst()
         .orElse(null);
   }

   public boolean textVisible(String id) {
      GeometryTextBlock block = this.textBlock(id);
      return block == null || block.visible();
   }

   public GeometryPreviewPlan withGizmo(AxisGizmo gizmo) {
      return new GeometryPreviewPlan(
         this.controlPoints,
         this.stage,
         this.variant,
         this.hint,
         this.stageDisplay,
         gizmo,
         this.ghostBlocks,
         this.pendingBlocks,
         this.guideLines,
         this.guidePlanes,
         this.interactionTargets,
         this.textBlocks
      );
   }

   public static GeometryPreviewPlan controlPoints(List<BlockPos> points, BlockPos hoveredPoint) {
      return builder(points, hoveredPoint).build();
   }

   public static Builder builder(List<BlockPos> points, BlockPos hoveredPoint) {
      return new Builder(points, hoveredPoint);
   }

   public static final class Builder {
      private final List<BlockPos> points;
      private final BlockPos hoveredPoint;
      private MutableComponent variant = Component.empty();
      private MutableComponent stage = Component.empty();
      private MutableComponent hint = Component.empty();
      private GeometryStageDisplay stageDisplay = GeometryStageDisplay.empty();
      private AxisGizmo gizmo;
      private Set<BlockPos> ghostBlocks;
      private Set<BlockPos> pendingBlocks = Set.of();
      private List<GuideLine> guideLines = List.of();
      private List<GuidePlane> guidePlanes = List.of();
      private List<GeometryInteractionTarget> interactionTargets = List.of();
      private List<GeometryTextBlock> textBlocks = List.of(
         new GeometryTextBlock(GeometryTextBlock.STATUS_ID, GeometryTextBlock.Placement.BOTTOM_CENTER, null, true),
         new GeometryTextBlock(GeometryTextBlock.STAGE_ID, GeometryTextBlock.Placement.BOTTOM_CENTER, null, true),
         new GeometryTextBlock(GeometryTextBlock.MODE_ID, GeometryTextBlock.Placement.BOTTOM_CENTER, null, true),
         new GeometryTextBlock(GeometryTextBlock.VALUE_ID, GeometryTextBlock.Placement.BOTTOM_CENTER, null, true),
         new GeometryTextBlock(GeometryTextBlock.HINT_ID, GeometryTextBlock.Placement.BOTTOM_HINT, null, true)
      );
      private List<ControlPoint> controlPoints;

      private Builder(List<BlockPos> points, BlockPos hoveredPoint) {
         this.points = List.copyOf(points);
         this.hoveredPoint = hoveredPoint;
         this.ghostBlocks = Set.copyOf(points);
      }

      public Builder hud(MutableComponent variant, MutableComponent hint) {
         this.variant = variant == null ? Component.empty() : variant;
         this.hint = hint == null ? Component.empty() : hint;
         return this;
      }

      public Builder stage(MutableComponent stage) {
         this.stage = stage == null ? Component.empty() : stage;
         return this;
      }

      public Builder stageDisplay(GeometryStageDisplay stageDisplay) {
         this.stageDisplay = stageDisplay == null ? GeometryStageDisplay.empty() : stageDisplay;
         return this;
      }

      public Builder gizmo(AxisGizmo gizmo) {
         this.gizmo = gizmo;
         return this;
      }

      public Builder ghost(Set<BlockPos> ghostBlocks) {
         this.ghostBlocks = ghostBlocks == null ? Set.of() : Set.copyOf(ghostBlocks);
         return this;
      }

      public Builder blocks(GeometryPreviewBlocks.Layers layers) {
         GeometryPreviewBlocks.Layers value = layers == null
            ? GeometryPreviewBlocks.Layers.confirmed(Set.of())
            : layers;
         this.ghostBlocks = value.confirmed();
         this.pendingBlocks = value.pending();
         return this;
      }

      public Builder candidate(BlockPos candidateBlock) {
         if (candidateBlock != null && !this.points.contains(candidateBlock) && !this.pendingBlocks.contains(candidateBlock)) {
            if (this.ghostBlocks.contains(candidateBlock)) {
               java.util.HashSet<BlockPos> confirmed = new java.util.HashSet<>(this.ghostBlocks);
               confirmed.remove(candidateBlock);
               this.ghostBlocks = Set.copyOf(confirmed);
            }
            java.util.HashSet<BlockPos> pending = new java.util.HashSet<>(this.pendingBlocks);
            pending.add(candidateBlock.immutable());
            this.pendingBlocks = Set.copyOf(pending);
         }
         return this;
      }

      public Builder guides(List<GuideLine> lines, List<GuidePlane> planes) {
         this.guideLines = lines == null ? List.of() : List.copyOf(lines);
         this.guidePlanes = planes == null ? List.of() : List.copyOf(planes);
         return this;
      }

      public Builder interactionTargets(List<GeometryInteractionTarget> interactionTargets) {
         this.interactionTargets = interactionTargets == null ? List.of() : List.copyOf(interactionTargets);
         return this;
      }

      public Builder textBlocks(List<GeometryTextBlock> textBlocks) {
         this.textBlocks = textBlocks == null ? List.of() : List.copyOf(textBlocks);
         return this;
      }

      public Builder textBlock(GeometryTextBlock block) {
         if (block == null) {
            return this;
         }
         ArrayList<GeometryTextBlock> next = new ArrayList<>(this.textBlocks);
         next.removeIf(existing -> existing.id().equals(block.id()));
         next.add(block);
         this.textBlocks = List.copyOf(next);
         return this;
      }

      public Builder controlPoints(List<ControlPoint> controlPoints) {
         this.controlPoints = controlPoints == null ? null : List.copyOf(controlPoints);
         return this;
      }

      public GeometryPreviewPlan build() {
         return new GeometryPreviewPlan(
            this.controlPoints(),
            this.stage,
            this.variant,
            this.hint,
            this.stageDisplay,
            this.gizmo,
            this.ghostBlocks,
            this.pendingBlocks,
            this.guideLines,
            this.guidePlanes,
            this.interactionTargets,
            this.resolvedTextBlocks()
         );
      }

      private List<GeometryTextBlock> resolvedTextBlocks() {
         ArrayList<GeometryTextBlock> result = new ArrayList<>(this.textBlocks);
         GeometryTextBlock status = findTextBlock(result, GeometryTextBlock.STATUS_ID);
         if (status != null && !GeometryTextBlock.hasContent(status.content())) {
            replaceTextBlock(result, new GeometryTextBlock(
               status.id(),
               status.placement(),
               GeometryStatusText.composeStageBar(
                  this.stage,
                  this.stageDisplay,
                  this.variant,
                  isTextVisible(result, GeometryTextBlock.STAGE_ID),
                  isTextVisible(result, GeometryTextBlock.MODE_ID),
                  isTextVisible(result, GeometryTextBlock.VALUE_ID)
               ),
               status.visible()
            ));
         }

         GeometryTextBlock hint = findTextBlock(result, GeometryTextBlock.HINT_ID);
         if (hint != null && !GeometryTextBlock.hasContent(hint.content())) {
            replaceTextBlock(result, new GeometryTextBlock(
               hint.id(),
               hint.placement(),
               this.hint,
               hint.visible()
            ));
         }
         return List.copyOf(result);
      }

      private static GeometryTextBlock findTextBlock(List<GeometryTextBlock> blocks, String id) {
         return blocks.stream()
            .filter(block -> id.equals(block.id()))
            .findFirst()
            .orElse(null);
      }

      private static boolean isTextVisible(List<GeometryTextBlock> blocks, String id) {
         GeometryTextBlock block = findTextBlock(blocks, id);
         return block == null || block.visible();
      }

      private static void replaceTextBlock(List<GeometryTextBlock> blocks, GeometryTextBlock replacement) {
         for (int index = 0; index < blocks.size(); index++) {
            if (blocks.get(index).id().equals(replacement.id())) {
               blocks.set(index, replacement);
               return;
            }
         }
      }

      private List<ControlPoint> controlPoints() {
         if (this.controlPoints != null) {
            return this.controlPoints;
         }
         if (this.points.isEmpty()) {
            return List.of();
          }
          ArrayList<ControlPoint> result = new ArrayList<>(this.points.size());
          BlockPos first = this.points.getFirst();
          result.add(ControlPoint.of(first, ControlPointRole.PRIMARY));
          for (int i = 1; i < this.points.size(); i++) {
            BlockPos point = this.points.get(i);
            result.add(ControlPoint.secondary(point));
          }
         return result;
      }
   }
}
