// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.jdisc.handler;

import com.yahoo.jdisc.Response;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertSame;


/**
 * @author Simon Thoresen Hult
 */
public class CallableResponseDispatchTestCase {

    @Test
    public void requireThatDispatchIsCalled() throws Exception {
        final Response response = new Response(Response.Status.OK);
        FutureResponse handler = new FutureResponse();
        new CallableResponseDispatch(handler) {

            @Override
            protected Response newResponse() {
                return response;
            }
        }.call();
        assertSame(response, handler.get(600, TimeUnit.SECONDS));
    }
}
