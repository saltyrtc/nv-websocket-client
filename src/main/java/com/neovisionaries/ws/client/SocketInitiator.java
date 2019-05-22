package com.neovisionaries.ws.client;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.SocketFactory;


/**
 * Lets multiple sockets race the given IP addresses until one has been
 * established.
 *
 * This follows <a href="https://tools.ietf.org/html/rfc6555">RFC 6555 (Happy
 * Eyeballs)</a>.
 *
 * @author Lennart Grahl
 */
public class SocketInitiator {
    /**
     * A <i>wait</i> signal will be awaited by a {@link SocketRacer} before it
     * starts to connect.
     *
     * When a {@link SocketRacer} <i>A</i> is done, it will unblock the
     * following racer <i>B</i> by marking <i>B's</i> signal as <i>done</i>.
     */
    private class Signal
    {
        private final CountDownLatch mLatch;
        private final int mMaxDelay;


        Signal(int maxDelay)
        {
            mLatch    = new CountDownLatch(1);
            mMaxDelay = maxDelay;
        }


        boolean isDone()
        {
            return mLatch.getCount() == 0;
        }


        void await() throws InterruptedException
        {
            mLatch.await(mMaxDelay, TimeUnit.MILLISECONDS);
        }


        void done()
        {
            mLatch.countDown();
        }
    }


    /**
     * This thread connects to a socket and notifies a {@link SocketFuture}
     * shared across all racer threads when it is done. A racer thread is done
     * when...
     *
     * <ul>
     * <li>it has established a connection, or</li>
     * <li>when establishing a connection failed with an exception, or</li>
     * <li>another racer established a connection.</li>
     * </ul>
     */
    private class SocketRacer extends Thread
    {
        private final SocketFuture mFuture;
        private final SocketFactory mSocketFactory;
        private final SocketAddress mSocketAddress;
        private String[] mServerNames;
        private final int mConnectTimeout;
        private final Signal mStartSignal;
        private final Signal mDoneSignal;


        SocketRacer(
                SocketFuture future, SocketFactory socketFactory, SocketAddress socketAddress,
                String[] serverNames, int connectTimeout, Signal startSignal, Signal doneSignal)
        {
            mFuture         = future;
            mSocketFactory  = socketFactory;
            mSocketAddress  = socketAddress;
            mServerNames    = serverNames;
            mConnectTimeout = connectTimeout;
            mStartSignal    = startSignal;
            mDoneSignal     = doneSignal;
        }


        public void run() {
            Socket socket = null;
            try
            {
                long el_aps = System.nanoTime();;;;

                // Await start signal.
                if (mStartSignal != null)
                {
                    mStartSignal.await();
                }

                // Check if a socket has already been established.
                if (mFuture.hasSocket())
                {
                    return;
                }
                System.out.println("run after " + ((System.nanoTime() - el_aps) / 1000000) + "ms : " + this);;;;

                // Let the socket factory create a socket.
                socket = mSocketFactory.createSocket();
                System.out.println("run: socket " + socket);;;;

                // Set up server names for SNI as necessary if possible.
                System.out.println("run: setServerNames: " + mServerNames);;;;
                SNIHelper.setServerNames(socket, mServerNames);

                // Connect to the server (either a proxy or a WebSocket endpoint).
                System.out.println("run: connect");;;;
                socket.connect(mSocketAddress, mConnectTimeout);

                // Socket established.
                complete(socket);
            }
            catch (Exception e)
            {
                abort(e);

                if (socket != null)
                {
                    try
                    {
                        System.out.println("run: close " + socket);;;;
                        socket.close();
                    }
                    catch (IOException ioe)
                    {
                        // ignored
                    }
                }
            }
        }


        private void complete(Socket socket)
        {
            synchronized (mFuture)
            {
                // Check if already completed or aborted.
                if (mDoneSignal.isDone()) {
                    return;
                }

                // Socket established.
                mFuture.setSocket(this, socket);

                // Socket racer complete.
                mDoneSignal.done();
            }
        }


        void abort(Exception exception)
        {
            synchronized (mFuture)
            {
                // Check if already completed or aborted.
                if (mDoneSignal.isDone())
                {
                    return;
                }

                // Socket not established.
                mFuture.setException(exception);

                // Socket racer complete.
                mDoneSignal.done();
            }
        }
    }


