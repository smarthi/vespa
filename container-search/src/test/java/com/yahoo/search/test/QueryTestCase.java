// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.search.test;

import com.yahoo.component.chain.Chain;
import com.yahoo.data.JsonProducer;
import com.yahoo.language.Language;
import com.yahoo.language.Linguistics;
import com.yahoo.language.detect.Detection;
import com.yahoo.language.detect.Detector;
import com.yahoo.language.detect.Hint;
import com.yahoo.language.process.StemMode;
import com.yahoo.language.process.Token;
import com.yahoo.language.simple.SimpleDetector;
import com.yahoo.language.simple.SimpleLinguistics;
import com.yahoo.prelude.Index;
import com.yahoo.prelude.IndexFacts;
import com.yahoo.prelude.IndexModel;
import com.yahoo.prelude.SearchDefinition;
import com.yahoo.prelude.fastsearch.FastHit;
import com.yahoo.prelude.query.AndItem;
import com.yahoo.prelude.query.AndSegmentItem;
import com.yahoo.prelude.query.CompositeItem;
import com.yahoo.prelude.query.Highlight;
import com.yahoo.prelude.query.IndexedItem;
import com.yahoo.prelude.query.IntItem;
import com.yahoo.prelude.query.Item;
import com.yahoo.prelude.query.OrItem;
import com.yahoo.prelude.query.RankItem;
import com.yahoo.prelude.query.WordItem;
import com.yahoo.processing.request.CompoundName;
import com.yahoo.search.Query;
import com.yahoo.search.Result;
import com.yahoo.search.Searcher;
import com.yahoo.search.grouping.GroupingQueryParser;
import com.yahoo.search.query.QueryTree;
import com.yahoo.search.query.SessionId;
import com.yahoo.search.query.profile.DimensionValues;
import com.yahoo.search.query.profile.QueryProfile;
import com.yahoo.search.query.profile.QueryProfileRegistry;
import com.yahoo.search.query.profile.compiled.CompiledQueryProfileRegistry;
import com.yahoo.search.query.profile.types.FieldDescription;
import com.yahoo.search.query.profile.types.QueryProfileType;
import com.yahoo.search.result.Hit;
import com.yahoo.search.searchchain.Execution;
import com.yahoo.yolean.Exceptions;
import org.json.JSONObject;
import org.junit.Ignore;
import org.junit.Test;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @author bratseth
 */
public class QueryTestCase {

    @Test
    public void testIt() throws Exception {
        JSONObject newroot = new JSONObject("{\"key\": 3}");
        var hit = new FastHit();
        hit.setField("data", (JsonProducer)s -> s.append(newroot));
        var field = hit.getField("data");
        if (field instanceof JsonProducer) {
            System.out.println((((JsonProducer) field).toJson()));
        }
    }

    @Test
    public void testSimpleFunctionality() {
        Query q = new Query(QueryTestCase.httpEncode("/sdfsd.html?query=this is a simple query&aParameter"));
        assertEquals("this is a simple query", q.getModel().getQueryString());
        assertNotNull(q.getModel().getQueryTree());
        assertNull(q.getModel().getDefaultIndex());
        assertEquals("", q.properties().get("aParameter"));
        assertNull(q.properties().get("notSetParameter"));

        String body = "a bb. ccc??!";
        Linguistics linguistics = new SimpleLinguistics();

        AndItem and = new AndItem();
        for (Token token : linguistics.getTokenizer().tokenize(body, Language.ENGLISH, StemMode.SHORTEST, true)) {
            if (token.isIndexable())
                and.addItem(new WordItem(token.getTokenString(), "body"));
        }
        q.getModel().getQueryTree().setRoot(and);
    }

    // TODO: YQL work in progress (jon)
    @Ignore
    @Test
    public void testSimpleProgram() {
        Query q = new Query(httpEncode("?program=select * from * where myfield contains(word)"));
        assertEquals("", q.getModel().getQueryTree().toString());
    }

    // TODO: YQL work in progress (jon)
    @Ignore
    @Test
    public void testSimpleProgramParameterAlias() {
        Query q = new Query(httpEncode("/sdfsd.html?yql=select * from source where myfield contains(word)"));
        assertEquals("", q.getModel().getQueryTree().toString());
    }

    @Test
    public void testClone() {
        Query q = new Query(httpEncode("/sdfsd.html?query=this+is+a+simple+query&aParameter"));
        q.getPresentation().setHighlight(new Highlight());
        Query p = q.clone();
        assertEquals(q, p);
        assertEquals(q.hashCode(), p.hashCode());

        // Make sure we deep clone all mutable objects

        assertNotSame(q, p);
        assertNotSame(q.getRanking(), p.getRanking());
        assertNotSame(q.getRanking().getFeatures(), p.getRanking().getFeatures());
        assertNotSame(q.getRanking().getProperties(), p.getRanking().getProperties());
        assertNotSame(q.getRanking().getMatchPhase(), p.getRanking().getMatchPhase());
        assertNotSame(q.getRanking().getMatchPhase().getDiversity(), p.getRanking().getMatchPhase().getDiversity());
        assertNotSame(q.getRanking().getSoftTimeout(), p.getRanking().getSoftTimeout());

        assertNotSame(q.getPresentation(), p.getPresentation());
        assertNotSame(q.getPresentation().getHighlight(), p.getPresentation().getHighlight());
        assertNotSame(q.getPresentation().getSummaryFields(), p.getPresentation().getSummaryFields());

        assertNotSame(q.getModel(), p.getModel());
        assertNotSame(q.getModel().getSources(), p.getModel().getSources());
        assertNotSame(q.getModel().getRestrict(), p.getModel().getRestrict());
        assertNotSame(q.getModel().getQueryTree(), p.getModel().getQueryTree());
    }

    private boolean isA(String s) {
        return (s.equals("a"));
    }

    private void printIt(List<String> l) {
        System.out.println(l);
    }

