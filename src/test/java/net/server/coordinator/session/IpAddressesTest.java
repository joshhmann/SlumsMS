package net.server.coordinator.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.5.0 review: the LAN patterns were unanchored, so any address containing "10." or "192.168." counted as LAN. */
class IpAddressesTest {

    @Test
    void publicAddressesThatContainAPrivatePrefixAreNotLan() {
        for (String ip : new String[]{"73.110.5.2", "98.10.1.1", "8.172.16.1", "1.192.168.4", "210.1.1.1", "110.0.0.1",
                "172.15.0.1", "172.32.0.1", "192.169.0.1", "11.0.0.1", "8.8.8.8"}) {
            assertFalse(IpAddresses.isLanAddress(ip), ip);
            assertFalse(IpAddresses.isLocalAddress(ip), ip);
        }
    }

    @Test
    void privateRangesAreLan() {
        for (String ip : new String[]{"10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.254", "192.168.0.186",
                "::ffff:192.168.1.5", "0:0:0:0:0:ffff:10.1.2.3"}) {
            assertTrue(IpAddresses.isLanAddress(ip), ip);
        }
    }

    @Test
    void loopbackIsLocal() {
        for (String ip : new String[]{"127.0.0.1", "127.8.9.10", "::1", "0:0:0:0:0:0:0:1", "0:0:0:0:0:0:0:1%lo",
                "::ffff:127.0.0.1"}) {
            assertTrue(IpAddresses.isLocalAddress(ip), ip);
        }
        assertFalse(IpAddresses.isLocalAddress("1.127.0.0"));
        assertFalse(IpAddresses.isLocalAddress("::2"));
    }

    @Test
    void junkIsNeither() {
        for (String ip : new String[]{"null", "", "10.0.0", "10.0.0.256", "10.0.0.1.5", "2001:db8::10"}) {
            assertFalse(IpAddresses.isLanAddress(ip), ip);
            assertFalse(IpAddresses.isLocalAddress(ip), ip);
        }
        assertFalse(IpAddresses.isLanAddress(null));
        assertFalse(IpAddresses.isLocalAddress(null));
    }

    @Test
    void aPublicAddressKeepsItsOwnBucket() {
        assertTrue("73.110.5.2".equals(IpAddresses.evaluateRemoteAddress("73.110.5.2")));
        assertTrue("98.10.1.1".equals(IpAddresses.evaluateRemoteAddress("98.10.1.1")));
    }
}
