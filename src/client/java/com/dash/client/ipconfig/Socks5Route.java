package com.dash.client.ipconfig;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import io.netty.handler.proxy.Socks5ProxyHandler;

import java.util.function.Supplier;

public final class Socks5Route {
    private static final int CONNECT_TIMEOUT_MS = 8_000;

    private Socks5Route() {}

    static boolean usesAuthentication(String username, String password) {
        boolean hasUsername = username != null && !username.isEmpty();
        boolean hasPassword = password != null && !password.isEmpty();
        if (hasUsername != hasPassword) {
            throw new IllegalArgumentException("Enter both Login and Password, or leave both blank.");
        }
        if (hasUsername && (!username.matches("[ -~]{1,255}") || !password.matches("[ -~]{1,255}"))) {
            throw new IllegalArgumentException("Login and password must be 1 to 255 ASCII characters.");
        }
        return hasUsername;
    }

    public static ChannelFuture connect(Bootstrap bootstrap, String endpoint, String username, String password,
                                        Supplier<ChannelFuture> direct) {
        if (endpoint == null) {
            return direct.get();
        }

        var proxyAddress = ProxyEndpoint.parse(endpoint).socketAddress();
        Socks5ProxyHandler proxy = usesAuthentication(username, password)
                ? new Socks5ProxyHandler(proxyAddress, username, password)
                : new Socks5ProxyHandler(proxyAddress);
        proxy.setConnectTimeoutMillis(CONNECT_TIMEOUT_MS);
        ChannelHandler minecraft = bootstrap.config().handler();
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS);
        bootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel channel) {
                channel.pipeline().addLast("dash_socks5", proxy);
                channel.pipeline().addLast(minecraft);
            }
        });

        ChannelFuture tcp = direct.get();
        ChannelPromise ready = tcp.channel().newPromise();
        ready.addListener(result -> {
            if (result.isCancelled()) {
                tcp.cancel(false);
                tcp.channel().close();
            }
        });
        tcp.addListener(result -> {
            if (result.isCancelled()) {
                ready.cancel(false);
            } else if (!result.isSuccess()) {
                ready.tryFailure(result.cause());
            } else {
                proxy.connectFuture().addListener(tunnel -> {
                    if (tunnel.isSuccess()) {
                        ready.trySuccess();
                    } else if (tunnel.isCancelled()) {
                        ready.cancel(false);
                    } else {
                        ready.tryFailure(tunnel.cause());
                    }
                });
            }
        });
        return ready;
    }
}
