// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition;

import com.yahoo.document.StructDataType;
import com.yahoo.searchdefinition.derived.AttributeFields;
import com.yahoo.searchdefinition.derived.IndexingScript;
import com.yahoo.searchdefinition.document.Attribute;
import com.yahoo.searchdefinition.document.SDField;
import com.yahoo.searchdefinition.parser.ParseException;
import com.yahoo.tensor.TensorType;
import com.yahoo.vespa.config.search.AttributesConfig;
import com.yahoo.vespa.configdefinition.IlscriptsConfig;
import org.junit.Test;

import java.io.IOException;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Attribute settings
 *
 * @author  bratseth
 */
public class AttributeSettingsTestCase extends AbstractSchemaTestCase {

    @Test
    public void testAttributeSettings() throws IOException, ParseException {
        Schema schema = ApplicationBuilder.buildFromFile("src/test/examples/attributesettings.sd");

        SDField f1=(SDField) schema.getDocument().getField("f1");
        assertEquals(1, f1.getAttributes().size());
        Attribute a1 = f1.getAttributes().get(f1.getName());
        assertEquals(Attribute.Type.LONG, a1.getType());
        assertEquals(Attribute.CollectionType.SINGLE, a1.getCollectionType());
        assertTrue(a1.isHuge());
        assertFalse(a1.isFastSearch());
        assertFalse(a1.isFastAccess());
        assertFalse(a1.isRemoveIfZero());
        assertFalse(a1.isCreateIfNonExistent());

        SDField f2=(SDField) schema.getDocument().getField("f2");
        assertEquals(1, f2.getAttributes().size());
        Attribute a2 = f2.getAttributes().get(f2.getName());
        assertEquals(Attribute.Type.LONG, a2.getType());
        assertEquals(Attribute.CollectionType.SINGLE, a2.getCollectionType());
        assertFalse(a2.isHuge());
        assertTrue(a2.isFastSearch());
        assertFalse(a2.isFastAccess());
        assertFalse(a2.isRemoveIfZero());
        assertFalse(a2.isCreateIfNonExistent());
        assertEquals("f2", f2.getAliasToName().get("f2alias"));
        SDField f3=(SDField) schema.getDocument().getField("f3");
        assertEquals(1, f3.getAttributes().size());
        assertEquals("f3", f3.getAliasToName().get("f3alias"));

        Attribute a3 = f3.getAttributes().get(f3.getName());
        assertEquals(Attribute.Type.LONG, a3.getType());
        assertEquals(Attribute.CollectionType.SINGLE, a3.getCollectionType());
        assertFalse(a3.isHuge());
        assertFalse(a3.isFastSearch());
        assertFalse(a3.isFastAccess());
        assertFalse(a3.isRemoveIfZero());
        assertFalse(a3.isCreateIfNonExistent());

        assertWeightedSet(schema, "f4", true, true);
        assertWeightedSet(schema, "f5", true, true);
        assertWeightedSet(schema, "f6", true, true);
        assertWeightedSet(schema, "f7", true, false);
        assertWeightedSet(schema, "f8", true, false);
        assertWeightedSet(schema, "f9", false, true);
        assertWeightedSet(schema, "f10", false, true);
    }

    private void assertWeightedSet(Schema schema, String name, boolean createIfNonExistent, boolean removeIfZero) {
        SDField f4 = (SDField) schema.getDocument().getField(name);
        assertEquals(1, f4.getAttributes().size());
        Attribute a4 = f4.getAttributes().get(f4.getName());
        assertEquals(Attribute.Type.STRING, a4.getType());
        assertEquals(Attribute.CollectionType.WEIGHTEDSET, a4.getCollectionType());
        assertFalse(a4.isHuge());
        assertFalse(a4.isFastSearch());
        assertFalse(a4.isFastAccess());
        assertEquals(a4.isRemoveIfZero(), removeIfZero);
        assertEquals(a4.isCreateIfNonExistent(), createIfNonExistent);
    }

