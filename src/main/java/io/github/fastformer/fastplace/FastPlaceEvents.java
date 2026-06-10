package io.github.fastformer.fastplace;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingSwapItemsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

public final class FastPlaceEvents {
    private static final double FAST_PLACE_REACH = 128.0;

    private FastPlaceEvents() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(FastPlaceEvents::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(FastPlaceEvents::onRightClickItem);
        NeoForge.EVENT_BUS.addListener(FastPlaceEvents::onLeftClickBlock);
        NeoForge.EVENT_BUS.addListener(FastPlaceEvents::onItemToss);
        NeoForge.EVENT_BUS.addListener(FastPlaceEvents::onSwapHands);
        NeoForge.EVENT_BUS.addListener(FastPlaceEvents::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(FastPlaceEvents::onPlayerClone);
        NeoForge.EVENT_BUS.addListener(FastPlaceEvents::onPlayerLogout);
        NeoForge.EVENT_BUS.addListener(FastPlaceEvents::onRegisterCommands);
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        if (!FastPlaceManager.active(player) || !canOperate(player)) {
            return;
        }
        if (!(player.getMainHandItem().getItem() instanceof BlockItem)) {
            FastPlaceManager.quit(player);
            return;
        }

        BlockHitResult hit = event.getHitVec();
        FastPlaceManager.addPoint(player, hit.getBlockPos().relative(hit.getDirection()));
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    private static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        if (!canOperate(player) || !(player.getMainHandItem().getItem() instanceof BlockItem)) {
            return;
        }

        BlockHitResult hit = raycastBlocks(player, FAST_PLACE_REACH);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return;
        }

        double normalReach = player.blockInteractionRange();
        boolean farEnoughToStart = hit.getLocation().distanceToSqr(player.getEyePosition()) > normalReach * normalReach;
        if (!FastPlaceManager.active(player) && !farEnoughToStart) {
            return;
        }

        FastPlaceManager.addPoint(player, hit.getBlockPos().relative(hit.getDirection()));
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    private static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START
                || !FastPlaceManager.active(player)) {
            return;
        }
        FastPlaceManager.undo(player);
        event.setCanceled(true);
    }

    private static void onItemToss(ItemTossEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player) || !FastPlaceManager.active(player)) {
            return;
        }
        ItemStack tossed = event.getEntity().getItem().copy();
        event.setCanceled(true);
        FastPlaceManager.restoreTossedItem(player, tossed);
        FastPlaceManager.quit(player);
    }

    private static void onSwapHands(LivingSwapItemsEvent.Hands event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !FastPlaceManager.active(player)) {
            return;
        }

        ItemStack mainHand = player.getMainHandItem();
        ItemStack offHand = player.getOffhandItem();
        if (mainHand.isEmpty() && !offHand.isEmpty()) {
            return;
        }

        event.setCanceled(true);
        FastPlaceManager.cycleFill(player);
    }

    private static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        if (!canOperate(player)) {
            FastPlaceManager.cancel(player);
        }
    }

    private static void onPlayerClone(PlayerEvent.Clone event) {
        if (event.getOriginal() instanceof ServerPlayer oldPlayer && event.getEntity() instanceof ServerPlayer newPlayer) {
            FastPlaceSettings.copy(oldPlayer, newPlayer);
        }
    }

    private static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            FastPlaceManager.remove(player);
        }
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("fastformer")
                .then(Commands.literal("toggle").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    FastPlaceSettings settings = FastPlaceSettings.load(player);
                    settings.toggleEnabled(player);
                    FastPlaceManager.cancel(player);
                    FastPlaceMessages.chat(player, "FastFormer: " + (settings.enabled() ? "enabled" : "disabled"));
                    return 1;
                }))
                .then(Commands.literal("cancel").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    FastPlaceManager.quit(player);
                    return 1;
                }))
                .then(Commands.literal("mode").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    if (canOperate(player) && FastPlaceManager.active(player)) {
                        FastPlaceManager.cycleStageMode(player);
                    }
                    return 1;
                }))
                .then(Commands.literal("status").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    FastPlaceSettings settings = FastPlaceSettings.load(player);
                    FastPlaceMessages.chat(player, "FastFormer: "
                            + (settings.enabled() ? "enabled" : "disabled")
                            + ", fill "
                            + settings.fillMode().displayName());
                    return 1;
                })));
    }

    private static boolean canOperate(ServerPlayer player) {
        return player.isCreative() && FastPlaceSettings.load(player).enabled();
    }

    private static BlockHitResult raycastBlocks(Player player, double range) {
        Level level = player.level();
        Vec3 start = player.getEyePosition();
        Vec3 end = start.add(player.getViewVector(1.0F).scale(range));
        ClipContext context = new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player);
        return level.clip(context);
    }
}
