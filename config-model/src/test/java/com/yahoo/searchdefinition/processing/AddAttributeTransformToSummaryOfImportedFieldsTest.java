// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition.processing;

import com.yahoo.config.model.application.provider.MockFileRegistry;
import com.yahoo.config.model.deploy.TestProperties;
import com.yahoo.config.model.test.MockApplicationPackage;
import com.yahoo.document.DataType;
import com.yahoo.document.Field;
import com.yahoo.searchdefinition.Application;
import com.yahoo.searchdefinition.DocumentReference;
import com.yahoo.searchdefinition.Schema;
import com.yahoo.searchdefinition.derived.TestableDeployLogger;
import com.yahoo.searchdefinition.document.ImportedField;
import com.yahoo.searchdefinition.document.ImportedFields;
import com.yahoo.searchdefinition.document.ImportedSimpleField;
import com.yahoo.searchdefinition.document.SDDocumentType;
import com.yahoo.searchdefinition.document.SDField;
import com.yahoo.vespa.documentmodel.DocumentSummary;
import com.yahoo.vespa.documentmodel.SummaryField;
import com.yahoo.vespa.documentmodel.SummaryTransform;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

/**
 * @author bjorncs
 */
public class AddAttributeTransformToSummaryOfImportedFieldsTest {

    private static final String IMPORTED_FIELD_NAME = "imported_myfield";
    private static final String DOCUMENT_NAME = "mydoc";
    private static final String SUMMARY_NAME = "mysummary";

    @Test
    public void attribute_summary_transform_applied_to_summary_field_of_imported_field() {
        Schema schema = createSearchWithDocument(DOCUMENT_NAME);
        schema.setImportedFields(createSingleImportedField(IMPORTED_FIELD_NAME));
        schema.addSummary(createDocumentSummary(IMPORTED_FIELD_NAME, schema));

        AddAttributeTransformToSummaryOfImportedFields processor = new AddAttributeTransformToSummaryOfImportedFields(
                schema, null, null, null);
        processor.process(true, false);
        SummaryField summaryField = schema.getSummaries().get(SUMMARY_NAME).getSummaryField(IMPORTED_FIELD_NAME);
        SummaryTransform actualTransform = summaryField.getTransform();
        assertEquals(SummaryTransform.ATTRIBUTE, actualTransform);
    }

    private static Schema createSearch(String documentType) {
        return new Schema(documentType, MockApplicationPackage.createEmpty(), new MockFileRegistry(), new TestableDeployLogger(), new TestProperties());
    }

    private static Schema createSearchWithDocument(String documentName) {
        Schema schema = createSearch(documentName);
        SDDocumentType document = new SDDocumentType(documentName, schema);
        schema.addDocument(document);
        return schema;
    }

    private static ImportedFields createSingleImportedField(String fieldName) {
        Schema targetSchema = createSearch("target_doc");
        SDField targetField = new SDField("target_field", DataType.INT);
        DocumentReference documentReference = new DocumentReference(new Field("reference_field"), targetSchema);
        ImportedField importedField = new ImportedSimpleField(fieldName, documentReference, targetField);
        return new ImportedFields(Collections.singletonMap(fieldName, importedField));
    }

    private static DocumentSummary createDocumentSummary(String fieldName, Schema schema) {
        DocumentSummary summary = new DocumentSummary("mysummary", schema);
        summary.add(new SummaryField(fieldName, DataType.INT));
        return summary;
    }

}