    @Test
    public void requireThatFastAccessCanBeSet() throws IOException, ParseException {
        Schema schema = ApplicationBuilder.buildFromFile("src/test/examples/attributesettings.sd");
        SDField field = (SDField) schema.getDocument().getField("fast_access");
        assertEquals(1, field.getAttributes().size());
        Attribute attr = field.getAttributes().get(field.getName());
        assertTrue(attr.isFastAccess());
    }

    private Schema getSchema(String sd) throws ParseException {
        ApplicationBuilder builder = new ApplicationBuilder();
        builder.addSchema(sd);
        builder.build(true);
        return builder.getSchema();
    }

    private Attribute getAttributeF(String sd) throws ParseException {
        Schema schema = getSchema(sd);
        SDField field = (SDField) schema.getDocument().getField("f");
        return field.getAttributes().get(field.getName());
    }

    @Test
    public void requireThatPagedIsDefaultOff() throws ParseException {
        Attribute attr = getAttributeF(
                "search test {\n" +
                        "  document test { \n" +
                        "    field f type tensor(x[2]) { \n" +
                        "      indexing: attribute \n" +
                        "    }\n" +
                        "  }\n" +
                        "}\n");
        assertFalse(attr.isPaged());
    }
    @Test
    public void requireThatPagedCanBeSet() throws ParseException {
        Attribute attr = getAttributeF(
                "search test {\n" +
                        "  document test { \n" +
                        "    field f type tensor(x[2]) { \n" +
                        "      indexing: attribute \n" +
                        "      attribute: paged \n" +
                        "    }\n" +
                        "  }\n" +
                        "}\n");
        assertTrue(attr.isPaged());
    }

    @Test
    public void requireThatMutableIsDefaultOff() throws ParseException {
        Attribute attr = getAttributeF(
                "search test {\n" +
                "  document test { \n" +
                "    field f type int { \n" +
                "      indexing: attribute \n" +
                "    }\n" +
                "  }\n" +
                "}\n");
        assertFalse(attr.isMutable());
    }

    @Test
    public void requireThatMutableCanNotbeSetInDocument() throws ParseException {
        try {
            getSchema("search test {\n" +
                      "  document test {\n" +
                      "    field f type int {\n" +
                      "      indexing: attribute\n" +
                      "      attribute: mutable\n" +
                      "    }\n" +
                      "  }\n" +
                      "}\n");
            fail();
        } catch (IllegalArgumentException e) {
            assertEquals("Field 'f' in 'test' can not be marked mutable as it is inside the document clause.", e.getMessage());
        }
    }

    @Test
    public void requireThatMutableExtraFieldCanBeSet() throws ParseException {
        Attribute attr = getAttributeF(
                "search test {\n" +
                        "  document test { \n" +
                        "    field a type int { \n" +
                        "      indexing: attribute \n" +
                        "    }\n" +
                        "  }\n" +
                        "  field f type long {\n" +
                        "    indexing: 0 | to_long | attribute\n" +
                        "    attribute: mutable\n" +
                        "  }\n" +
                        "}\n");
        assertTrue(attr.isMutable());
    }

    private Schema getSearchWithMutables() throws ParseException {
        return getSchema(
                "search test {\n" +
                    "  document test { \n" +
                    "    field a type int { \n" +
                    "      indexing: attribute \n" +
                    "    }\n" +
                    "  }\n" +
                    "  field m type long {\n" +
                    "    indexing: attribute\n" +
                    "    attribute: mutable\n" +
                    "  }\n" +
                    "  field f type long {\n" +
                    "    indexing: 0 | to_long | attribute\n" +
                    "  }\n" +
                    "}\n");
    }