    @Test
    public void testCloneWithConnectivity() {
        List<String> l = List.of("a", "b", "c", "a");
        printIt(l.stream().filter(i -> isA(i)).collect(Collectors.toList()));
        printIt(l.stream().filter(i -> ! isA(i)).collect(Collectors.toList()));

        Query q = new Query();
        WordItem a = new WordItem("a");
        WordItem b = new WordItem("b");
        WordItem c = new WordItem("c");
        WordItem d = new WordItem("d");
        WordItem e = new WordItem("e");
        WordItem f = new WordItem("f");
        WordItem g = new WordItem("g");

        OrItem or = new OrItem();
        or.addItem(c);
        or.addItem(d);

        AndItem and1 = new AndItem();
        and1.addItem(a);
        and1.addItem(b);
        and1.addItem(or);
        and1.addItem(e);

        AndItem and2 = new AndItem();
        and2.addItem(f);
        and2.addItem(g);

        RankItem rank = new RankItem();
        rank.addItem(and1);
        rank.addItem(and2);

        a.setConnectivity(b, 0.1);
        b.setConnectivity(c, 0.2);
        c.setConnectivity(d, 0.3);
        d.setConnectivity(e, 0.4);
        e.setConnectivity(f, 0.5);
        f.setConnectivity(g, 0.6);

        q.getModel().getQueryTree().setRoot(rank);
        Query qClone = q.clone();
        assertEquals(q, qClone);

        RankItem rankClone = (RankItem)qClone.getModel().getQueryTree().getRoot();
        AndItem and1Clone = (AndItem)rankClone.getItem(0);
        AndItem and2Clone = (AndItem)rankClone.getItem(1);
        OrItem orClone = (OrItem)and1Clone.getItem(2);

        WordItem aClone = (WordItem)and1Clone.getItem(0);
        WordItem bClone = (WordItem)and1Clone.getItem(1);
        WordItem cClone = (WordItem)orClone.getItem(0);
        WordItem dClone = (WordItem)orClone.getItem(1);
        WordItem eClone = (WordItem)and1Clone.getItem(3);
        WordItem fClone = (WordItem)and2Clone.getItem(0);
        WordItem gClone = (WordItem)and2Clone.getItem(1);

        assertTrue(rankClone != rank);
        assertTrue(and1Clone != and1);
        assertTrue(and2Clone != and2);
        assertTrue(orClone != or);

        assertTrue(aClone != a);
        assertTrue(bClone != b);
        assertTrue(cClone != c);
        assertTrue(dClone != d);
        assertTrue(eClone != e);
        assertTrue(fClone != f);
        assertTrue(gClone != g);

        assertTrue(aClone.getConnectedItem() == bClone);
        assertTrue(bClone.getConnectedItem() == cClone);
        assertTrue(cClone.getConnectedItem() == dClone);
        assertTrue(dClone.getConnectedItem() == eClone);
        assertTrue(eClone.getConnectedItem() == fClone);
        assertTrue(fClone.getConnectedItem() == gClone);

        double delta = 0.0000001;
        assertEquals(0.1, aClone.getConnectivity(), delta);
        assertEquals(0.2, bClone.getConnectivity(), delta);
        assertEquals(0.3, cClone.getConnectivity(), delta);
        assertEquals(0.4, dClone.getConnectivity(), delta);
        assertEquals(0.5, eClone.getConnectivity(), delta);
        assertEquals(0.6, fClone.getConnectivity(), delta);
    }

    @Test
    public void test_that_cloning_preserves_timeout() {
        Query original = new Query();
        original.setTimeout(9876L);

        Query clone = original.clone();
        assertEquals(9876L, clone.getTimeout());
    }

    @Test
    public void testTimeout() {
        // yes, this test depends on numbers which have exact IEEE representations
        Query q = new Query(httpEncode("/search?timeout=500"));
        assertEquals(500000L, q.getTimeout());
        assertEquals(0, q.errors().size());

        q = new Query(httpEncode("/search?timeout=500 ms"));
        assertEquals(500, q.getTimeout());
        assertEquals(0, q.errors().size());

        q = new Query(httpEncode("/search?timeout=500.0ms"));
        assertEquals(500, q.getTimeout());
        assertEquals(0, q.errors().size());

        q = new Query(httpEncode("/search?timeout=500.0s"));
        assertEquals(500000, q.getTimeout());
        assertEquals(0, q.errors().size());

        q = new Query(httpEncode("/search?timeout=5ks"));
        assertEquals(5000000, q.getTimeout());
        assertEquals(0, q.errors().size());

        q = new Query(httpEncode("/search?timeout=5000.0 \u00B5s"));
        assertEquals(5, q.getTimeout());
        assertEquals(0, q.errors().size());

        // seconds is unit when unknown unit
        q = new Query(httpEncode("/search?timeout=42 yrs"));
        assertEquals(42000, q.getTimeout());
        assertEquals(0, q.errors().size());

        q=new Query();
        q.setTimeout(53L);
        assertEquals(53L, q.properties().get("timeout"));
        assertEquals(53L, q.getTimeout());

        // This is the unfortunate consequence of this legacy:
        q=new Query();
        q.properties().set("timeout", 53L);
        assertEquals(53L * 1000, q.properties().get("timeout"));
        assertEquals(53L * 1000, q.getTimeout());
    }

    @Test
    public void testUnparseableTimeout() {
        try {
            new Query(httpEncode("/search?timeout=nalle"));
            fail("Above statement should throw");
        } catch (IllegalArgumentException e) {
            // As expected.
            assertTrue(Exceptions.toMessageString(e).contains("Could not set 'timeout' to 'nalle': Error parsing 'nalle': Invalid number 'nalle'"));
        }
    }

    @Test
    public void testCloneTimeout() {
        Query q = new Query(httpEncode("/search?timeout=300ms"));
        assertEquals(300, q.getTimeout());
        Query clonedQ = q.clone();
        assertEquals(300, clonedQ.getTimeout());
    }

    @Test
    public void testQueryProfileSubstitution1() {
        QueryProfile profile = new QueryProfile("myProfile");
        profile.set("myField", "Profile: %{queryProfile}", null);
        Query q = new Query(QueryTestCase.httpEncode("/search?queryProfile=myProfile"), profile.compile(null));
        assertEquals("Profile: myProfile", q.properties().get("myField"));
    }

    @Test
    public void testQueryProfileSourceAccess() {
        QueryProfile profile = new QueryProfile("myProfile");
        profile.set("myField", "Profile: %{queryProfile}", null);
        Query query = new Query(QueryTestCase.httpEncode("/search?queryProfile=myProfile"), profile.compile(null));

        String source = query.properties().getInstance(com.yahoo.search.query.profile.QueryProfileProperties.class).getQueryProfile().listValuesWithSources(new CompoundName(""), query.getHttpRequest().propertyMap(), query.properties()).get("myField").source();
        assertEquals("myProfile", source);
    }

