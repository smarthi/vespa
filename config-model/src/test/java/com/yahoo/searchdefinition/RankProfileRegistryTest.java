// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition;

import com.yahoo.config.model.application.provider.FilesApplicationPackage;
import com.yahoo.config.model.test.MockApplicationPackage;
import com.yahoo.config.model.test.TestDriver;
import com.yahoo.config.model.test.TestRoot;
import com.yahoo.searchlib.rankingexpression.ExpressionFunction;
import com.yahoo.searchlib.rankingexpression.RankingExpression;
import com.yahoo.vespa.config.search.RankProfilesConfig;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * @author Ulf Lilleengen
 */
public class RankProfileRegistryTest {

    private static final String TESTDIR = "src/test/cfg/search/data/v2/inherited_rankprofiles";

    @Test
    public void testRankProfileInheritance() {
        TestRoot root = new TestDriver().buildModel(FilesApplicationPackage.fromFile(new File(TESTDIR)));
        RankProfilesConfig left = root.getConfig(RankProfilesConfig.class, "inherit/search/cluster.inherit/left");
        RankProfilesConfig right = root.getConfig(RankProfilesConfig.class, "inherit/search/cluster.inherit/right");
        assertEquals(3, left.rankprofile().size());
        assertEquals(2, right.rankprofile().size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRankProfileDuplicateNameIsIllegal() {
        Schema schema = new Schema("foo", MockApplicationPackage.createEmpty());
        RankProfileRegistry rankProfileRegistry = RankProfileRegistry.createRankProfileRegistryWithBuiltinRankProfiles(schema);
        RankProfile barRankProfile = new RankProfile("bar", schema, rankProfileRegistry, schema.rankingConstants());
        rankProfileRegistry.add(barRankProfile);
        rankProfileRegistry.add(barRankProfile);
    }

    @Test
    public void testRankProfileDuplicateNameLegalForOverridableRankProfiles() {
        Schema schema = new Schema("foo", MockApplicationPackage.createEmpty());
        RankProfileRegistry rankProfileRegistry = RankProfileRegistry.createRankProfileRegistryWithBuiltinRankProfiles(schema);

        for (String rankProfileName : RankProfileRegistry.overridableRankProfileNames) {
            assertNull(rankProfileRegistry.get(schema, rankProfileName).getFunctions().get("foo"));
            RankProfile rankProfileWithAddedFunction = new RankProfile(rankProfileName, schema, rankProfileRegistry, schema.rankingConstants());
            rankProfileWithAddedFunction.addFunction(new ExpressionFunction("foo", RankingExpression.from("1+2")), true);
            rankProfileRegistry.add(rankProfileWithAddedFunction);
            assertNotNull(rankProfileRegistry.get(schema, rankProfileName).getFunctions().get("foo"));
        }
    }

}
