package com.moneylog.core.notification.domain;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

public final class IpMask {

    public static final String UNKNOWN = "unknown";

    private IpMask() {
    }

    public static String mask(String ip) {
        if (ip == null || ip.isBlank() || ip.length() > 45 || !ip.matches("[0-9a-fA-F:.]+")) {
            return UNKNOWN;
        }
        InetAddress address;
        try {
            address = InetAddress.getByName(ip.strip());
        } catch (java.net.UnknownHostException | RuntimeException e) {
            return UNKNOWN;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            return (bytes[0] & 0xff) + "." + (bytes[1] & 0xff) + ".x.x";
        }
        if (address instanceof Inet6Address) {
            return Integer.toHexString(((bytes[0] & 0xff) << 8) | (bytes[1] & 0xff)) + ":"
                    + Integer.toHexString(((bytes[2] & 0xff) << 8) | (bytes[3] & 0xff)) + "::";
        }
        return UNKNOWN;
    }
}
