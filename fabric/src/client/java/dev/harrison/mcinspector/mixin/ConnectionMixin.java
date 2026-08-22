package dev.harrison.mcinspector.mixin;

import dev.harrison.mcinspector.packets.PacketCapture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Packet capture + rules at the Connection level: every inbound packet passes {@code channelRead0} and
 * every outbound packet passes {@code doSendPacket}, for network AND in-memory (singleplayer) pipelines.
 * Rules may drop, hold or replace a packet, in which case the original call is cancelled.
 * Both signatures are identical on 1.21.8 through 26.2.
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void mcinspector$inbound(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        if (PacketCapture.onInbound((Connection) (Object) this, packet)) ci.cancel();
    }

    @Inject(method = "doSendPacket", at = @At("HEAD"), cancellable = true)
    private void mcinspector$outbound(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        if (PacketCapture.onOutbound((Connection) (Object) this, packet, listener, flush)) ci.cancel();
    }
}
