package net.server.coordinator.session;

import config.YamlConfig;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class IpAddresses {
    /**
     * A whole dotted-quad address. 1.5.0: the old LAN patterns ("10\\.", "192\\.168\\.", "172\\.(1[6-9]|2[0-9]|3[0-1])\\.")
     * were matched with an unanchored find(), so public addresses such as 73.110.5.2 or 98.10.1.1 counted as LAN, were
     * rewritten to HOST and shared one login-limiter / auto-register / IP-ban bucket.
     */
    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    private static final String[] IPV4_MAPPED_PREFIXES = {"::ffff:", "0:0:0:0:0:ffff:"};
    private static final String[] IPV6_LOOPBACK = {"::1", "0:0:0:0:0:0:0:1"};

    public static String evaluateRemoteAddress(String inetAddress) {
        if (isLocalAddress(inetAddress) || isLanAddress(inetAddress)) {
            return YamlConfig.config.server.HOST;
        } else {
            return inetAddress;
        }
    }

    /** Loopback: 127.0.0.0/8, ::1, or an IPv4-mapped 127.x address. */
    public static boolean isLocalAddress(String inetAddress) {
        String address = normalize(inetAddress);
        if (address == null) {
            return false;
        }
        for (String loopback : IPV6_LOOPBACK) {
            if (address.equals(loopback)) {
                return true;
            }
        }
        int[] octets = ipv4(address);
        return octets != null && octets[0] == 127;
    }

    /** Private IPv4 (RFC 1918): 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, also as IPv4-mapped IPv6. */
    public static boolean isLanAddress(String inetAddress) {
        int[] octets = ipv4(normalize(inetAddress));
        if (octets == null) {
            return false;
        }
        return octets[0] == 10
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168);
    }

    /** Lower case, without an IPv6 zone id or IPv4-mapped prefix. */
    private static String normalize(String inetAddress) {
        if (inetAddress == null) {
            return null;
        }
        String address = inetAddress.trim().toLowerCase(Locale.ROOT);
        int zone = address.indexOf('%');
        if (zone >= 0) {
            address = address.substring(0, zone);
        }
        if (address.startsWith("[") && address.endsWith("]")) {
            address = address.substring(1, address.length() - 1);
        }
        for (String prefix : IPV4_MAPPED_PREFIXES) {
            if (address.startsWith(prefix) && address.indexOf('.') > 0) {
                return address.substring(prefix.length());
            }
        }
        return address;
    }

    /** The four octets of a dotted-quad address, or null if it isn't one. */
    private static int[] ipv4(String address) {
        if (address == null) {
            return null;
        }
        Matcher m = IPV4.matcher(address);
        if (!m.matches()) {
            return null;
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            octets[i] = Integer.parseInt(m.group(i + 1));
            if (octets[i] > 255) {
                return null;
            }
        }
        return octets;
    }
}
