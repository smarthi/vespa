// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition.derived;

import com.yahoo.search.Query;
import com.yahoo.search.query.profile.compiled.CompiledQueryProfileRegistry;
import com.yahoo.search.query.profile.config.QueryProfileConfigurer;
import com.yahoo.searchdefinition.parser.ParseException;
import com.yahoo.vespa.model.container.search.QueryProfiles;
import org.junit.Test;

import java.io.IOException;

import com.yahoo.component.ComponentId;

import static org.junit.Assert.assertEquals;

public class NeuralNetTestCase extends AbstractExportingTestCase {

    @Test
    public void testNeuralNet() throws IOException, ParseException {
        ComponentId.resetGlobalCountersForTests();
        DerivedConfiguration c = assertCorrectDeriving("neuralnet");

        // Verify that query profiles end up correct when passed through the same intermediate forms as a full system
        CompiledQueryProfileRegistry queryProfiles =
                QueryProfileConfigurer.createFromConfig(new QueryProfiles(c.getQueryProfiles(), (level, message) -> {}).getConfig()).compile();
        Query q = new Query("?test=foo&ranking.features.query(b_1)=[1,2,3,4,5,6,7,8,9]",
                            queryProfiles.getComponent("default"));
        assertEquals("tensor(out[9]):[1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0]",
                     q.properties().get("ranking.features.query(b_1)").toString());
    }

}
