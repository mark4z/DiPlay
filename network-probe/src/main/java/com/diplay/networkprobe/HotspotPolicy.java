package com.diplay.networkprobe;

import java.net.InetAddress;

/** Conservative hotspot candidates; the user must still confirm the peer's actual gateway. */
final class HotspotPolicy {
    private HotspotPolicy() {}

    static boolean isPrivateIpv4(byte[] bytes) {
        if (bytes == null || bytes.length != 4) return false;
        int a = bytes[0] & 255, b = bytes[1] & 255;
        return a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168);
    }

    static boolean isWifiApName(String name) {
        return name != null && name.matches("(?:wlan|swlan|ap|softap|wifi)[0-9]+");
    }

    static boolean eligible(String name, byte[] address, int prefix, boolean up,
                            boolean loopback, boolean pointToPoint, boolean virtual,
                            boolean broadcast, boolean androidManaged) {
        return isWifiApName(name) && isHostAddress(address, prefix)
                && up && !loopback && !pointToPoint && !virtual && broadcast && !androidManaged;
    }

    /** Excludes unusable network/broadcast addresses even when an OEM reports one on an interface. */
    private static boolean isHostAddress(byte[] address, int prefix) {
        if (!isPrivateIpv4(address) || prefix < 1 || prefix > 30) return false;
        long value = 0;
        for (byte part : address) value = (value << 8) | (part & 255);
        long hostMask = (1L << (32 - prefix)) - 1;
        long host = value & hostMask;
        return host != 0 && host != hostMask;
    }

    static String healthUrl(InetAddress address, int port) {
        ProbePolicy.requireTestPort(port);
        if (address == null || !isPrivateIpv4(address.getAddress()))
            throw new IllegalArgumentException("NOT_PRIVATE_IPV4");
        return (port == ProbePolicy.HTTPS_PORT ? "https://" + ProbePolicy.HOTSPOT_HOSTNAME
                : "http://" + address.getHostAddress()) + ":" + port + "/health";
    }
}
