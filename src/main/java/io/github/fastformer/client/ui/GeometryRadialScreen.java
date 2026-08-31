package io.github.fastformer.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.fastformer.fastplace.GeometryMode;
import io.github.fastformer.network.payload.geometry.GeometrySelectModePayload;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

public final class GeometryRadialScreen extends Screen {
   private static final int OUTER_RADIUS = 92;
   private static final int INNER_RADIUS = 30;
   private static final int ICON_DISTANCE = 61;
   private static final int ICON_SIZE = 22;
   private static final int FRAME_SIZE = 192;
   private static final ResourceLocation FRAME_TEXTURE = ResourceLocation.fromNamespaceAndPath(
      "fastformer", "textures/gui/geometry_wheel_frame.png"
   );
   private static final List<Slice> SLICES = List.of(
      new Slice(GeometryMode.WALL, null, Sector.TOP, 0xFF4FA3FF),
      new Slice(GeometryMode.CONE_PRISM, null, Sector.RIGHT, 0xFFFFA640),
      new Slice(null, "fastformer.geometry.special.complex", Sector.BOTTOM, 0xFFFF668A),
      new Slice(GeometryMode.POLYHEDRON, null, Sector.LEFT, 0xFF55C97A)
   );
   private static final List<Slice> COMPLEX_SLICES = List.of(
      new Slice(GeometryMode.CONVEX_POLYHEDRON, null, Sector.TOP, 0xFFFFD24A)
   );

   private Slice selected;
   private Page page = Page.ROOT;
   private boolean centerHovered;

   public GeometryRadialScreen() {
      super(Component.translatable("fastformer.geometry.radial.title"));
   }

   @Override
   public boolean isPauseScreen() {
      return false;
   }

   @Override
   public void tick() {
      Minecraft minecraft = Minecraft.getInstance();
      long window = minecraft.getWindow().getWindow();
      if (!radialChordHeld(window)) {
         this.finish(this.selected != null);
      }
   }