    @Test
    public void testBooleanParameter() {
        QueryProfile profile = new QueryProfile("myProfile");
        Query query = new Query("/?query=something&ranking.softtimeout.enable=false", profile.compile(null));
        assertFalse(query.properties().getBoolean("ranking.softtimeout.enable"));
        assertFalse(query.getRanking().getSoftTimeout().getEnable());
    }

    @Test
    public void testQueryProfileSubstitution2() {
        QueryProfile profile = new QueryProfile("myProfile");
        profile.set("model.language", "en-US", null);
        profile.set("myField", "Language: %{lang}", null);
        Query q = new Query(QueryTestCase.httpEncode("/search?queryProfile=myProfile"), profile.compile(null));
        assertEquals("Language: ENGLISH", q.properties().get("myField"));
    }

    @Test
    public void testQueryProfileSubstitution3() {
        QueryProfile profile = new QueryProfile("myProfile");
        profile.set("model.locale", "en-US", null);
        profile.set("myField", "Language: %{lang}, locale: %{locale}", null);
        Query q = new Query(QueryTestCase.httpEncode("/search?queryProfile=myProfile"), profile.compile(null));
        assertEquals("Language: ENGLISH, locale: en_US", q.properties().get("myField"));
    }

    @Test
    public void testTimeoutInRequestOverridesQueryProfile() {
        QueryProfile profile = new QueryProfile("test");
        profile.set("timeout", 318, (QueryProfileRegistry)null);
        Query q = new Query(QueryTestCase.httpEncode("/search?timeout=500"), profile.compile(null));
        assertEquals(500000L, q.getTimeout());
    }

    @Test
    public void testQueryProfileClearAndSet() {
        QueryProfile profile = new QueryProfile("myProfile");
        profile.set("b", "b-value", null);
        Query q = new Query(QueryTestCase.httpEncode("/search?queryProfile=myProfile"), profile.compile(null));
        assertEquals("b-value", q.properties().get("b"));
        assertContains(q.properties().listProperties("b"), "b-value");

        q.properties().set("b", null, null);
        assertContains(q.properties().listProperties("b"), (Object)null);

        q.properties().set("b", "b-value", null);
        assertEquals("b-value", q.properties().get("b"));
        assertContains(q.properties().listProperties("b"), "b-value");
    }

    @Test
    public void testQueryProfileClearValue() {
        QueryProfile profile = new QueryProfile("myProfile");
        profile.set("a", "a-value", null);
        profile.set("b", "b-value", null);
        profile.set("b.c", "b.c-value", null);
        profile.set("b.d", "b.d-value", null);
        Query q = new Query(QueryTestCase.httpEncode("/search?queryProfile=myProfile"), profile.compile(null));
        assertEquals("a-value", q.properties().get("a"));
        assertEquals("b-value", q.properties().get("b"));
        assertEquals("b.c-value", q.properties().get("b.c"));
        assertEquals("b.d-value", q.properties().get("b.d"));
        assertContains(q.properties().listProperties("b"), "b-value", "b.c-value", "b.d-value");

        q.properties().set("a", null, null);
        assertEquals(null, q.properties().get("a"));

        q.properties().set("b", null, null);
        assertEquals(null, q.properties().get("b"));
        assertEquals("b.c-value", q.properties().get("b.c"));
        assertEquals("b.d-value", q.properties().get("b.d"));
        assertContains(q.properties().listProperties("b"), null, "b.c-value", "b.d-value");

        q.properties().set("b", "b-value", null);
        q.properties().set("b.e", "b.e-value", null);
        q.properties().set("b.f", "b.f-value", null);
        assertEquals("b-value", q.properties().get("b"));
        assertEquals("b.e-value", q.properties().get("b.e"));
        assertContains(q.properties().listProperties("b"), "b-value", "b.c-value", "b.d-value", "b.e-value", "b.f-value");

        q.properties().clearAll("b");
        assertEquals(null, q.properties().get("b"));
        assertEquals(null, q.properties().get("b.c"));
        assertEquals(null, q.properties().get("b.d"));
        assertEquals(null, q.properties().get("b.e"));
        assertEquals(null, q.properties().get("b.f"));
        assertContains(q.properties().listProperties("b"), (Object)null);
    }

    @Test
    public void testNotEqual() {
        Query q = new Query("/?query=something+test&nocache");
        Query p = new Query("/?query=something+test");
        assertEquals(q,p);
        assertEquals(q.hashCode(),p.hashCode());
        Query r = new Query("?query=something+test&hits=5");
        assertNotSame(q,r);
        assertNotSame(q.hashCode(),r.hashCode());
    }

    @Test
    public void testEqual() {
        assertEquals(new Query("?query=12").hashCode(),new Query("?query=12").hashCode());
        assertEquals(new Query("?query=12"),new Query("?query=12"));
    }

    @Test
    public void testUtf8Decoding() {
        Query q = new Query("/?query=beyonc%C3%A9");
        assertEquals("beyonc\u00e9", q.getModel().getQueryTree().toString());
    }

    @Test
    public void testQueryProfileInSubstitution() {
        QueryProfile testProfile = new QueryProfile("test");
        testProfile.setOverridable("u", false, DimensionValues.empty);
        testProfile.set("d","e", null);
        testProfile.set("u","11", null);
        testProfile.set("foo.bar", "wiz", null);
        Query q = new Query(QueryTestCase.httpEncode("?query=a:>5&a=b&traceLevel=5&sources=a,b&u=12&foo.bar2=wiz2&c.d=foo&queryProfile=test"),testProfile.compile(null));
        String trace = q.getContext(false).getTrace().toString();
        String[] traceLines = trace.split("\n");
    }

    @Test
    public void testDefaultIndex() {
        Query q = new Query("?query=hi%20hello%20keyword:kanoo%20" +
                            "default:munkz%20%22phrases+too%22&default-index=def");
        assertEquals("AND def:hi def:hello keyword:kanoo default:munkz def:\"phrases too\"",
                     q.getModel().getQueryTree().toString());
    }

    @Test
    public void testHashCode() {
        Query p = new Query("?query=foo&type=any");
        Query q = new Query("?query=foo&type=all");
        assertTrue(p.hashCode() != q.hashCode());
    }

