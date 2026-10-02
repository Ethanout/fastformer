package io.github.fastformer.client.render;

/** Dimensions and contrast levels for the geometry overlay. */
public final class PreviewStyle {
   public static final double LINE_WIDTH = 1.5;
   public static final double GIZMO_LINE_WIDTH = 2.0;
   public static final double GIZMO_HOVER_WIDTH = 2.5;
   public static final double DASH_LENGTH = 0.25;
   public static final double DASH_PERIOD = DASH_LENGTH * 2.0;
   public static final float DASH_GAP_ALPHA = 0.18F;
   public static final double DASH_SPEED = 0.50;
   public static final float FACE_ALPHA = 0.14F;
   public static final float FACE_HOVER_ALPHA = 0.28F;
   public static final float OUTLINE_ALPHA = 0.92F;
   /** Every mark behind a surface, whatever its kind. */
   public static final float OCCLUDED_ALPHA = 0.30F;
   /** The white halo is this much wider than the pencil line it sits under. */
   public static final double HALO_EXTRA_WIDTH = 1.25;
   /** Halo opacity relative to its pencil line. */
   public static final float HALO_ALPHA = 0.35F;
   /** One offset for every box drawn around a block, so outlines never stack at different sizes. */
   public static final double OUTLINE_INFLATE = 0.01;
   public static final float LABEL_SCALE = 0.025F;
   public static final int HUD_MARGIN = 8;
   public static final int HUD_LINE_HEIGHT = 12;

   private PreviewStyle() { }
}