    @Test
    public void requireThatMutableConfigIsProperlyPropagated() throws ParseException {

        AttributeFields attributes = new AttributeFields(getSearchWithMutables());
        AttributesConfig.Builder builder = new AttributesConfig.Builder();
        attributes.getConfig(builder, AttributeFields.FieldSet.ALL, 13333);
        AttributesConfig cfg = builder.build();
        assertEquals("a", cfg.attribute().get(0).name());
        assertFalse(cfg.attribute().get(0).ismutable());

        assertEquals("f", cfg.attribute().get(1).name());
        assertFalse(cfg.attribute().get(1).ismutable());

        assertEquals("m", cfg.attribute().get(2).name());
        assertTrue(cfg.attribute().get(2).ismutable());
    }

    @Test
    public void requireMaxUnCommittedMemoryIsProperlyPropagated() throws ParseException {

        AttributeFields attributes = new AttributeFields(getSearchWithMutables());
        AttributesConfig.Builder builder = new AttributesConfig.Builder();
        attributes.getConfig(builder, AttributeFields.FieldSet.ALL, 13333);
        AttributesConfig cfg = builder.build();
        assertEquals("a", cfg.attribute().get(0).name());
        assertEquals(13333, cfg.attribute().get(0).maxuncommittedmemory());

        assertEquals("f", cfg.attribute().get(1).name());
        assertEquals(13333, cfg.attribute().get(1).maxuncommittedmemory());

        assertEquals("m", cfg.attribute().get(2).name());
        assertEquals(13333, cfg.attribute().get(2).maxuncommittedmemory());
    }

    @Test
    public void requireThatMutableIsAllowedThroughIndexing() throws ParseException {
        IndexingScript script = new IndexingScript(getSearchWithMutables());
        IlscriptsConfig.Builder builder = new IlscriptsConfig.Builder();
        script.getConfig(builder);
        IlscriptsConfig cfg = builder.build();
        assertEquals(1, cfg.ilscript().size());
        IlscriptsConfig.Ilscript ils = cfg.ilscript(0);
        assertEquals("test", ils.doctype());
        assertEquals(2, ils.docfield().size());
        assertEquals("a", ils.docfield(0));
        assertEquals("m", ils.docfield(1));

    }

    @Test
    public void attribute_convert_to_array_copies_internal_state() {
        StructDataType refType = new StructDataType("my_struct");
        Attribute single = new Attribute("foo", Attribute.Type.STRING, Attribute.CollectionType.SINGLE,
                Optional.of(TensorType.fromSpec("tensor(x{})")), Optional.of(refType));
        single.setRemoveIfZero(true);
        single.setCreateIfNonExistent(true);
        single.setPrefetch(Boolean.TRUE);
        single.setEnableBitVectors(true);
        single.setEnableOnlyBitVector(true);
        single.setFastSearch(true);
        single.setHuge(true);
        single.setPaged(true);
        single.setFastAccess(true);
        single.setPosition(true);
        single.setArity(5);
        single.setLowerBound(7);
        single.setUpperBound(11);
        single.setDensePostingListThreshold(13.3);
        single.getSorting().setAscending();
        single.getAliases().add("foo");

        Attribute array = single.convertToArray();
        assertEquals("foo", array.getName());
        assertEquals(Attribute.Type.STRING, array.getType());
        assertEquals(Attribute.CollectionType.ARRAY, array.getCollectionType());
        assertEquals(Optional.of(TensorType.fromSpec("tensor(x{})")), array.tensorType());
        assertSame(single.referenceDocumentType(), array.referenceDocumentType());
        assertTrue(array.isRemoveIfZero());
        assertTrue(array.isCreateIfNonExistent());
        assertTrue(array.isPrefetch());
        assertTrue(array.isEnabledBitVectors());
        assertTrue(array.isEnabledOnlyBitVector());
        assertTrue(array.isFastSearch());
        assertTrue(array.isHuge());
        assertTrue(array.isPaged());
        assertTrue(array.isFastAccess());
        assertTrue(array.isPosition());
        assertEquals(5, array.arity());
        assertEquals(7, array.lowerBound());
        assertEquals(11, array.upperBound());
        assertEquals(13.3, array.densePostingListThreshold(), 0.00001);
        assertSame(single.getSorting(), array.getSorting());
        assertSame(single.getAliases(), array.getAliases());
    }

}
