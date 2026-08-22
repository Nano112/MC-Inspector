package dev.harrison.mcinspector.mixin;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Re-deliver a held/rewritten inbound packet as if it had just arrived (channelRead0 ignores the ctx). */
@Mixin(Connection.class)
public interface ConnectionInvoker {
    @Invoker("channelRead0")
    void mcinspector$channelRead0(ChannelHandlerContext ctx, Packet<?> packet);
}
