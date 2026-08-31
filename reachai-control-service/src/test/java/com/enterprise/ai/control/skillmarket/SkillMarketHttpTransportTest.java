package com.enterprise.ai.control.skillmarket;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillMarketHttpTransportTest {

    @Test
    void rejectsLocalPrivateAndCarrierGradeNatAddresses() throws Exception {
        assertTrue(nonPublic("127.0.0.1"));
        assertTrue(nonPublic("10.20.30.40"));
        assertTrue(nonPublic("172.20.1.1"));
        assertTrue(nonPublic("192.168.1.1"));
        assertTrue(nonPublic("100.64.1.1"));
        assertTrue(nonPublic("169.254.169.254"));
        assertTrue(nonPublic("::1"));
        assertTrue(nonPublic("fd00::1"));
    }

    @Test
    void rejectsDocumentationAndOtherNonRoutableRanges() throws Exception {
        assertTrue(nonPublic("192.0.2.10"));
        assertTrue(nonPublic("198.51.100.10"));
        assertTrue(nonPublic("203.0.113.10"));
        assertTrue(nonPublic("2001:db8::10"));
        assertTrue(nonPublic("224.0.0.1"));
        assertTrue(nonPublic("255.255.255.255"));
    }

    @Test
    void acceptsRepresentativePublicAddresses() throws Exception {
        assertFalse(nonPublic("1.1.1.1"));
        assertFalse(nonPublic("8.8.8.8"));
        assertFalse(nonPublic("2606:4700:4700::1111"));
    }

    @Test
    void recognizesOnlyTheExplicitRfc2544SyntheticProxyPool() throws Exception {
        assertTrue(SkillMarketHttpTransport.isSyntheticProxyAddress(
                InetAddress.getByName("198.18.0.140")));
        assertTrue(SkillMarketHttpTransport.isSyntheticProxyAddress(
                InetAddress.getByName("198.19.255.254")));
        assertTrue(SkillMarketHttpTransport.isSyntheticProxyAddress(
                InetAddress.getByName("fdfe:dcba:9876::15d")));
        assertFalse(SkillMarketHttpTransport.isSyntheticProxyAddress(
                InetAddress.getByName("198.51.100.10")));
        assertFalse(SkillMarketHttpTransport.isSyntheticProxyAddress(
                InetAddress.getByName("10.0.0.1")));
        assertFalse(SkillMarketHttpTransport.isSyntheticProxyAddress(
                InetAddress.getByName("fdfe:dcba:9877::1")));
        assertFalse(SkillMarketHttpTransport.isSyntheticProxyAddress(
                InetAddress.getByName("fd00::1")));
    }

    private boolean nonPublic(String address) throws Exception {
        return SkillMarketHttpTransport.isNonPublic(InetAddress.getByName(address));
    }
}
