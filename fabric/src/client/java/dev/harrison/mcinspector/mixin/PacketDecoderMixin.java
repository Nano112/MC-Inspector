package dev.harrison.mcinspector.mixin;

import dev.harrison.mcinspector.packets.PacketSizes;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketDecoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Inbound wire size = decoder input length (readable bytes at HEAD), attached to the decoded packet at RETURN. */
@Mixin(PacketDecoder.class)
public abstract class PacketDecoderMixin {
    private static final String DECODE = "decode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Ljava/util/List;)V";

    @Inject(method = DECODE, at = @At("HEAD"))
    private void mcinspector$decodeStart(ChannelHandlerContext ctx, ByteBuf buf, List<Object> out, CallbackInfo ci) {
        PacketSizes.onDecodeStart(buf);
    }

    @Inject(method = DECODE, at = @At("RETURN"))
    private void mcinspector$decoded(ChannelHandlerContext ctx, ByteBuf buf, List<Object> out, CallbackInfo ci) {
        PacketSizes.onDecoded(out.isEmpty() ? null : out.get(out.size() - 1));
    }
}
