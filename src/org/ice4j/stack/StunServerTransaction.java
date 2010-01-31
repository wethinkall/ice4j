/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.stack;

import java.io.*;

import org.ice4j.*;
import org.ice4j.message.*;

/**
 * A STUN client retransmits requests as specified by the protocol.
 *
 * Once formulated and sent, the client sends the Binding Request.  Reliability
 * is accomplished through request retransmissions.  The ClientTransaction
 * retransmits the request starting with an interval of 100ms, doubling
 * every retransmit until the interval reaches 1.6s.  Retransmissions
 * continue with intervals of 1.6s until a response is received, or a
 * total of 9 requests have been sent. If no response is received by 1.6
 * seconds after the last request has been sent, the client SHOULD
 * consider the transaction to have failed. In other words, requests
 * would be sent at times 0ms, 100ms, 300ms, 700ms, 1500ms, 3100ms,
 * 4700ms, 6300ms, and 7900ms. At 9500ms, the client considers the
 * transaction to have failed if no response has been received.
 *
 * A server transaction is therefore responsible for retransmitting the same
 * response that was saved for the original request, and not let any
 * retransmissions go through to the user application.
 *
 * @author Emil Ivov
 */

class StunServerTransaction
    implements Runnable
{
    /**
     * The time that we keep server transactions active.
     */
    private long transactionLifetime = 16000;

    /**
     * The StunProvider that created us.
     */
    private StunProvider providerCallback  = null;

    /**
     * The source of the transaction request.
     */
    private TransportAddress responseDestination = null;

    /**
     * The response sent in response to the request.
     */
    private Response response = null;

    /**
     * The <tt>TransportAddress</tt> we use when sending responses
     */
    private TransportAddress localAddress = null;

    /**
     * The id of the transaction.
     */
    private TransactionID transactionID = null;

    /**
     * The date (in milliseconds) when the next retransmission should follow.
     */
    private long expirationDate = -1;

    /**
     * The thread that this transaction runs in.
     */
    private Thread runningThread = null;

    /**
     * Determines whether or not the transaction has expired.
     */
    private boolean expired = true;

    /**
     * Determines whether or not the transaction is in a retransmitting state.
     * In other words whether a response has already been sent once to the
     * transaction request.
     */
    private boolean isRetransmitting = false;

    /**
     * Creates a server transaction
     * @param providerCallback the provider that created us.
     * @param tranID the transaction id contained by the request that was the
     * cause for this transaction.
     */
    public StunServerTransaction(StunProvider            providerCallback,
                                 TransactionID           tranID)
    {
        this.providerCallback  = providerCallback;

        this.transactionID = tranID;

        runningThread = new Thread(this);
    }

    /**
     * Start the transaction. This launches the countdown to the moment the
     * transaction would expire.
     */
    public void start()
    {
        expired = false;
        runningThread.start();
    }

    /**
     * Actually this method is simply a timer waiting for the server transaction
     * lifetime to come to an end.
     */
    public void run()
    {
        runningThread.setName("ServTran");

        schedule(transactionLifetime);
        waitNextScheduledDate();

        //let's get lost
        expire();
        providerCallback.removeServerTransaction(this);
    }

    /**
     * Sends the specified response through the <code>sendThrough</code>
     * NetAccessPoint descriptor to the specified destination and changes
     * the transaction's state to retransmitting.
     *
     * @param response the response to send the transaction to.
     * @param sendThrough the local address through which the response is to
     * be sent
     * @param sendTo the destination of the response.
     *
     * @throws IOException  if an error occurs while sending message bytes
     * through the network socket.
     * @throws IllegalArgumentException if the apDescriptor references an
     * access point that had not been installed,
     * @throws StunException if message encoding fails,
     */
    public void sendResponse(Response response,
                             TransportAddress sendThrough,
                             TransportAddress sendTo)
        throws StunException,
               IOException,
               IllegalArgumentException
    {
        if(!isRetransmitting){
            this.response = response;
            //the transaction id might already have been set, but its our job
            //to make sure of that
            response.setTransactionID(this.transactionID.getTransactionID());
            this.localAddress = sendThrough;
            this.responseDestination = sendTo;
        }

        isRetransmitting = true;
        retransmitResponse();
    }

    /**
     * Retransmits the response that was originally sent to the request that
     * caused this transaction.
     *
     * @throws IOException  if an error occurs while sending message bytes
     * through the network socket.
     * @throws IllegalArgumentException if the apDescriptor references an
     * access point that had not been installed,
     * @throws StunException if message encoding fails,
     */
    private void retransmitResponse()
        throws StunException,
               IOException,
               IllegalArgumentException
    {
        //don't retransmit if we are expired or if the user application
        //hasn't yet transmitted a first response
        if(expired || !isRetransmitting)
            return;

        providerCallback.getNetAccessManager().sendMessage(response,
                                                           localAddress,
                                                           responseDestination);
    }

    /**
     * Waits until next retransmission is due or until the transaction is
     * canceled (whichever comes first).
     */
    private synchronized void waitNextScheduledDate()
    {
        long current = System.currentTimeMillis();
        while(expirationDate - current > 0)
        {
            try
            {
                wait(expirationDate - current);
            }
            catch (InterruptedException ex)
            {
            }

            //did someone ask us to get lost?
            if(expired)
                return;
            current = System.currentTimeMillis();
        }
    }

    /**
     * Sets the expiration date for this server transaction.
     *
     * @param timeout the number of milliseconds to wait before expiration.
     */
    private void schedule(long timeout)
    {
        this.expirationDate = System.currentTimeMillis() + timeout;
    }

    /**
     * Cancels the transaction. Once this method is called the transaction is
     * considered terminated and will stop retransmissions.
     */
    public synchronized void expire()
    {
        this.expired = true;
        notifyAll();
    }


    /**
     * Returns the ID of the current transaction.
     *
     * @return the ID of the transaction.
     */
    public TransactionID getTransactionID()
    {
        return this.transactionID;
    }

    /**
     * Specifies whether this server transaction is in the retransmitting state.
     * Or in other words - has it already sent a first response or not?
     *
     * @return <tt>true</tt> if this transaction is still retransmitting and
     * false <tt>otherwise</tt>
     */
    public boolean isReransmitting()
    {
        return isRetransmitting;
    }
}
