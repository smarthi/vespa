// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

/**
 * This class must be extended by the server application. It is needed
 * to let the application define the target packet handler for
 * incoming channels without creating a race condition.
 **/
class FNET_IServerAdapter
{
public:

    /**
     * Destructor.  No cleanup needed for base class.
     */
    virtual ~FNET_IServerAdapter(void) { }

    /**
     * This method is called by the network layer when an incoming
     * connection has been accepted. It gives the application a chance
     * to define the target packet handler and application context for
     * incoming admin packets. All packets received with the reserved
     * channel id (FNET_NOID) are considered admin packets.
     *
     * In order to return true from this method both the handler and
     * context must be set for the given channel object.
     *
     * NOTE: Generally, application code should never close a connection
     * by invoking the Close method directly. However, as this method is
     * invoked by the transport thread before the connection is added to
     * the event-loop framework, the Close method on the incoming
     * connection may be invoked by this method. This may be useful for
     * limiting the number of allowed concurrent connections. NOTE: if
     * the incoming connection is closed, this method MUST NOT return
     * true!
     *
     * @return success(true)/fail(false)
     * @param channel the admin channel being initialized.
     **/
    virtual bool InitAdminChannel(FNET_Channel *channel) = 0;


    /**
     * This method is called by the network layer when opening a new
     * channel on a connection handled by this server adapter. The
     * implementation of this method must define the target packet
     * handler and the application context for the given channel. The
     * 'pcode' parameter indicates the type of the first packet to be
     * received on this channel.
     *
     * @return success(true)/fail(false)
     * @param channel the channel being initialized.
     * @param pcode the packet type of the first packet on the channel.
     **/
    virtual bool InitChannel(FNET_Channel *channel,
                             uint32_t pcode) = 0;
};

