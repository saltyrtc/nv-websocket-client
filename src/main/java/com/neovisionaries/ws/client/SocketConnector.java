/*
 * Copyright (C) 2016-2017 Neo Visionaries Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.neovisionaries.ws.client;


import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;


/**
 * A class to connect to the server.
 *
 * @since 1.20
 *
 * @author Takahiko Kawasaki
 */
class SocketConnector
{
    private final SocketFactory mSocketFactory;
    private final Address mAddress;
    private final int mConnectionTimeout;
    private final String[] mServerNames;
    private final ProxyHandshaker mProxyHandshaker;
    private final SSLSocketFactory mSSLSocketFactory;
    private final String mHost;
    private final int mPort;
    private boolean mVerifyHostname;

    SocketConnector(SocketFactory socketFactory, Address address, int timeout, String[] serverNames)
    {
        this(socketFactory, address, timeout, serverNames, null, null, null, 0);
    }


    SocketConnector(
            SocketFactory socketFactory, Address address, int timeout, String[] serverNames,
            ProxyHandshaker handshaker, SSLSocketFactory sslSocketFactory,
            String host, int port)
    {
        mSocketFactory     = socketFactory;
        mAddress           = address;
        mConnectionTimeout = timeout;
        mServerNames       = serverNames;
        mProxyHandshaker   = handshaker;
        mSSLSocketFactory  = sslSocketFactory;
        mHost              = host;
        mPort              = port;
    }


    public Socket getSocket()
    {
        return mSocket;
    }


    public int getConnectionTimeout()
    {
        return mConnectionTimeout;
    }


    public void connect() throws WebSocketException
    {
        try
        {
            // Connect to the server (either a proxy or a WebSocket endpoint).
            doConnect();
        }
        catch (WebSocketException e)
        {
            // Failed to connect the server.

            try
            {
                // Close the socket.
                mSocket.close();
            }
            catch (IOException ioe)
            {
                // Ignore any error raised by close().
            }

            throw e;
        }
    }


    SocketConnector setVerifyHostname(boolean verifyHostname)
    {
        mVerifyHostname = verifyHostname;

        return this;
    }


    private class SocketFuture
    {
        private final CountDownLatch mLatch;
        private final List<SocketEstablisher> mEstablishers;
        private Socket mSocket;
        private Exception mException;

        SocketFuture(List<SocketEstablisher> establishers)
        {
            mLatch        = new CountDownLatch(establishers.size());
            mEstablishers = establishers;

            // Attach future to establishers.
            for (SocketEstablisher establisher: mEstablishers)
            {
                establisher.setFuture(this);
            }
        }

        public void setSocket(Socket socket)
        {
            // Set socket if not already set, otherwise close socket.
            if (mSocket == null)
            {
                mSocket = socket;

                // Stop all other establishers.
                for (SocketEstablisher establisher: mEstablishers)
                {
                    establisher.interrupt();
                }
            }
            else
            {
                try
                {
                    mSocket.close();
                }
                catch (IOException e)
                {
                    // ignored
                }
            }

            // Establisher complete.
            mLatch.countDown();
        }

        public void setException(Exception exception)
        {
            // Set exception if not already set.
            if (mException == null)
            {
                mException = exception;
            }

            // Establisher complete.
            mLatch.countDown();
        }

        public Socket await() throws Exception
        {
            mLatch.await();

            // Return the socket, if any, otherwise the first exception raised
            if (mSocket != null)
            {
                return mSocket;
            }
            else
            {
                throw mException;
            }
        }
    }


    private class SocketEstablisher extends Thread
    {
        private final SocketFactory mSocketFactory;
        private final SocketAddress mSocketAddress;
        private String[] mServerNames;
        private final int mInitialDelay;
        private final int mConnectTimeout;
        private SocketFuture mFuture;

        SocketEstablisher(
                SocketFactory socketFactory, SocketAddress socketAddress,
                String[] serverNames, int initialDelay, int connectTimeout)
        {
            mSocketFactory  = socketFactory;
            mSocketAddress  = socketAddress;
            mServerNames    = serverNames;
            mInitialDelay   = initialDelay;
            mConnectTimeout = connectTimeout;
        }

        public void setFuture(SocketFuture future)
        {
            mFuture = future;
        }

        public void run() {
            Socket socket = null;
            try
            {
                // Initial delay prior to connecting.
                Thread.sleep(mInitialDelay);

                // Let the socket factory create a socket.
                socket = mSocketFactory.createSocket();

                // Set up server names for SNI as necessary if possible.
                SNIHelper.setServerNames(socket, mServerNames);

                // Connect to the server (either a proxy or a WebSocket endpoint).
                socket.connect(mSocketAddress, mConnectTimeout);

                // Socket established
                mFuture.setSocket(socket);
            }
            catch (Exception e)
            {
                if (socket != null)
                {
                    try
                    {
                        socket.close();
                    }
                    catch (IOException ioe)
                    {
                        // ignored
                    }
                }
                mFuture.setException(e);
            }
        }
    }