    @Test
    public void testSimpleQueryParsing () {
        Query q = new Query("/search?query=foobar&offset=10&hits=20");
        assertEquals("foobar",q.getModel().getQueryTree().toString());
        assertEquals(10,q.getOffset());
        assertEquals(20,q.getHits());
    }

    /** Test that GET parameter names are case in-sensitive */
    @Test
    public void testGETParametersCase() {
        Query q = new Query("?QUERY=testing&hits=10&oFfSeT=10");
        assertEquals("testing", q.getModel().getQueryString());
        assertEquals(10, q.getHits());
        assertEquals(10, q.getOffset());
    }

    /** Test that we get the last value if a parameter is assigned multiple times */
    @Test
    public void testRepeatedParameter() {
        Query q = new Query("?query=test&hits=5&hits=10");
        assertEquals(10, q.getHits());
    }

    @Test
    public void testNoCache() {
        Query q = new Query("search?query=foobar&nocache");
        assertTrue(q.getNoCache());
    }

    @Test
    public void testSessionCache() {
        Query q = new Query("search?query=foobar&groupingSessionCache");
        assertTrue(q.getGroupingSessionCache());
        q = new Query("search?query=foobar");
        assertTrue(q.getGroupingSessionCache());
    }

    public static class TestClass {

        private int testInt = 0;

        public int getTestInt() { return testInt; }

        public void setTestInt(int testInt) { this.testInt = testInt; }

        public void setTestInt(String testInt) { this.testInt = Integer.parseInt(testInt); }

    }

    @Test
    public void testSetting() {
        Query q = new Query();
        q.properties().set("test", "test");
        assertEquals(q.properties().get("test"), "test");

        TestClass tc = new TestClass();
        q.properties().set("test", tc);
        assertEquals(q.properties().get("test"), tc);
        q.properties().set("test.testInt", 1);
        assertEquals(q.properties().get("test.testInt"), 1);
    }

    @Test
    public void testAlias() {
        Query q = new Query("search?query=testing&language=en");
        assertEquals(q.getModel().getLanguage(), q.properties().get("model.language"));
    }

    @Test
    public void testTracing() {
        Query q = new Query("?query=foo&traceLevel=2");
        assertEquals(2, q.getTraceLevel());
        q.trace(true, 1, "trace1");
        q.trace(false,2, "trace2");
        q.trace(true, 3, "Ignored");
        q.trace(true, 2, "trace3-1", ", ", "trace3-2");
        q.trace(false,1, "trace4-1", ", ", "trace4-2");
        q.trace(false,3, "Ignored-1", "Ignored-2");
        Set<String> traces = new HashSet<>();
        for (String trace : q.getContext(true).getTrace().traceNode().descendants(String.class))
            traces.add(trace);
        assertTrue(traces.contains("trace1: [select * from sources * where default contains \"foo\";]"));
        assertTrue(traces.contains("trace2"));
        assertTrue(traces.contains("trace3-1, trace3-2: [select * from sources * where default contains \"foo\";]"));
        assertTrue(traces.contains("trace4-1, trace4-2"));
    }

    @Test
    public void testNullTracing() {
        Query q = new Query("?query=foo&traceLevel=2");
        assertEquals(2, q.getTraceLevel());
        q.trace(false,2, "trace2 ", null);
        Set<String> traces = new HashSet<>();
        for (String trace : q.getContext(true).getTrace().traceNode().descendants(String.class))
            traces.add(trace);
        assertTrue(traces.contains("trace2 null"));
    }

    @Test
    public void testExplain() {
        Query q = new Query("?query=foo&explainLevel=2");
        assertEquals(2, q.getExplainLevel());
        assertEquals(0, q.getTraceLevel());
    }

    @Test
    public void testQueryPropertyResolveTracing() {
        QueryProfile testProfile = new QueryProfile("test");
        testProfile.setOverridable("u", false, DimensionValues.empty);
        testProfile.set("d","e", null);
        testProfile.set("u","11", null);
        testProfile.set("foo.bar", "wiz", null);
        Query q = new Query(QueryTestCase.httpEncode("?query=a:>5&a=b&traceLevel=5&sources=a,b&u=12&foo.bar2=wiz2&c.d=foo&queryProfile=test"),testProfile.compile(null));
        String trace = q.getContext(false).getTrace().toString();
        String[] traceLines = trace.split("\n");
        assertTrue(contains("query: a:>5 (from request)", traceLines));
        assertTrue(contains("traceLevel: 5 (from request)", traceLines));
        assertTrue(contains("a: b (from request)", traceLines));
        assertTrue(contains("sources: [a, b] (from request)", traceLines));
        assertTrue(contains("d: e (from query profile 'test')", traceLines));
        assertTrue(contains("u: 11 (from query profile - unoverridable, ignoring request value)", traceLines));
    }

    @Test
    public void testNonleafInRequestDoesNotOverrideProfile() {
        QueryProfile testProfile = new QueryProfile("test");
        testProfile.set("a.b", "foo", null);
        testProfile.freeze();
        {
            Query q = new Query("?", testProfile.compile(null));
            assertEquals("foo", q.properties().get("a.b"));
        }

        {
            Query q = new Query("?a=bar", testProfile.compile(null));
            assertEquals("bar", q.properties().get("a"));
            assertEquals("foo", q.properties().get("a.b"));
        }
    }

    @Test
    public void testQueryPropertyResolveTracing2() {
        QueryProfile defaultProfile = new QueryProfile("default");
        defaultProfile.freeze();
        Query q = new Query(QueryTestCase.httpEncode("?query=dvd&a.b=foo&tracelevel=9"), defaultProfile.compile(null));
        String trace = q.getContext(false).getTrace().toString();
        String[] traceLines = trace.split("\n");
        assertTrue(contains("query: dvd (from request)", traceLines));
        assertTrue(contains("a.b: foo (from request)", traceLines));
    }

    @Test
    public void testNativeProperties() {
        Set<String> nativeProperties = Query.nativeProperties.stream().map(CompoundName::toString).collect(Collectors.toSet());
        // Sample the content
        assertTrue(nativeProperties.contains("hits"));
        assertTrue(nativeProperties.contains("model.sources"));
        assertTrue(nativeProperties.contains("ranking.matchPhase.attribute"));
    }

