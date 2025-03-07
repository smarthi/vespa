// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.restapi.application;

import com.yahoo.container.jdisc.HttpRequest;
import com.yahoo.jdisc.Container;
import com.yahoo.jdisc.Request;
import com.yahoo.jdisc.ResourceReference;
import com.yahoo.jdisc.handler.RequestHandler;
import com.yahoo.jdisc.service.CurrentContainer;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @author bratseth
 */
public class MultipartParserTest {

    @Test
    public void multipartParserTest() throws URISyntaxException {
        String data =
                "Content-Type: multipart/form-data; boundary=AaB03x\r\n" +
                "\r\n" +
                "--AaB03x\r\n" +
                "Content-Disposition: form-data; name=\"submit-name\"\r\n" +
                "\r\n" +
                "Larry\r\n" +
                "--AaB03x\r\n" +
                "Content-Disposition: form-data; name=\"submit-address\"\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "House 1\r\n" +
                "--AaB03x\r\n" +
                "Content-Disposition: form-data; name=\"files\"; filename=\"file1.txt\"\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "... contents of file1.txt ...\r\n" +
                "--AaB03x--\r\n";
        ByteArrayInputStream dataStream = new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.createRequest(new MockCurrentContainer(), 
                                                        new URI("http://foo"), 
                                                        com.yahoo.jdisc.http.HttpRequest.Method.POST, 
                                                        dataStream);
        request.getJDiscRequest().headers().put("Content-Type", "multipart/form-data; boundary=AaB03x");
        Map<String, byte[]> parts = new MultipartParser().parse(request);
        assertEquals(3, parts.size());
        assertTrue(parts.containsKey("submit-name"));
        assertTrue(parts.containsKey("submit-address"));
        assertTrue(parts.containsKey("files"));
        assertEquals("Larry", new String(parts.get("submit-name"), StandardCharsets.UTF_8));
        assertEquals("... contents of file1.txt ...", new String(parts.get("files"), StandardCharsets.UTF_8));
    }
    
    private static class MockCurrentContainer implements CurrentContainer {

        @Override
        public Container newReference(URI uri) { return new MockContainer(); }

    }
    
    private static class MockContainer implements Container {

        @Override
        public RequestHandler resolveHandler(Request request) { return null; }

        @Override
        public <T> T getInstance(Class<T> aClass) { return null; }

        @Override
        public ResourceReference refer() { return null; }

        @Override
        public void release() { }

        @Override
        public long currentTimeMillis() { return 0; }

    }

}