   @Override
   public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
   }

   @Override
   public void mouseMoved(double mouseX, double mouseY) {
      this.updateSelection(mouseX, mouseY);
   }

   @Override
   public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
      this.updateSelection(mouseX, mouseY);
      return true;
   }

   @Override
   public boolean mouseClicked(double mouseX, double mouseY, int button) {
      Page previousPage = this.page;
      this.updateSelection(mouseX, mouseY);
      if (this.page == Page.COMPLEX && this.centerHovered) {
         this.page = Page.ROOT;
         this.selected = null;
         return true;
      }
      if (previousPage == Page.ROOT && this.page == Page.COMPLEX) {
         return true;
      }
      if (button == 0) {
         this.finish(this.selected != null);
         return true;
      }
      if (button == 1) {
         return true;
      }
      return super.mouseClicked(mouseX, mouseY, button);
   }

   @Override
   public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
      if (keyCode == 341 || keyCode == 345 || keyCode == 342 || keyCode == 346) {
         this.finish(this.selected != null);
         return true;
      }
      return super.keyReleased(keyCode, scanCode, modifiers);
   }

   @Override
   public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
      if (keyCode == 256) {
         this.finish(false);
         return true;
      }
      return super.keyPressed(keyCode, scanCode, modifiers);
   }

   @Override
   public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      this.updateSelection(mouseX, mouseY);
      int centerX = this.width / 2;
      int centerY = this.height / 2;
      graphics.fill(0, 0, this.width, this.height, 0x28000000);
      if (this.page == Page.COMPLEX) {
         this.renderComplexPage(graphics, centerX, centerY);
         return;
      }
      fillCircle(graphics, centerX, centerY, OUTER_RADIUS, 0xD8242A35);
      if (this.selected != null) {
         fillWedge(graphics, centerX, centerY, OUTER_RADIUS, this.selected.sector(), withAlpha(this.selected.color(), 0xB8));
      }
      fillCircle(graphics, centerX, centerY, INNER_RADIUS, 0xF0181D25);
      blitWheelFrame(graphics, centerX, centerY);

      for (Slice slice : SLICES) {
         int iconX = centerX + slice.sector().x() * ICON_DISTANCE;
         int iconY = centerY + slice.sector().y() * ICON_DISTANCE;
         renderModeIcon(graphics, slice, iconX, iconY, slice == this.selected);
      }

      Component centerLabel = this.selected == null
         ? Component.translatable("fastformer.geometry.radial.title")
         : Component.translatable(this.selected.translationKey());
      graphics.drawCenteredString(this.font, centerLabel, centerX, centerY - 4, 0xFFFFFFFF);
      graphics.drawCenteredString(
         this.font, Component.translatable("fastformer.geometry.radial.hint"), centerX, centerY + OUTER_RADIUS + 14, 0xFFD5DEE8
      );
   }

   private void updateSelection(double mouseX, double mouseY) {
      double dx = mouseX - this.width * 0.5;
      double dy = mouseY - this.height * 0.5;
      this.centerHovered = false;
      if (dx * dx + dy * dy < (double)INNER_RADIUS * INNER_RADIUS) {
         this.selected = null;
         this.centerHovered = this.page == Page.COMPLEX;
         return;
      }
      if (this.page == Page.COMPLEX) {
         Sector sector = sector(dx, dy);
         this.selected = COMPLEX_SLICES.stream().filter(slice -> slice.sector() == sector).findFirst().orElse(null);
         return;
      }
      Sector sector = sector(dx, dy);
      this.selected = SLICES.stream().filter(slice -> slice.sector() == sector).findFirst().orElse(null);
      if (this.selected != null && this.selected.special()) {
         this.page = Page.COMPLEX;
         this.selected = null;
      }
   }

   private void renderComplexPage(GuiGraphics graphics, int centerX, int centerY) {
      fillCircle(graphics, centerX, centerY, OUTER_RADIUS, 0xE0202530);
      if (this.selected != null) {
         fillWedge(graphics, centerX, centerY, OUTER_RADIUS, this.selected.sector(), withAlpha(this.selected.color(), 0xB8));
      }
      fillCircle(graphics, centerX, centerY, INNER_RADIUS, this.centerHovered ? 0xF06A7688 : 0xF0181D25);
      blitWheelFrame(graphics, centerX, centerY);
      for (Slice slice : COMPLEX_SLICES) {
         int iconX = centerX + slice.sector().x() * ICON_DISTANCE;
         int iconY = centerY + slice.sector().y() * ICON_DISTANCE;
         renderModeIcon(graphics, slice, iconX, iconY, slice == this.selected);
      }
      Component label = this.centerHovered
         ? Component.translatable("fastformer.geometry.special.back")
         : this.selected == null
            ? Component.translatable("fastformer.geometry.special.complex")
         : Component.translatable(this.selected.translationKey());
      graphics.drawCenteredString(
         this.font, label, centerX, centerY - 4, 0xFFFFFFFF
      );
      graphics.drawCenteredString(
         this.font, Component.translatable("fastformer.geometry.special.hint"), centerX, centerY + OUTER_RADIUS + 14, 0xFFD5DEE8
      );
   }

   private void finish(boolean select) {
      Minecraft minecraft = Minecraft.getInstance();
      if (select
         && this.selected != null
         && this.selected.mode() != null
         && minecraft.getConnection() != null
         && NetworkRegistry.hasChannel(minecraft.getConnection(), GeometrySelectModePayload.TYPE.id())) {
         PacketDistributor.sendToServer(new GeometrySelectModePayload(this.selected.mode()), new CustomPacketPayload[0]);
      }
      minecraft.setScreen(null);
   }

   private static boolean radialChordHeld(long window) {
      boolean ctrlHeld = InputConstants.isKeyDown(window, 341) || InputConstants.isKeyDown(window, 345);
      boolean altHeld = InputConstants.isKeyDown(window, 342) || InputConstants.isKeyDown(window, 346);
      return ctrlHeld && altHeld;
   }

   private static Sector sector(double dx, double dy) {
      if (Math.abs(dx) > Math.abs(dy)) {
         return dx >= 0.0 ? Sector.RIGHT : Sector.LEFT;
      }
      return dy >= 0.0 ? Sector.BOTTOM : Sector.TOP;
   }

   private static void renderModeIcon(GuiGraphics graphics, Slice slice, int centerX, int centerY, boolean selected) {
      int half = ICON_SIZE / 2;
      int border = selected ? 0xFFFFFFFF : 0xFF7F8A98;
      graphics.fill(centerX - half - 2, centerY - half - 2, centerX + half + 2, centerY + half + 2, border);
      graphics.fill(centerX - half, centerY - half, centerX + half, centerY + half, slice.color());
   }

   private static void fillCircle(GuiGraphics graphics, int centerX, int centerY, int radius, int color) {
      int radiusSqr = radius * radius;
      for (int y = -radius; y <= radius; y++) {
         int halfWidth = (int)Math.floor(Math.sqrt(radiusSqr - y * y));
         graphics.fill(centerX - halfWidth, centerY + y, centerX + halfWidth + 1, centerY + y + 1, color);
      }
   }

   private static void fillWedge(GuiGraphics graphics, int centerX, int centerY, int radius, Sector sector, int color) {
      int radiusSqr = radius * radius;
      for (int y = -radius; y <= radius; y++) {
         int halfWidth = (int)Math.floor(Math.sqrt(radiusSqr - y * y));
         int diagonal = Math.abs(y);
         switch (sector) {
            case TOP -> {
               if (y < 0) {
                  int width = Math.min(halfWidth, -y);
                  graphics.fill(centerX - width, centerY + y, centerX + width + 1, centerY + y + 1, color);
               }
            }
            case BOTTOM -> {
               if (y > 0) {
                  int width = Math.min(halfWidth, y);
                  graphics.fill(centerX - width, centerY + y, centerX + width + 1, centerY + y + 1, color);
               }
            }
            case RIGHT -> {
               if (diagonal <= halfWidth) {
                  graphics.fill(centerX + diagonal, centerY + y, centerX + halfWidth + 1, centerY + y + 1, color);
               }
            }
            case LEFT -> {
               if (diagonal <= halfWidth) {
                  graphics.fill(centerX - halfWidth, centerY + y, centerX - diagonal + 1, centerY + y + 1, color);
               }
            }
         }
      }
   }

   private static void blitWheelFrame(GuiGraphics graphics, int centerX, int centerY) {
      graphics.blit(
         FRAME_TEXTURE,
         centerX - FRAME_SIZE / 2,
         centerY - FRAME_SIZE / 2,
         0.0F,
         0.0F,
         FRAME_SIZE,
         FRAME_SIZE,
         FRAME_SIZE,
         FRAME_SIZE
      );
   }

   private static int withAlpha(int color, int alpha) {
      return alpha << 24 | color & 0x00FFFFFF;
   }

   private record Slice(GeometryMode mode, String specialTranslationKey, Sector sector, int color) {
      boolean special() {
         return this.mode == null;
      }

      String translationKey() {
         return this.special() ? this.specialTranslationKey : this.mode.translationKey();
      }
   }

   private enum Page {
      ROOT,
      COMPLEX
   }

   private enum Sector {
      TOP(0, -1),
      RIGHT(1, 0),
      BOTTOM(0, 1),
      LEFT(-1, 0);

      private final int x;
      private final int y;

      Sector(int x, int y) {
         this.x = x;
         this.y = y;
      }

      int x() {
         return this.x;
      }

      int y() {
         return this.y;
      }
   }
}