    @Test
    public void testQueryPropertyListingAndTrace() {
        QueryProfile defaultProfile = new QueryProfile("default");
        defaultProfile.setDimensions(new String[]{"x"});
        defaultProfile.set("a.b","a.b-x1-value", new String[] {"x1"}, null);
        defaultProfile.set("a.b", "a.b-x2-value", new String[]{"x2"}, null);
        defaultProfile.freeze();

        {
            Query q = new Query(QueryTestCase.httpEncode("?tracelevel=9&x=x1"),defaultProfile.compile(null));
            Map<String,Object> propertyList = q.properties().listProperties();
            assertEquals("a.b-x1-value", propertyList.get("a.b"));
            String trace = q.getContext(false).getTrace().toString();
            String[] traceLines = trace.split("\n");
            assertTrue(contains("a.b: a.b-x1-value (from query profile 'default' variant [x1])", traceLines));
        }

        {
            Query q = new Query(QueryTestCase.httpEncode("?tracelevel=9&x=x1"), defaultProfile.compile(null));
            Map<String,Object> propertyList = q.properties().listProperties("a");
            assertEquals(1, propertyList.size());
            assertEquals("a.b-x1-value", propertyList.get("b"));
        }

        {
            Query q = new Query(QueryTestCase.httpEncode("?tracelevel=9&x=x2"),defaultProfile.compile(null));
            Map<String,Object> propertyList = q.properties().listProperties();
            assertEquals("a.b-x2-value", propertyList.get("a.b"));
            String trace = q.getContext(false).getTrace().toString();
            String[] traceLines = trace.split("\n");
            assertTrue(contains("a.b: a.b-x2-value (from query profile 'default' variant [x2])", traceLines));
        }
    }

    @Test
    public void testQueryPropertyListingThreeLevel() {
        QueryProfile defaultProfile = new QueryProfile("default");
        defaultProfile.setDimensions(new String[] {"x"});
        defaultProfile.set("a.b.c", "a.b.c-x1-value", new String[]{"x1"}, null);
        defaultProfile.set("a.b.c", "a.b.c-x2-value", new String[]{"x2"}, null);
        defaultProfile.freeze();

        {
            Query q = new Query(QueryTestCase.httpEncode("?tracelevel=9&x=x1"),defaultProfile.compile(null));
            Map<String,Object> propertyList = q.properties().listProperties();
            assertEquals("a.b.c-x1-value", propertyList.get("a.b.c"));
        }

        {
            Query q = new Query(QueryTestCase.httpEncode("?tracelevel=9&x=x1"),defaultProfile.compile(null));
            Map<String,Object> propertyList = q.properties().listProperties("a");
            assertEquals(1, propertyList.size());
            assertEquals("a.b.c-x1-value", propertyList.get("b.c"));
        }

        {
            Query q = new Query(QueryTestCase.httpEncode("?tracelevel=9&x=x1"),defaultProfile.compile(null));
            Map<String,Object> propertyList = q.properties().listProperties("a.b");
            assertEquals(1, propertyList.size());
            assertEquals("a.b.c-x1-value", propertyList.get("c"));
        }

        {
            Query q = new Query(QueryTestCase.httpEncode("?tracelevel=9&x=x2"),defaultProfile.compile(null));
            Map<String,Object> propertyList = q.properties().listProperties();
            assertEquals("a.b.c-x2-value", propertyList.get("a.b.c"));
        }
    }

    @Test
    public void testQueryPropertyReplacement() {
        QueryProfile defaultProfile = new QueryProfile("default");
        defaultProfile.set("model.queryString","myquery", null);
        defaultProfile.set("queryUrl","http://provider:80?query=%{model.queryString}", null);
        defaultProfile.freeze();

        Query q1 = new Query(QueryTestCase.httpEncode(""), defaultProfile.compile(null));
        assertEquals("myquery", q1.getModel().getQueryString());
        assertEquals("http://provider:80?query=myquery", q1.properties().get("queryUrl"));

        Query q2 = new Query(QueryTestCase.httpEncode("?model.queryString=foo"), defaultProfile.compile(null));
        assertEquals("foo", q2.getModel().getQueryString());
        assertEquals("http://provider:80?query=foo", q2.properties().get("queryUrl"));

        Query q3 = new Query(QueryTestCase.httpEncode("?query=foo"),defaultProfile.compile(null));
        assertEquals("foo",q3.getModel().getQueryString());
        assertEquals("http://provider:80?query=foo",q3.properties().get("queryUrl"));

        Query q4 = new Query(QueryTestCase.httpEncode("?query=foo"),defaultProfile.compile(null));
        q4.getModel().setQueryString("bar");
        assertEquals("http://provider:80?query=bar",q4.properties().get("queryUrl"));
    }

    @Test
    public void testNoQueryString() {
        Query q = new Query(httpEncode("?tracelevel=1"));
        Chain<Searcher> chain = new Chain<>(new RandomSearcher());
        new Execution(chain, Execution.Context.createContextStub()).search(q);
        assertNotNull(q.getModel().getQueryString());
    }

    @Test
    public void testSetCollapseField() {
        Query q = new Query(httpEncode("?collapsefield=foo&presentation.format=tiled"));
        assertEquals("foo", q.properties().get("collapsefield"));
        assertEquals("tiled", q.properties().get("presentation.format"));
        assertEquals("tiled", q.getPresentation().getFormat());
    }

    @Test
    public void testSetNullProperty() {
        QueryProfile profile = new QueryProfile("test");
        profile.set("property","initialValue", null);
        Query query = new Query(httpEncode("?query=test"), profile.compile(null));
        assertEquals("initialValue",query.properties().get("property"));
        query.properties().set("property", null);
        assertNull(query.properties().get("property"));
    }

    @Test
    public void testSetNullPropertyNoQueryProfile() {
        Query query = new Query();
        query.properties().set("a", null);
        assertNull(query.properties().get("a"));
    }

    @Test
    public void testMissingParameter() {
        Query q = new Query("?query=foo&hits=");
        assertEquals(0, q.errors().size());
    }

