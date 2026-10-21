package com.moneylog.core.quickentry.domain;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

public final class IpMask {

    private static final Pattern IPV4 = Pattern.compile("(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)");
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9a-fA-F:.]+");

    private IpMask() {
    }

    public static String mask(String ip) {
        if (ip == null) {
            return null;
        }
        String value = ip.strip();
        if (IPV4.matcher(value).matches()) {
            return maskIpv4(value.split("\\."));
        }
        if (value.indexOf(':') < 0 || !IPV6_LITERAL.matcher(value).matches()) {
            return null;
        }
        try {
            InetAddress address = InetAddress.getByName(value);
            byte[] bytes = address.getAddress();
            if (address instanceof Inet4Address) {
                return maskIpv4(new String[] {String.valueOf(bytes[0] & 0xff), String.valueOf(bytes[1] & 0xff), String.valueOf(bytes[2] & 0xff), "0"});
            }
            StringBuilder out = new StringBuilder();
            for (int group = 0; group < 8; group++) {
                int hextet = group < 3 ? ((bytes[group * 2] & 0xff) << 8) | (bytes[group * 2 + 1] & 0xff) : 0;
                out.append(group == 0 ? "" : ":").append(Integer.toHexString(hextet));
            }
            return out.toString();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static String maskIpv4(String[] octets) {
        return Integer.parseInt(octets[0]) + "." + Integer.parseInt(octets[1]) + "." + Integer.parseInt(octets[2]) + ".0";
    }
}
