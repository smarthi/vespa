// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.search.pagetemplates.engine.test;

import com.yahoo.search.Query;
import com.yahoo.search.Result;
import com.yahoo.search.pagetemplates.engine.Organizer;
import com.yahoo.search.pagetemplates.engine.Resolution;
import com.yahoo.search.pagetemplates.engine.Resolver;
import com.yahoo.search.pagetemplates.engine.resolvers.DeterministicResolver;
import com.yahoo.search.pagetemplates.model.Choice;
import org.junit.Test;

/**
 * Tests an example with two data sources with a renderer each.
 *
 * @author bratseth
 */
public class PageWithSourceRendererTestCase extends ExecutionAbstractTestCase {

    @Test
    public void testExecution() {
        // Create the page template
        Choice page = Choice.createSingleton(importPage("PageWithSourceRenderer.xml"));

        // Create a federated result
        Query query = new Query();
        Result result = new Result(query);
        result.hits().add(createHits("news", 2));
        result.hits().add(createHits("htmlSource", 1));

        // Resolve
        Resolver resolver = new DeterministicResolver();
        Resolution resolution = resolver.resolve(page, query, result);

        // Execute
        Organizer organizer = new Organizer();
        organizer.organize(page, resolution, result);

        // Check rendering
        assertRendered(result, "PageWithSourceRendererResult.xml");
    }

}
