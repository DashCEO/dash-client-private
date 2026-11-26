package com.dash.client.ipconfig;

import io.netty.util.NetUtil;

import java.net.InetAddress;
import java.net.InetSocketAddress;

record ProxyEndpoint(String host, int port) {
    private static final int DEFAULT_PORT = 1080;

    static ProxyEndpoint parse(String input) {
        String address = input.strip();
        if (address.isEmpty()) {
            throw new IllegalArgumentException("Enter a SOCKS5 proxy address.");
        }

        String host;
        String port = null;
        if (address.startsWith("[")) {
            int close = address.indexOf(']');
            if (close == -1) {
                throw invalidAddress();
            }
            host = address.substring(1, close);
            if (!NetUtil.isValidIpV6Address(host)) {
                throw invalidAddress();
            }
            if (close + 1 < address.length()) {
                if (address.charAt(close + 1) != ':') {
                    throw invalidAddress();
                }
                port = address.substring(close + 2);
            }
        } else {
            int colon = address.indexOf(':');
            if (colon == -1) {
                host = address;
            } else if (colon == address.lastIndexOf(':')) {
                host = address.substring(0, colon);
                port = address.substring(colon + 1);
            } else {
                host = address;
                if (!NetUtil.isValidIpV6Address(host)) {
                    throw invalidAddress();
                }
            }
        }

        if (host.indexOf(':') == -1 && !NetUtil.isValidIpV4Address(host)) {
            throw invalidAddress();
        }

        int number = DEFAULT_PORT;
        if (port != null) {
            if (!port.matches("[0-9]{1,5}")) {
                throw invalidPort();
            }
            number = Integer.parseInt(port);
            if (number < 1 || number > 65535) {
                throw invalidPort();
            }
        }
        return new ProxyEndpoint(host, number);
    }

    InetSocketAddress socketAddress() {
        InetAddress address = NetUtil.createInetAddressFromIpAddressString(host);
        if (address == null) {
            throw invalidAddress();
        }
        return new InetSocketAddress(address, port);
    }

    String address() {
        String formatted = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return formatted + ":" + port;
    }

    private static IllegalArgumentException invalidAddress() {
        return new IllegalArgumentException("Enter an IPv4 address or [IPv6]:port.");
    }

    private static IllegalArgumentException invalidPort() {
        return new IllegalArgumentException("The proxy port must be 1 to 65535.");
    }
}