    @Test
    public void testModelProperties() {
        {
            Query query = new Query();
            query.properties().set("model.searchPath", "foo");
            assertEquals("Set dynamic get dynamic works","foo", query.properties().get("model.searchPath"));
            assertEquals("Set dynamic get static works","foo", query.getModel().getSearchPath());
            Map<String, Object> properties = query.properties().listProperties();
            assertEquals("Listing built-in properties works", "foo", properties.get("model.searchPath"));
        }

        {
            Query query = new Query();
            query.getModel().setSearchPath("foo");
            assertEquals("Set static get dynamic works","foo", query.properties().get("model.searchPath"));
            assertEquals("Set static get static works","foo", query.getModel().getSearchPath());
        }

        {
            Query query = new Query();
            query.properties().set("a", "bar");
            assertEquals("bar", query.properties().get("a"));
            query.properties().set("a.b", "baz");
            assertEquals("baz", query.properties().get("a.b"));
        }
    }

    @Test
    public void testThatSessionIdIsUniquePerQuery() {
        Query q = new Query();
        assertNull(q.getSessionId());
        assertNull(q.getSessionId());
        SessionId s1 = q.getSessionId("node-0");
        assertNotNull(s1);
        SessionId s2 = q.getSessionId("node-0");
        assertNotSame(s1, s2);
        assertEquals(s1, s2);
        assertEquals(s1.toString(), s2.toString());

        Query q2 = new Query();
        assertNotEquals(q.getSessionId(), q2.getSessionId("node-0"));
        assertNotEquals(q.getSessionId().toString(), q2.getSessionId("node-0").toString());
    }
    @Test
    public void testThatCloneGetANewSessionId() {
        Query q = new Query();
        q.getSessionId("node-0");
        Query clonedQ = q.clone();
        assertNull(clonedQ.getSessionId());
        assertNotEquals(q.getSessionId(), clonedQ.getSessionId("node-0"));
    }

    @Test
    public void testThatSessionIdIsUniquePerRankProfilePerQuery() {
        Query q = new Query();
        SessionId s1 = q.getSessionId("node-0");
        q.getRanking().setProfile("my-profile");
        SessionId s2 = q.getSessionId();
        assertNotEquals(s1, s2);
    }

    @Test
    public void testThatSessionIdIsNotSharedIfCreatedAfterClone() {
        Query q = new Query();
        Query q2 = q.clone();
        assertNull(q.getSessionId());
        assertNull(q2.getSessionId());

        assertNotNull(q.getSessionId("node-0"));
        assertNull(q2.getSessionId());

        assertNotNull(q2.getSessionId("node-0"));
        assertNotEquals(q.getSessionId(), q2.getSessionId());
    }

    @Test
    public void testPositiveTerms() {
        Query q = new Query(httpEncode("/?query=-a \"b c\" d e"));
        Item i = q.getModel().getQueryTree().getRoot();
        List<IndexedItem> l = QueryTree.getPositiveTerms(i);
        assertEquals(3, l.size());
    }

    @Test
    public void testHeuristicLanguageDetectionTextExtraction() {
        assertDetectionText("b ", "a:b", "text:a", "text:default");
        assertDetectionText("b ", "b", "text:default");
        assertDetectionText("b ", "b","text:b", "text:default");
        assertDetectionText("a b ", "a:b","text:b", "text:default");
        assertDetectionText("foo bar fuz ", "foo a:bar --() fuz","text:a", "text:default");
        assertDetectionText(" 彭 博士 觀 風向  彭 博士 觀 風向  彭 博士 觀 風向 ","headline:\"彭 博士 觀 風向\" content:\"彭 博士 觀 風向\" description:\"彭 博士 觀 風向\" sddocname:contentindexing!0 embargo:<1484665288753!0 expires:>1484665288753!0",
                            "text:headline", "text:content", "text:description", "text:default", "nontext:tags", "nontext:sddocname", "nontext:embargo", "nontext:expires");
    }

    @Test
    public void testCompositeChildVerification() {
        CompositeItem root = new AndItem();
        try {
            root.addItem(null);
            fail("Expected exception");
        }
        catch (NullPointerException e) {
            assertEquals("A composite item child can not be null", e.getMessage());
        }

        try {
            root.addItem(root);
            fail("Expected exception");
        }
        catch (IllegalArgumentException e) {
            assertEquals("Attempted to add a composite to itself", e.getMessage());
        }

        try {
            OrItem child = new OrItem();
            child.addItem(root);
            root.addItem(child);
            fail("Expected exception");
        }
        catch (IllegalArgumentException e) {
            assertEquals("Cannot add OR (AND ) to (AND ) as it would create a cycle", e.getMessage());
        }

        try {
            OrItem child = new OrItem();
            root.addItem(child);
            child.addItem(root);
            fail("Expected exception");
        }
        catch (IllegalArgumentException e) {
            assertEquals("Cannot add (AND (OR )) to (OR ) as it would create a cycle", e.getMessage());
        }
    }

    @Test
    public void queryLanguageAlternatives() {
        // Given:
        // Person = {
        //  Name: 'Joe',
        //  Hobbies: ['sports','books','bonzais'],
        //  Phones: [{Number: '12-3456-7890', areaCode: 'NY'},{Number: '22-3456-7890', areaCode: 'CA'}],
        //  Mother: {
        //    Name: 'Mom',
        //    Birthyear: '1961'
        //  }
        //}

        { // Select all Persons whose hobbies contains 'sport'
            // YQL
            Query yqlQuery = new Query(httpEncode("?query=select * from Persons where hobbies contains 'sports'&type=yql"));
            assertEquals("hobbies:sports", yqlQuery.getModel().getQueryTree().toString());

            // JSON
            Query jsonQuery = new Query(httpEncode("?select.where={\"contains\" : [ \"hobbies\", \"sports\" ]}&type=select"));
            assertEquals("hobbies:sports", jsonQuery.getModel().getQueryTree().toString());

            // Programmatically
            Query query = new Query();
            query.getModel().getQueryTree().setRoot(new WordItem("sports", "hobbies"));
            assertEquals("hobbies:sports", query.getModel().getQueryTree().toString());
        }

        { // Select all Persons whose Phones areaCode equals 'NY'
            // YQL
            Query yqlQuery = new Query(httpEncode("?query=select * from Persons where phones.areaCode contains 'NY'&type=yql"));
            assertEquals("phones.areaCode:NY", yqlQuery.getModel().getQueryTree().toString());

            // JSON
            Query jsonQuery = new Query(httpEncode("?select.where={\"contains\" : [ \"phones.areaCode\", \"NY\" ]}&type=select"));
            assertEquals("phones.areaCode:NY", jsonQuery.getModel().getQueryTree().toString());

            // Programmatically
            Query query = new Query();
            query.getModel().getQueryTree().setRoot(new WordItem("NY", "phones.areaCode"));
            assertEquals("phones.areaCode:NY", query.getModel().getQueryTree().toString());
        }

        { // Select all Persons whose Mother's Birthyear is greater than 1960
            // YQL
            Query yqlQuery = new Query(httpEncode("?query=select * from Persons where mother.Birthyear > 1960&type=yql"));
            assertEquals("mother.Birthyear:>1960", yqlQuery.getModel().getQueryTree().toString());

            // JSON
            Query jsonQuery = new Query(httpEncode("?select.where={\"range\" : [ \"mother.Birthyear\", { \">\": 1960}]}&type=select"));
            assertEquals("mother.Birthyear:>1960", jsonQuery.getModel().getQueryTree().toString());

            // Programmatically
            Query query = new Query();
            query.getModel().getQueryTree().setRoot(new IntItem(">1960", "mother.Birthyear"));
            assertEquals("mother.Birthyear:>1960", query.getModel().getQueryTree().toString());
        }
    }

