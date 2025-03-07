// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition.processing;

import com.yahoo.searchdefinition.ApplicationBuilder;
import com.yahoo.searchdefinition.derived.AbstractExportingTestCase;
import com.yahoo.searchdefinition.parser.ParseException;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;

import static com.yahoo.searchdefinition.processing.AssertIndexingScript.assertIndexing;
import static com.yahoo.searchdefinition.processing.AssertSearchBuilder.assertBuildFails;

/**
 * @author Simon Thoresen Hult
 */
public class IndexingValidationTestCase extends AbstractExportingTestCase {

    @Test
    public void testAttributeChanged() throws IOException, ParseException {
        assertBuildFails("src/test/examples/indexing_attribute_changed.sd",
                         "For schema 'indexing_attribute_changed', field 'foo': For expression 'attribute foo': " +
                         "Attempting to assign conflicting values to field 'foo'.");
    }

    @Test
    public void testAttributeOther() throws IOException, ParseException {
        assertBuildFails("src/test/examples/indexing_attribute_other.sd",
                         "For schema 'indexing_attribute_other', field 'foo': Indexing expression 'attribute bar' " +
                         "attempts to write to a field other than 'foo'.");
    }

    @Test
    public void testIndexChanged() throws IOException, ParseException {
        assertBuildFails("src/test/examples/indexing_index_changed.sd",
                         "For schema 'indexing_index_changed', field 'foo': For expression 'index foo': " +
                         "Attempting to assign conflicting values to field 'foo'.");
    }

    @Test
    public void testIndexOther() throws IOException, ParseException {
        assertBuildFails("src/test/examples/indexing_index_other.sd",
                         "For schema 'indexing_index_other', field 'foo': Indexing expression 'index bar' " +
                         "attempts to write to a field other than 'foo'.");
    }

    @Test
    public void testSummaryChanged() throws IOException, ParseException {
        assertBuildFails("src/test/examples/indexing_summary_changed.sd",
                         "For schema 'indexing_summary_fail', field 'foo': For expression 'summary foo': Attempting " +
                         "to assign conflicting values to field 'foo'.");
    }

    @Test
    public void testSummaryOther() throws IOException, ParseException {
        assertBuildFails("src/test/examples/indexing_summary_other.sd",
                         "For schema 'indexing_summary_other', field 'foo': Indexing expression 'summary bar' " +
                         "attempts to write to a field other than 'foo'.");
    }

    @Test
    public void testExtraField() throws IOException, ParseException {
        assertIndexing(
                Arrays.asList("clear_state | guard { input my_index | tokenize normalize stem:\"BEST\" | index my_index | summary my_index }",
                              "clear_state | guard { input my_input | tokenize normalize stem:\"BEST\" | index my_extra | summary my_extra }"),
                ApplicationBuilder.buildFromFile("src/test/examples/indexing_extra.sd"));
    }

    @Test
    public void requireThatMultilineOutputConflictThrows() throws IOException, ParseException {
        assertBuildFails("src/test/examples/indexing_multiline_output_conflict.sd",
                         "For schema 'indexing_multiline_output_confict', field 'cox': For expression 'index cox': " +
                         "Attempting to assign conflicting values to field 'cox'.");
    }
}
