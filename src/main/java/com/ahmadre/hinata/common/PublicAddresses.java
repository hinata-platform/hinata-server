package com.ahmadre.hinata.common;

import java.net.InetAddress;

/**
 * Whether an address lies on the public internet: the question every server-side
 * fetch of a URL somebody else typed has to answer before it connects.
 *
 * <p>Shared because two fetchers ask it, the one for the organization logo and the
 * calendar fetcher of the {@code ics} package. A rule like this is only as good as
 * its least careful copy, so there is one.
 *
 * <p>The IPv4 side names every range IANA's special-purpose registry marks as not
 * globally reachable. The IPv6 side goes the other way round and admits only
 * global unicast ({@code 2000::/3}) minus the blocks inside it that are not
 * reachable either; everything outside it (loopback, link-local, unique-local,
 * multicast, the discard prefix) is refused without having to be listed.
 *
 * <p>Two IPv6 forms carry an IPv4 address and are judged by that address.
 * IPv4-mapped addresses ({@code ::ffff:0:0/96}) are usually unwrapped by the JDK
 * already, but not on every path. The NAT64 well-known prefix
 * ({@code 64:ff9b::/96}) is what a DNS64 resolver hands out for a public site on
 * an IPv6-only host, so it has to stay usable; it must not become a way to spell
 * 169.254.169.254 that the JDK's own address predicates do not recognise.
 */
public final class PublicAddresses {

	private PublicAddresses() {
	}

	/** True only for an address that is reachable on the public internet. */
	public static boolean isPublic(InetAddress address) {
		byte[] bytes = address.getAddress();
		return bytes.length == 4 ? isPublicIpv4(bytes, 0) : isPublicIpv6(bytes);
	}

	private static boolean isPublicIpv4(byte[] bytes, int offset) {
		int first = bytes[offset] & 0xFF;
		int second = bytes[offset + 1] & 0xFF;
		int third = bytes[offset + 2] & 0xFF;
		boolean special = first == 0                                // 0.0.0.0/8, "this network"
				|| first == 10                                      // 10.0.0.0/8, private
				|| first == 100 && second >= 64 && second <= 127    // 100.64.0.0/10, carrier-grade NAT
				|| first == 127                                     // 127.0.0.0/8, loopback
				|| first == 169 && second == 254                    // 169.254.0.0/16, link-local and cloud metadata
				|| first == 172 && second >= 16 && second <= 31     // 172.16.0.0/12, private
				|| first == 192 && second == 0 && third == 0        // 192.0.0.0/24, protocol assignments
				|| first == 192 && second == 0 && third == 2        // 192.0.2.0/24, documentation
				|| first == 192 && second == 88 && third == 99      // 192.88.99.0/24, former 6to4 relays
				|| first == 192 && second == 168                    // 192.168.0.0/16, private
				|| first == 198 && (second == 18 || second == 19)   // 198.18.0.0/15, benchmarking
				|| first == 198 && second == 51 && third == 100     // 198.51.100.0/24, documentation
				|| first == 203 && second == 0 && third == 113      // 203.0.113.0/24, documentation
				|| first >= 224;                                    // multicast, reserved, broadcast
		return !special;
	}

	private static boolean isPublicIpv6(byte[] bytes) {
		if (carriesIpv4(bytes)) {
			return isPublicIpv4(bytes, 12);
		}
		if ((bytes[0] & 0xE0) != 0x20) {
			return false;
		}
		int first = word(bytes, 0);
		int second = word(bytes, 2);
		boolean special = first == 0x2001 && second < 0x0200       // 2001::/23, protocol assignments incl. Teredo
				|| first == 0x2001 && second == 0x0DB8              // 2001:db8::/32, documentation
				|| first == 0x2002                                  // 2002::/16, 6to4 wraps any IPv4 address
				|| first == 0x3FFF && second < 0x1000;              // 3fff::/20, documentation
		return !special;
	}

	/** IPv4-mapped ({@code ::ffff:a.b.c.d}) or the NAT64 well-known prefix ({@code 64:ff9b::a.b.c.d}). */
	private static boolean carriesIpv4(byte[] bytes) {
		for (int i = 4; i < 10; i++) {
			if (bytes[i] != 0) {
				return false;
			}
		}
		boolean mapped = word(bytes, 0) == 0 && word(bytes, 2) == 0 && word(bytes, 10) == 0xFFFF;
		boolean nat64 = word(bytes, 0) == 0x0064 && word(bytes, 2) == 0xFF9B && word(bytes, 10) == 0;
		return mapped || nat64;
	}

	private static int word(byte[] bytes, int offset) {
		return (bytes[offset] & 0xFF) << 8 | bytes[offset + 1] & 0xFF;
	}
}