    @Test
    public void testImplicitPhraseIsDefault() {
        Query query = new Query(httpEncode("?query=it's fine"));
        assertEquals("AND (SAND it s) fine", query.getModel().getQueryTree().toString());
    }

    @Test
    public void testImplicitPhrase() {
        Query query = new Query(httpEncode("?query=myfield:it's myfield:a.b myfield:c"));

        SearchDefinition test = new SearchDefinition("test");
        Index myField = new Index("myfield");
        myField.addCommand("phrase-segmenting true");
        assertTrue(myField.getPhraseSegmenting());
        test.addIndex(myField);
        IndexModel indexModel = new IndexModel(test);
        query.getModel().setExecution(new Execution(Execution.Context.createContextStub(new IndexFacts(indexModel))));

        assertEquals("AND myfield:'it s' myfield:\"a b\" myfield:c", query.getModel().getQueryTree().toString());
    }

    @Test
    public void testImplicitAnd() {
        Query query = new Query(httpEncode("?query=myfield:it's myfield:a.b myfield:c"));

        SearchDefinition test = new SearchDefinition("test");
        Index myField = new Index("myfield");
        myField.addCommand("phrase-segmenting false");
        assertFalse(myField.getPhraseSegmenting());
        test.addIndex(myField);
        IndexModel indexModel = new IndexModel(test);
        query.getModel().setExecution(new Execution(Execution.Context.createContextStub(new IndexFacts(indexModel))));

        assertEquals("AND (SAND myfield:it myfield:s) myfield:a myfield:b myfield:c", query.getModel().getQueryTree().toString());
        // 'it' and 's' should have connectivity 1
        AndItem root = (AndItem)query.getModel().getQueryTree().getRoot();
        AndSegmentItem sand = (AndSegmentItem)root.getItem(0);
        WordItem it = (WordItem)sand.getItem(0);
        assertEquals("it", it.getWord());
        WordItem s = (WordItem)sand.getItem(1);
        assertEquals("s", s.getWord());
        assertEquals(s, it.getConnectedItem());
        assertEquals(1.0, it.getConnectivity(), 0.00000001);
    }

    @Test
    public void testImplicitAndConnectivity() {
        SearchDefinition test = new SearchDefinition("test");
        Index myField = new Index("myfield");
        myField.addCommand("phrase-segmenting false");
        test.addIndex(myField);
        IndexModel indexModel = new IndexModel(test);

        {
            Query query = new Query(httpEncode("?query=myfield:b.c.d"));
            query.getModel().setExecution(new Execution(Execution.Context.createContextStub(new IndexFacts(indexModel))));
            assertEquals("AND myfield:b myfield:c myfield:d", query.getModel().getQueryTree().toString());
            AndItem root = (AndItem) query.getModel().getQueryTree().getRoot();
            WordItem b = (WordItem) root.getItem(0);
            WordItem c = (WordItem) root.getItem(1);
            WordItem d = (WordItem) root.getItem(2);
            assertEquals(c, b.getConnectedItem());
            assertEquals(1.0, b.getConnectivity(), 0.00000001);
            assertEquals(d, c.getConnectedItem());
            assertEquals(1.0, c.getConnectivity(), 0.00000001);
        }

        {
            Query query = new Query(httpEncode("?query=myfield:a myfield:b.c.d myfield:e"));
            query.getModel().setExecution(new Execution(Execution.Context.createContextStub(new IndexFacts(indexModel))));
            assertEquals("AND myfield:a myfield:b myfield:c myfield:d myfield:e", query.getModel().getQueryTree().toString());
            AndItem root = (AndItem) query.getModel().getQueryTree().getRoot();
            WordItem a = (WordItem) root.getItem(0);
            WordItem b = (WordItem) root.getItem(1);
            WordItem c = (WordItem) root.getItem(2);
            WordItem d = (WordItem) root.getItem(3);
            WordItem e = (WordItem) root.getItem(4);
            assertNull(a.getConnectedItem());
            assertEquals(c, b.getConnectedItem());
            assertEquals(1.0, b.getConnectivity(), 0.00000001);
            assertEquals(d, c.getConnectedItem());
            assertEquals(1.0, c.getConnectivity(), 0.00000001);
            assertNull(d.getConnectedItem());
        }
    }

    @Test
    public void testImplicitAndInPhrase() {
        Query query = new Query(httpEncode("?query=myfield:\"it's fine\""));

        SearchDefinition test = new SearchDefinition("test");
        Index myField = new Index("myfield");
        myField.addCommand("phrase-segmenting false");
        assertFalse(myField.getPhraseSegmenting());
        test.addIndex(myField);
        IndexModel indexModel = new IndexModel(test);
        query.getModel().setExecution(new Execution(Execution.Context.createContextStub(new IndexFacts(indexModel))));

        assertEquals("myfield:\"'it s' fine\"", query.getModel().getQueryTree().toString());
    }