    /**
     * The socket future is shared across all {@link SocketRacer} threads and
     * aggregates the results. A socket future is considered fulfilled when...
     *
     * <ul>
     * <li>any racer thread has established a socket in which case all
     *     other racers will be stopped, or</li>
     * <li>all racer threads returned with an exception, or</li>
     * <li>there was no racer thread (e.g. in case there is no network
     *     interface).</li>
     * </ul>
     *
     * In the first case, the socket will be returned. In all other cases, an
     * exception will be thrown, indicating the failure type.
     */
    private class SocketFuture
    {
        private CountDownLatch mLatch;
        private List<SocketRacer> mRacers;
        private Socket mSocket;
        private Exception mException;


        synchronized boolean hasSocket()
        {
            return mSocket != null;
        }


        synchronized void setSocket(SocketRacer current, Socket socket)
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

                // Stop all other racers.
                for (SocketRacer racer: mRacers)
                {
                    // Skip instance that is setting the socket.
                    if (racer == current)
                    {
                        continue;
                    }
                    System.out.println("setSocket: interrupt-before " + racer);;;;
                    racer.abort(new InterruptedException());
                    racer.interrupt();
                    System.out.println("setSocket: interrupt-after " + racer);;;;
                }
            }
            else
            {
                try
                {
                    System.out.println("setSocket: close " + socket);;;;
                    socket.close();
                }
                catch (IOException e)
                {
                    // ignored
                }
            }

            // Racer complete.
            mLatch.countDown();
            System.out.println("-> " + mLatch.getCount());;;;
        }


        synchronized void setException(Exception exception)
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

            // Racer complete.
            mLatch.countDown();
            System.out.println("-> " + mLatch.getCount());;;;
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
            else if (mException != null)
            {
                throw mException;
            }
            else
            {
                throw new WebSocketException(WebSocketError.SOCKET_CONNECT_ERROR,
                        "No viable interface to connect");
            }
        }
    }


    private final SocketFactory mSocketFactory;
    private final Address mAddress;
    private final int mConnectTimeout;
    private final String[] mServerNames;
    private final DualStackMode mMode;
    private final int mFallbackDelay;


    public SocketInitiator(
            SocketFactory socketFactory, Address address, int connectTimeout, String[] serverNames,
            DualStackMode mode, int fallbackDelay)
    {
        mSocketFactory  = socketFactory;
        mAddress        = address;
        mConnectTimeout = connectTimeout;
        mServerNames    = serverNames;
        mMode           = mode;
        mFallbackDelay  = fallbackDelay;
    }


    public Socket establish(InetAddress[] addresses) throws Exception
    {
        // Create socket future.
        SocketFuture future = new SocketFuture();

        // Create socket racer for each IP address.
        List<SocketRacer> racers = new ArrayList<SocketRacer>(addresses.length);
        int delay = 0;
        Signal startSignal = null;
        for (InetAddress address: addresses)
        {
            // Check if the mode requires us to skip this address.
            switch (mMode)
            {
                case BOTH:
                    break;
                case IPV4_ONLY:
                    if (!(address instanceof Inet4Address))
                    {
                        continue;
                    }
                case IPV6_ONLY:
                    if (!(address instanceof Inet6Address))
                    {
                        continue;
                    }
            }
            System.out.println("establish: address=" + address + ":" + mAddress.getPort() + ", delay=" + delay + ", timeout=" + mConnectTimeout);;;;

            // Increase the *happy eyeballs* delay (see RFC 6555, sec 5.5).
            delay += mFallbackDelay;

            // Create the *done* signal which acts as a *start* signal for the subsequent racer.
            Signal doneSignal = new Signal(delay);

            // Create racer to establish the socket.
            SocketAddress socketAddress = new InetSocketAddress(address, mAddress.getPort());
            SocketRacer racer = new SocketRacer(
                    future, mSocketFactory, socketAddress, mServerNames, mConnectTimeout,
                    startSignal, doneSignal);
            racers.add(racer);

            // Replace *start* signal with this racer's *done* signal.
            startSignal = doneSignal;
        }

        // Wait until one of the sockets has been established, or all failed with an exception.
        return future.await(racers);
    }
}
