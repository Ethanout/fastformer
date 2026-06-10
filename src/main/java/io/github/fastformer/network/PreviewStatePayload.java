package io.github.fastformer.network;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.FaceMode;
import io.github.fastformer.fastplace.FastPlaceSettings;
import io.github.fastformer.fastplace.FillMode;
import io.github.fastformer.fastplace.LineMode;
import io.github.fastformer.fastplace.VolumeMode;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record PreviewStatePayload(
        boolean active,
        List<BlockPos> points,
        LineMode lineMode,
        FaceMode faceMode,
        VolumeMode volumeMode,
        FillMode fillMode) implements CustomPacketPayload {
    public static final Type<PreviewStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(FastFormer.MOD_ID, "preview_state"));
    public static final StreamCodec<FriendlyByteBuf, PreviewStatePayload> STREAM_CODEC =
            CustomPacketPayload.codec(PreviewStatePayload::write, PreviewStatePayload::new);

    public PreviewStatePayload {
        points = List.copyOf(points);
    }

    public static PreviewStatePayload active(List<BlockPos> points, FastPlaceSettings settings) {
        return new PreviewStatePayload(
                true,
                points,
                settings.lineMode(),
                settings.faceMode(),
                settings.volumeMode(),
                settings.fillMode());
    }

    public static PreviewStatePayload inactive() {
        return new PreviewStatePayload(
                false,
                List.of(),
                LineMode.STRAIGHT,
                FaceMode.RECTANGLE,
                VolumeMode.EXTRUDE,
                FillMode.WIREFRAME);
    }

    private PreviewStatePayload(FriendlyByteBuf buffer) {
        this(
                buffer.readBoolean(),
                buffer.readList(readBuffer -> readBuffer.readBlockPos()),
                buffer.readEnum(LineMode.class),
                buffer.readEnum(FaceMode.class),
                buffer.readEnum(VolumeMode.class),
                buffer.readEnum(FillMode.class));
    }

    private void write(FriendlyByteBuf buffer) {
        buffer.writeBoolean(active);
        buffer.writeCollection(points, (writeBuffer, point) -> writeBuffer.writeBlockPos(point));
        buffer.writeEnum(lineMode);
        buffer.writeEnum(faceMode);
        buffer.writeEnum(volumeMode);
        buffer.writeEnum(fillMode);
    }

    @Override
    public Type<PreviewStatePayload> type() {
        return TYPE;
    }
}