    @Test
    public void testOldStyleSelect() {
        // The same as testOldStyleSelectAndNativeQueryProfileType but not inheriting native
        QueryProfileRegistry registry = new QueryProfileRegistry();
        QueryProfileType type = new QueryProfileType("mytype");
        QueryProfile profile = new QueryProfile("default");
        profile.setType(type);
        registry.register(profile);
        registry.getTypeRegistry().register(type);
        CompiledQueryProfileRegistry cRegistry = registry.compile();
        Query query = new Query(httpEncode("?query=sddocname:sentence&select=all(group(context_id) max(10) each(each(output(summary()))))"),
                                cRegistry.findQueryProfile("default"));
        GroupingQueryParser parser = new GroupingQueryParser();
        parser.search(query, new Execution(parser, Execution.Context.createContextStub()));
        assertEquals("[all(group(context_id) max(10) each(each(output(summary())))), all(group(context_id) max(10) each(each(output(summary()))))]",
                     query.getSelect().getGrouping().toString());
    }

    @Test
    public void testOldStyleSelectAndNativeQueryProfileType() {
        QueryProfileRegistry registry = new QueryProfileRegistry();
        QueryProfileType type = new QueryProfileType("mytype");
        type.inherited().add(registry.getType("native"));
        QueryProfile profile = new QueryProfile("default");
        profile.setType(type);
        registry.register(profile);
        registry.getTypeRegistry().register(type);
        CompiledQueryProfileRegistry cRegistry = registry.compile();
        Query query = new Query(httpEncode("?query=sddocname:sentence&select=all(group(context_id) max(10) each(each(output(summary()))))"),
                                cRegistry.findQueryProfile("default"));
        GroupingQueryParser parser = new GroupingQueryParser();
        parser.search(query, new Execution(parser, Execution.Context.createContextStub()));
        assertEquals("[all(group(context_id) max(10) each(each(output(summary())))), all(group(context_id) max(10) each(each(output(summary()))))]",
                     query.getSelect().getGrouping().toString());
    }

    /**
     * Tests that the value presentation.format.tensors can be set in a query profile.
     * This is special because presentation.format is a native query profile.
     */
    @Test
    public void testSettingNativeQueryProfileValueInQueryProfile() {
        {
            QueryProfileRegistry registry = new QueryProfileRegistry();
            QueryProfile profile = new QueryProfile("default");
            profile.set("presentation.format.tensors", "short", Map.of(), registry);
            registry.register(profile);
            CompiledQueryProfileRegistry cRegistry = registry.compile();
            Query query = new Query("?query=foo", cRegistry.findQueryProfile("default"));
            assertTrue(query.getPresentation().getTensorShortForm());
        }

        {   // Same as above but also set presentation.format
            QueryProfileRegistry registry = new QueryProfileRegistry();
            QueryProfile profile = new QueryProfile("default");
            profile.set("presentation.format", "xml", Map.of(), registry);
            profile.set("presentation.format.tensors", "short", Map.of(), registry);
            registry.register(profile);
            CompiledQueryProfileRegistry cRegistry = registry.compile();
            Query query = new Query("?query=foo", cRegistry.findQueryProfile("default"));
            assertEquals("xml", query.getPresentation().getFormat());
            assertTrue(query.getPresentation().getTensorShortForm());
        }

        {   // Set presentation.format with a typed query profile type
            QueryProfileRegistry registry = new QueryProfileRegistry();
            QueryProfileType type = new QueryProfileType("mytype");
            type.inherited().add(registry.getType("native"));
            registry.getTypeRegistry().register(type);
            type.addField(new FieldDescription("ranking.features.query(embedding)", "tensor(x[5])"),
                          registry.getTypeRegistry());
            QueryProfile profile = new QueryProfile("default");
            profile.setType(type);
            registry.register(profile);
            CompiledQueryProfileRegistry cRegistry = registry.compile();
            Query query = new Query("?query=foo&presentation.format=xml", cRegistry.findQueryProfile("default"));
        }
    }

    private void assertDetectionText(String expectedDetectionText, String queryString, String ... indexSpecs) {
        Query q = new Query(httpEncode("/?query=" + queryString));
        SearchDefinition sd = new SearchDefinition("testSearchDefinition");
        for (String indexSpec : indexSpecs) {
            String[] specParts = indexSpec.split(":");
            Index tokenIndex = new Index(specParts[1]);
            if (specParts[0].equals("text"))
                tokenIndex.setPlainTokens(true);
            sd.addIndex(tokenIndex);
        }
        IndexFacts indexFacts = new IndexFacts(new IndexModel(sd));
        MockLinguistics mockLinguistics = new MockLinguistics();
        q.getModel().setExecution(new Execution(Execution.Context.createContextStub(indexFacts, mockLinguistics)));
        q.getModel().getQueryTree(); // cause parsing
        assertEquals(expectedDetectionText, mockLinguistics.detector.lastDetectionText);
    }

    private void assertContains(Map<String, Object> properties, Object ... expectedValues) {
        if (expectedValues == null) {
            assertEquals(1, properties.size());
            assertTrue("Contains value null", properties.containsValue(null));
        }
        else {
            assertEquals(properties + " contains values " + Arrays.toString(expectedValues),
                         expectedValues.length, properties.size());
            for (Object expectedValue : expectedValues)
                assertTrue("Contains value " + expectedValue, properties.containsValue(expectedValue));
        }
    }

    /** A linguistics instance which records the last language detection text passed to it */
    private static class MockLinguistics extends SimpleLinguistics {

        final MockDetector detector = new MockDetector();

        @Override
        public Detector getDetector() { return detector; }

        @Override
        public boolean equals(Linguistics other) { return (other instanceof MockLinguistics); }
    }

    private static class MockDetector extends SimpleDetector {

        String lastDetectionText = null;

        @Override
        public Detection detect(String input, Hint hint) {
            lastDetectionText = input;
            return super.detect(input, hint);
        }

    }

    protected boolean contains(String lineSubstring,String[] lines) {
        for (String line : lines)
            if (line.contains(lineSubstring)) return true;
        return false;
    }

    private static class RandomSearcher extends Searcher {

        @Override
        public Result search(Query query, Execution execution) {
            Result r=new Result(query);
            r.hits().add(new Hit("hello"));
            return r;
        }
    }

    /**
     * Url encode the given string, except the characters =?&, such that queries with paths and parameters can
     * be written as a single string.
     */
    public static String httpEncode(String s) {
        if (s == null) return null;
        String encoded = URLEncoder.encode(s, StandardCharsets.UTF_8);
        encoded = encoded.replaceAll("%3F", "?");
        encoded = encoded.replaceAll("%3D", "=");
        encoded = encoded.replaceAll("%26", "&");
        return encoded;
    }

}
