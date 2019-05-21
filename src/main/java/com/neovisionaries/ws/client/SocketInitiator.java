package com.neovisionaries.ws.client;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import javax.net.SocketFactory;

public class SocketInitiator {
    private class SocketRacer extends Thread
    {
        private final SocketFuture mFuture;
        private final SocketFactory mSocketFactory;
        private final SocketAddress mSocketAddress;
        private String[] mServerNames;
        private final int mInitialDelay;
        private final int mConnectTimeout;

        SocketRacer(
                SocketFuture future, SocketFactory socketFactory, SocketAddress socketAddress,
                String[] serverNames, int initialDelay, int connectTimeout)
        {
            mFuture         = future;
            mSocketFactory  = socketFactory;
            mSocketAddress  = socketAddress;
            mServerNames    = serverNames;
            mInitialDelay   = initialDelay;
            mConnectTimeout = connectTimeout;
        }


        public void run() {
            Socket socket = null;
            try
            {
                // Initial delay prior to connecting.
                Thread.sleep(mInitialDelay);
                System.out.println("run: slept " + mInitialDelay);;;;

                // Let the socket factory create a socket.
                socket = mSocketFactory.createSocket();
                System.out.println("run: socket " + socket);;;;

                // Set up server names for SNI as necessary if possible.
                SNIHelper.setServerNames(socket, mServerNames);

                // Connect to the server (either a proxy or a WebSocket endpoint).
                System.out.println("run: connect");;;;
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


    private class SocketFuture
    {
        private CountDownLatch mLatch;
        private List<SocketRacer> mRacers;
        private Socket mSocket;
        private Exception mException;

        void setSocket(Socket socket)
        {
            // Sanity check.
            if (mLatch == null || mRacers == null)
            {
                throw new IllegalStateException("Cannot set socket before awaiting!");
            }
            System.out.println("setSocket " + socket);;;;

            // Set socket if not already set, otherwise close socket.
            if (mSocket == null)
            {
                mSocket = socket;

                // Stop all other establishers.
                System.out.println("setSocket: interrupt");;;;
                for (SocketRacer racer: mRacers)
                {
                    racer.interrupt();
                }
            }
            else
            {
                try
                {
                    System.out.println("setSocket: close " + socket);;;;
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

        void setException(Exception exception)
        {
            // Sanity check.
            if (mLatch == null || mRacers == null)
            {
                throw new IllegalStateException("Cannot set exception before awaiting!");
            }
            System.out.println("setException " + exception);;;;

            // Set exception if not already set.
            if (mException == null)
            {
                mException = exception;
            }

            // Establisher complete.
            mLatch.countDown();
        }

        Socket await(List<SocketRacer> racers) throws Exception
        {
            // Store racers.
            mRacers = racers;

            // Create new latch.
            mLatch = new CountDownLatch(mRacers.size());

            // Start each racer.
            for (SocketRacer racer: mRacers)
            {
                racer.start();
            }

            // Wait until all racers complete.
            mLatch.await();

            // Return the socket, if any, otherwise the first exception raised
            System.out.println("await: socket=" + mSocket + ", exception=" + mException);;;;
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


    private final SocketFactory mSocketFactory;
    private final Address mAddress;
    private final int mConnectTimeout;
    private final String[] mServerNames;


    public SocketInitiator(SocketFactory socketFactory, Address address, int connectTimeout, String[] serverNames)
    {
        mSocketFactory = socketFactory;
        mAddress = address;
        mConnectTimeout = connectTimeout;
        mServerNames = serverNames;
    }


    public Socket establish(List<InetAddress> addresses) throws Exception
    {
        // Create socket future.
        SocketFuture future = new SocketFuture();

        // Create socket racer for each IP address.
        List<SocketRacer> racers = new ArrayList<SocketRacer>(addresses.size());
        int delay = 0;
        for (InetAddress resolvedAddress: addresses)
        {
            // Decrease the timeout by the accumulated delay between each connect.
            int timeout = Math.max(0, mConnectTimeout - delay);

            // Create racer to establish the socket.
            SocketAddress socketAddress = new InetSocketAddress(resolvedAddress, mAddress.getPort());
            racers.add(new SocketRacer(
                    future, mSocketFactory, socketAddress, mServerNames, delay, timeout));
            System.out.println("establish: address=" + resolvedAddress + ":" + mAddress.getPort() + ", delay=" + delay + ", timeout=" + timeout);;;;

            // Increase the *happy eyeballs* delay (see RFC 6555, sec 5.5).
            // TODO: Make `delay` configurable
            delay += 250;
        }

        // Wait until one of the sockets has been established, or all failed with an exception.
        return future.await(racers);
    }
}