    private void doConnect() throws WebSocketException
    {
        // True if a proxy server is set.
        boolean proxied = mProxyHandshaker != null;

        try
        {
            // Resolve hostname to IPv4 and IPv6 addresses.
            List<InetAddress> resolvedAddresses = new ArrayList<InetAddress>();
            // TODO: Allow to force v6 only
            resolvedAddresses.addAll(Arrays.asList(Inet6Address.getAllByName(mAddress.getHostname())));
            // TODO: Allow to force v4 only
            resolvedAddresses.addAll(Arrays.asList(Inet4Address.getAllByName(mAddress.getHostname())));

            // Create socket establishment instance for each IP address.
            List<SocketEstablisher> socketEstablishers = new ArrayList<SocketEstablisher>(
                    resolvedAddresses.size());
            int delay = 0;
            for (InetAddress resolvedAddress: resolvedAddresses)
            {
                // Decrease the timeout by the accumulated delay between each connect.
                int timeout = Math.max(0, mConnectionTimeout - delay);

                // Create thread to establish the socket.
                SocketAddress socketAddress = new InetSocketAddress(resolvedAddress, mAddress.getPort());
                socketEstablishers.add(new SocketEstablisher(
                        mSocketFactory, socketAddress, mServerNames, delay, timeout));

                // Increase the *happy eyeballs* delay (see RFC 6555, sec 5.5).
                // TODO: Make `delay` configurable
                delay += 250;
            }

            // Wrap socket establishment instances in a future.
            SocketFuture socketFuture = new SocketFuture(socketEstablishers);

            // Wait until one of the sockets has been established or all failed with an exception.
            socketFuture.await();



            // Connect to the server (either a proxy or a WebSocket endpoint).
            mSocket.connect(mAddress.toInetSocketAddress(), mConnectionTimeout);

            if (mSocket instanceof SSLSocket)
            {
                // Verify that the hostname matches the certificate here since
                // this is not automatically done by the SSLSocket.
                verifyHostname((SSLSocket)mSocket, mAddress.getHostname());
            }
        }
        catch (Exception e)
        {
            // Failed to connect the server.
            String message = String.format("Failed to connect to %s'%s': %s",
                (proxied ? "the proxy " : ""), mAddress, e.getMessage());

            // Raise an exception with SOCKET_CONNECT_ERROR.
            throw new WebSocketException(WebSocketError.SOCKET_CONNECT_ERROR, message, e);
        }

        // If a proxy server is set.
        if (proxied)
        {
            // Perform handshake with the proxy server.
            // SSL handshake is performed as necessary, too.
            handshake();
        }
    }


    private void verifyHostname(SSLSocket socket, String hostname) throws HostnameUnverifiedException
    {
        if (mVerifyHostname == false)
        {
            // Skip hostname verification.
            return;
        }

        // Hostname verifier.
        OkHostnameVerifier verifier = OkHostnameVerifier.INSTANCE;

        // The SSL session.
        SSLSession session = socket.getSession();

        // Verify the hostname.
        if (verifier.verify(hostname, session))
        {
            // Verified. No problem.
            return;
        }

        // The certificate of the peer does not match the expected hostname.
        throw new HostnameUnverifiedException(socket, hostname);
    }


    /**
     * Perform proxy handshake and optionally SSL handshake.
     */
    private void handshake() throws WebSocketException
    {
        try
        {
            // Perform handshake with the proxy server.
            mProxyHandshaker.perform();
        }
        catch (IOException e)
        {
            // Handshake with the proxy server failed.
            String message = String.format(
                "Handshake with the proxy server (%s) failed: %s", mAddress, e.getMessage());

            // Raise an exception with PROXY_HANDSHAKE_ERROR.
            throw new WebSocketException(WebSocketError.PROXY_HANDSHAKE_ERROR, message, e);
        }

        if (mSSLSocketFactory == null)
        {
            // SSL handshake with the WebSocket endpoint is not needed.
            return;
        }

        try
        {
            // Overlay the existing socket.
            mSocket = mSSLSocketFactory.createSocket(mSocket, mHost, mPort, true);
        }
        catch (IOException e)
        {
            // Failed to overlay an existing socket.
            String message = "Failed to overlay an existing socket: " + e.getMessage();

            // Raise an exception with SOCKET_OVERLAY_ERROR.
            throw new WebSocketException(WebSocketError.SOCKET_OVERLAY_ERROR, message, e);
        }

        try
        {
            // Start the SSL handshake manually. As for the reason, see
            // http://docs.oracle.com/javase/7/docs/technotes/guides/security/jsse/samples/sockets/client/SSLSocketClient.java
            ((SSLSocket)mSocket).startHandshake();

            if (mSocket instanceof SSLSocket)
            {
                // Verify that the proxied hostname matches the certificate here since
                // this is not automatically done by the SSLSocket.
                verifyHostname((SSLSocket)mSocket, mProxyHandshaker.getProxiedHostname());
            }
        }
        catch (IOException e)
        {
            // SSL handshake with the WebSocket endpoint failed.
            String message = String.format(
                "SSL handshake with the WebSocket endpoint (%s) failed: %s", mAddress, e.getMessage());

            // Raise an exception with SSL_HANDSHAKE_ERROR.
            throw new WebSocketException(WebSocketError.SSL_HANDSHAKE_ERROR, message, e);
        }
    }


    void closeSilently()
    {
        try
        {
            mSocket.close();
        }
        catch (Throwable t)
        {
            // Ignored.
        }
    }
}
