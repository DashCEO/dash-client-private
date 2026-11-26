package com.dash.client.ipconfig.mixin;

import com.dash.client.ipconfig.ProxySettings;
import com.dash.client.ipconfig.Socks5Route;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.net.InetAddress;

@Mixin(Connection.class)
abstract class ConnectionMixin {
    @WrapOperation(method = "connect(Ljava/net/InetSocketAddress;Lnet/minecraft/server/network/EventLoopGroupHolder;"
            + "Lnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;",
            at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;connect(Ljava/net/InetAddress;I)"
                    + "Lio/netty/channel/ChannelFuture;"))
    private static ChannelFuture dash$routeConnection(Bootstrap bootstrap, InetAddress address, int port,
                                                       Operation<ChannelFuture> original) {
        var route = ProxySettings.route();
        if (route.needsCredentials()) {
            throw new IllegalStateException("Enter the SOCKS5 login and password in IP Config first.");
        }
        return Socks5Route.connect(bootstrap, route.address(), route.username(), route.password(),
                () -> original.call(bootstrap, address, port));
    }
}
