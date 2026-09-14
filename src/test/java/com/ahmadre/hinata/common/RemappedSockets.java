package com.ahmadre.hinata.common;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Connects every socket to a local test server, and notes the address the client asked for.
 *
 * <p>Production refuses exactly what a local test server is: a loopback address on a
 * random port. With these sockets no rule has to be relaxed. The client is told a public
 * address, and the socket it opens to that address reaches the local server underneath.
 */
public final class RemappedSockets extends SocketFactory {

	private final InetSocketAddress server;
	private final List<InetSocketAddress> requested = new CopyOnWriteArrayList<>();

	public RemappedSockets(InetSocketAddress server) {
		this.server = server;
	}

	/** Every address a socket was asked to connect to, in order. */
	public List<InetSocketAddress> requested() {
		return requested;
	}

	@Override
	public Socket createSocket() {
		return new Socket() {
			@Override
			public void connect(SocketAddress endpoint, int timeout) throws IOException {
				requested.add((InetSocketAddress) endpoint);
				super.connect(server, timeout);
			}
		};
	}

	@Override
	public Socket createSocket(String host, int port) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Socket createSocket(String host, int port, InetAddress localHost, int localPort) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Socket createSocket(InetAddress host, int port) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) {
		throw new UnsupportedOperationException();
	}
}
