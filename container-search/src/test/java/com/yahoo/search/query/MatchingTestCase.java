// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.search.query;

import com.yahoo.search.Query;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;

/**
 * @author baldersheim
 */
public class MatchingTestCase {

    @Test
    public void testDefaultsInQuery() {
        Query query = new Query("?query=test");
        assertNull(query.getRanking().getMatching().getTermwiseLimit());
        assertNull(query.getRanking().getMatching().getNumThreadsPerSearch());
        assertNull(query.getRanking().getMatching().getNumSearchPartitions());
        assertNull(query.getRanking().getMatching().getMinHitsPerThread());

    }

    @Test
    public void testQueryOverride() {
        Query query = new Query("?query=test&ranking.matching.termwiselimit=0.7&ranking.matching.numthreadspersearch=17&ranking.matching.numsearchpartitions=13&ranking.matching.minhitsperthread=3");
        assertEquals(Double.valueOf(0.7), query.getRanking().getMatching().getTermwiseLimit());
        assertEquals(Integer.valueOf(17), query.getRanking().getMatching().getNumThreadsPerSearch());
        assertEquals(Integer.valueOf(13), query.getRanking().getMatching().getNumSearchPartitions());
        assertEquals(Integer.valueOf(3), query.getRanking().getMatching().getMinHitsPerThread());

        query.prepare();
        assertEquals("0.7", query.getRanking().getProperties().get("vespa.matching.termwise_limit").get(0));
        assertEquals("17", query.getRanking().getProperties().get("vespa.matching.numthreadspersearch").get(0));
        assertEquals("13", query.getRanking().getProperties().get("vespa.matching.numsearchpartitions").get(0));
        assertEquals("3", query.getRanking().getProperties().get("vespa.matching.minhitsperthread").get(0));
    }

    private void verifyException(String key, String value) {
        try {
            new Query("?query=test&ranking.matching."+key+"="+value);
            fail();
        } catch (IllegalArgumentException e) {
            assertEquals("Could not set 'ranking.matching." + key + "' to '" + value +"'", e.getMessage());
            assertEquals(key + " must be in the range [0.0, 1.0]. It is " + value, e.getCause().getMessage());
        }
    }

    @Test
    public void testLimits() {
        verifyException("termwiselimit", "-0.1");
        verifyException("termwiselimit", "1.1");
    }

}
