package com.neovisionaries.ws.client;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.SocketFactory;

public class SocketInitiator {
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


    private class SocketFuture
    {
        private CountDownLatch mLatch;
        private List<SocketRacer> mRacers;
        private Socket mSocket;
        private Exception mException;


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

            // Establisher complete.
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

            // Establisher complete.
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


    public SocketInitiator(
            SocketFactory socketFactory, Address address, int connectTimeout, String[] serverNames)
    {
        mSocketFactory = socketFactory;
        mAddress = address;
        mConnectTimeout = connectTimeout;
        mServerNames = serverNames;
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
            System.out.println("establish: address=" + address + ":" + mAddress.getPort() + ", delay=" + delay + ", timeout=" + mConnectTimeout);;;;

            // Increase the *happy eyeballs* delay (see RFC 6555, sec 5.5).
            // TODO: Make `delay` configurable
            delay += 250;

            // Create the *done* signal which acts as a *start* signal for the subsequent racer.
            Signal doneSignal = new Signal(delay);

            // Create racer to establish the socket.
            SocketAddress socketAddress = new InetSocketAddress(address, mAddress.getPort());
            SocketRacer racer = new SocketRacer(
                    future, mSocketFactory, socketAddress, mServerNames, mConnectTimeout, startSignal, doneSignal);
            racers.add(racer);

            // Replace *start* signal with this racer's *done* signal.
            startSignal = doneSignal;
        }

        // Wait until one of the sockets has been established, or all failed with an exception.
        return future.await(racers);
    }
}
