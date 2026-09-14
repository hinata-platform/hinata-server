package com.ahmadre.hinata.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which addresses count as the public internet. Every value is an address
 * literal, which the JDK parses without asking DNS, so this runs offline.
 */
class PublicAddressesTest {

	@ParameterizedTest
	@ValueSource(strings = {
			"8.8.8.8", "93.184.215.14", "1.1.1.1",
			// The addresses right outside a refused range are ordinary ones.
			"100.63.255.255", "100.128.0.0", "172.15.255.255", "172.32.0.0",
			"198.17.255.255", "198.20.0.0", "223.255.255.255",
			"2606:4700:4700::1111", "2a00:1450:4001:82a::200e", "2001:200::1",
			// An IPv6-only host behind DNS64 reaches a public site this way.
			"64:ff9b::808:808" })
	void anAddressOnThePublicInternetIsPublic(String literal) throws UnknownHostException {
		assertThat(PublicAddresses.isPublic(InetAddress.getByName(literal))).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"0.0.0.0", "0.255.255.255", "10.0.0.1", "100.64.0.1", "100.127.255.255",
			"127.0.0.1", "127.255.255.254", "169.254.169.254", "172.16.0.1", "172.31.255.255",
			"192.0.0.170", "192.0.2.1", "192.88.99.1", "192.168.178.1", "198.18.0.1",
			"198.19.255.255", "198.51.100.7", "203.0.113.9", "224.0.0.1", "239.255.255.250",
			"240.0.0.1", "255.255.255.255",
			"::", "::1", "::127.0.0.1", "::ffff:127.0.0.1", "::ffff:169.254.169.254",
			"64:ff9b::a9fe:a9fe", "64:ff9b::7f00:1", "64:ff9b:1::a", "100::1", "2001::1",
			"2001:db8::1", "2002:7f00:1::1", "3fff::1", "5f00::1", "fc00::1", "fd12:3456::1",
			"fe80::1", "fec0::1", "ff02::1" })
	void aSpecialPurposeAddressIsNot(String literal) throws UnknownHostException {
		assertThat(PublicAddresses.isPublic(InetAddress.getByName(literal))).isFalse();
	}

	@Test
	void anIpv4MappedAddressTheJdkDidNotUnwrapIsJudgedByItsIpv4Part() throws UnknownHostException {
		// InetAddress.getByName turns ::ffff:a.b.c.d into an Inet4Address. An
		// Inet6Address built from raw bytes keeps the mapped form, and a resolver
		// may hand out exactly that.
		byte[] metadata = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF,
				(byte) 169, (byte) 254, (byte) 169, (byte) 254 };
		byte[] google = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 8, 8, 8, 8 };

		assertThat(PublicAddresses.isPublic(Inet6Address.getByAddress(null, metadata, -1))).isFalse();
		assertThat(PublicAddresses.isPublic(Inet6Address.getByAddress(null, google, -1))).isTrue();
	}
}
