// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.messagebus.test;

import com.yahoo.component.Version;
import com.yahoo.messagebus.EmptyReply;
import com.yahoo.messagebus.Routable;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * @author Simon Thoresen Hult
 */
public class SimpleProtocolTestCase {

    private static final Version VERSION = new Version(1);
    private static final SimpleProtocol PROTOCOL = new SimpleProtocol();

    @Test
    public void requireThatNameIsSet() {
        assertEquals(SimpleProtocol.NAME, PROTOCOL.getName());
    }


    @Test
    public void requireThatMessageCanBeEncodedAndDecoded() {
        SimpleMessage msg = new SimpleMessage("foo");
        byte[] buf = PROTOCOL.encode(Version.emptyVersion, msg);
        Routable routable = PROTOCOL.decode(Version.emptyVersion, buf);
        assertNotNull(routable);
        assertEquals(SimpleMessage.class, routable.getClass());
        msg = (SimpleMessage)routable;
        assertEquals("foo", msg.getValue());
    }

    @Test
    public void requireThatReplyCanBeDecoded() {
        SimpleReply reply = new SimpleReply("foo");
        byte[] buf = PROTOCOL.encode(Version.emptyVersion, reply);
        Routable routable = PROTOCOL.decode(Version.emptyVersion, buf);
        assertNotNull(routable);
        assertEquals(SimpleReply.class, routable.getClass());
        reply = (SimpleReply)routable;
        assertEquals("foo", reply.getValue());
    }

    @Test
    public void requireThatUnknownRoutablesAreNotEncoded() {
        assertNull(PROTOCOL.encode(VERSION, new EmptyReply()));
    }

    @Test
    public void requireThatEmptyBufferIsNotDecoded() {
        assertNull(PROTOCOL.decode(VERSION, new byte[0]));
    }

    @Test
    public void requireThatUnknownBufferIsNotDecoded() {
        assertNull(PROTOCOL.decode(VERSION, new byte[] { 'U' }));
    }
}
