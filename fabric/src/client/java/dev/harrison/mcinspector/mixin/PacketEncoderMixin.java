package dev.harrison.mcinspector.mixin;

import dev.harrison.mcinspector.packets.PacketSizes;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Outbound wire size = encoder output length. Same signature on 1.21.8–26.2. */
@Mixin(PacketEncoder.class)
public abstract class PacketEncoderMixin {
    @Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V", at = @At("RETURN"))
    private void mcinspector$encoded(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf buf, CallbackInfo ci) {
        PacketSizes.onEncoded(packet, buf.readableBytes());
    }
}
