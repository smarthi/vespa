// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.config;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;

/**
 * @author gjoranv
 * @since 5.1.28
 */
public class PathNodeTest {

    @Test
    public void testSetValue() {
        PathNode n = new PathNode();
        assertEquals("(null)", n.toString());

        n = new PathNode(new FileReference("foo.txt"));
        assertEquals(new File("foo.txt").toPath(), n.value());
    }

}
