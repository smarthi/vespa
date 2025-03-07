// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition.derived;

import com.yahoo.config.subscription.ConfigInstanceUtil;
import com.yahoo.document.DataType;
import com.yahoo.searchdefinition.Schema;
import com.yahoo.searchdefinition.document.Attribute;
import com.yahoo.searchdefinition.document.Case;
import com.yahoo.searchdefinition.document.Dictionary;
import com.yahoo.searchdefinition.document.GeoPos;
import com.yahoo.searchdefinition.document.ImmutableSDField;
import com.yahoo.searchdefinition.document.Ranking;
import com.yahoo.searchdefinition.document.Sorting;
import com.yahoo.vespa.config.search.AttributesConfig;
import com.yahoo.vespa.indexinglanguage.expressions.ToPositionExpression;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.yahoo.searchdefinition.document.ComplexAttributeFieldUtils.isArrayOfSimpleStruct;
import static com.yahoo.searchdefinition.document.ComplexAttributeFieldUtils.isMapOfPrimitiveType;
import static com.yahoo.searchdefinition.document.ComplexAttributeFieldUtils.isMapOfSimpleStruct;
import static com.yahoo.searchdefinition.document.ComplexAttributeFieldUtils.isSupportedComplexField;

/**
 * The set of all attribute fields defined by a search definition
 *
 * @author bratseth
 */
public class AttributeFields extends Derived implements AttributesConfig.Producer {

    public enum FieldSet {ALL, FAST_ACCESS}

    private Map<String, Attribute> attributes = new java.util.LinkedHashMap<>();
    private Map<String, Attribute> importedAttributes = new java.util.LinkedHashMap<>();

    /** Whether this has any position attribute */
    private boolean hasPosition = false;

    public static final AttributeFields empty = new AttributeFields(null);

    public AttributeFields(Schema schema) {
        if (schema != null)
            derive(schema);
    }

    /** Derives everything from a field */
    @Override
    protected void derive(ImmutableSDField field, Schema schema) {
        if (unsupportedFieldType(field)) {
            return; // Ignore complex struct and map fields for indexed search (only supported for streaming search)
        }
        if (isArrayOfSimpleStruct(field)) {
            deriveArrayOfSimpleStruct(field);
        } else if (isMapOfSimpleStruct(field)) {
            deriveMapOfSimpleStruct(field);
        } else if (isMapOfPrimitiveType(field)) {
            deriveMapOfPrimitiveType(field);
        } else {
            deriveAttributes(field);
        }
    }

    private static boolean unsupportedFieldType(ImmutableSDField field) {
        return (field.usesStructOrMap() &&
                !isSupportedComplexField(field) &&
                !GeoPos.isAnyPos(field));
    }

    /** Returns an attribute by name, or null if it doesn't exist */
    public Attribute getAttribute(String attributeName) {
        return attributes.get(attributeName);
    }

    public boolean containsAttribute(String attributeName) {
        return getAttribute(attributeName) != null;
    }

    /** Derives one attribute. TODO: Support non-default named attributes */
    private void deriveAttributes(ImmutableSDField field) {
        if (field.isImportedField()) {
            deriveImportedAttributes(field);
            return;
        }
        for (Attribute fieldAttribute : field.getAttributes().values()) {
            deriveAttribute(field, fieldAttribute);
        }

        if (field.containsExpression(ToPositionExpression.class)) {
            // TODO: Move this check to processing and remove this
            if (hasPosition) {
                throw new IllegalArgumentException("Can not specify more than one set of position attributes per field: " + field.getName());
            }
            hasPosition = true;
        }
    }

    private void deriveAttribute(ImmutableSDField field, Attribute fieldAttribute) {
        Attribute attribute = getAttribute(fieldAttribute.getName());
        if (attribute == null) {
            attributes.put(fieldAttribute.getName(), fieldAttribute);
            attribute = getAttribute(fieldAttribute.getName());
        }
        Ranking ranking = field.getRanking();
        if (ranking != null && ranking.isFilter()) {
            attribute.setEnableBitVectors(true);
            attribute.setEnableOnlyBitVector(true);
        }
    }

    private void deriveImportedAttributes(ImmutableSDField field) {
        for (Attribute attribute : field.getAttributes().values()) {
            if (!importedAttributes.containsKey(field.getName())) {
                importedAttributes.put(field.getName(), attribute);
            }
        }
    }

    private void deriveArrayOfSimpleStruct(ImmutableSDField field) {
        for (ImmutableSDField structField : field.getStructFields()) {
            deriveAttributeAsArrayType(structField);
        }
    }

    private void deriveAttributeAsArrayType(ImmutableSDField field) {
        if (field.isImportedField()) {
            deriveImportedAttributes(field);
            return;
        }
        Attribute attribute = field.getAttributes().get(field.getName());
        if (attribute != null) {
            attributes.put(attribute.getName(), attribute.convertToArray());
        }
    }

    private void deriveMapOfSimpleStruct(ImmutableSDField field) {
        deriveAttributeAsArrayType(field.getStructField("key"));
        deriveMapValueField(field.getStructField("value"));
    }

    private void deriveMapValueField(ImmutableSDField valueField) {
        for (ImmutableSDField structField : valueField.getStructFields()) {
            deriveAttributeAsArrayType(structField);
        }
    }

    private void deriveMapOfPrimitiveType(ImmutableSDField field) {
        deriveAttributeAsArrayType(field.getStructField("key"));
        deriveAttributeAsArrayType(field.getStructField("value"));
    }

    /** Returns a read only attribute iterator */
    public Iterator attributeIterator() {
        return attributes().iterator();
    }

    public Collection<Attribute> attributes() {
        return Collections.unmodifiableCollection(attributes.values());
    }

    public Collection<Attribute> structFieldAttributes(String baseFieldName) {
        String structPrefix = baseFieldName + ".";
        return attributes().stream()
                .filter(attribute -> attribute.getName().startsWith(structPrefix))
                .collect(Collectors.toList());
    }

    public String toString() {
        return "attributes " + getName();
    }

    @Override
    protected String getDerivedName() {
        return "attributes";
    }

    @SuppressWarnings("removal") // TODO Vespa 8: remove
    private Map<String, AttributesConfig.Attribute.Builder> toMap(List<AttributesConfig.Attribute.Builder> ls) {
        Map<String, AttributesConfig.Attribute.Builder> ret = new LinkedHashMap<>();
        for (AttributesConfig.Attribute.Builder builder : ls) {
            ret.put((String) ConfigInstanceUtil.getField(builder, "name"), builder);
        }
        return ret;
    }

    @Override
    public void getConfig(AttributesConfig.Builder builder) {
        //TODO This is just to get some exporting tests to work, Should be undone and removed
        getConfig(builder, FieldSet.ALL, 77777);
    }

    private boolean isAttributeInFieldSet(Attribute attribute, FieldSet fs) {
        return (fs == FieldSet.ALL) || ((fs == FieldSet.FAST_ACCESS) && attribute.isFastAccess());
    }

    private AttributesConfig.Attribute.Builder getConfig(String attrName, Attribute attribute, boolean imported) {
        AttributesConfig.Attribute.Builder aaB = new AttributesConfig.Attribute.Builder()
                .name(attrName)
                .datatype(AttributesConfig.Attribute.Datatype.Enum.valueOf(attribute.getType().getExportAttributeTypeName()))
                .collectiontype(AttributesConfig.Attribute.Collectiontype.Enum.valueOf(attribute.getCollectionType().getName()));
        if (attribute.isRemoveIfZero()) {
            aaB.removeifzero(true);
        }
        if (attribute.isCreateIfNonExistent()) {
            aaB.createifnonexistent(true);
        }
        aaB.enablebitvectors(attribute.isEnabledBitVectors());
        aaB.enableonlybitvector(attribute.isEnabledOnlyBitVector());
        if (attribute.isFastSearch()) {
            aaB.fastsearch(true);
        }
        if (attribute.isFastAccess()) {
            aaB.fastaccess(true);
        }
        if (attribute.isMutable()) {
            aaB.ismutable(true);
        }
        if (attribute.isHuge()) {
            aaB.huge(true);
        }
        if (attribute.isPaged()) {
            aaB.paged(true);
        }
        if (attribute.getSorting().isDescending()) {
            aaB.sortascending(false);
        }
        if (attribute.getSorting().getFunction() != Sorting.Function.UCA) {
            aaB.sortfunction(AttributesConfig.Attribute.Sortfunction.Enum.valueOf(attribute.getSorting().getFunction().toString()));
        }
        if (attribute.getSorting().getStrength() != Sorting.Strength.PRIMARY) {
            aaB.sortstrength(AttributesConfig.Attribute.Sortstrength.Enum.valueOf(attribute.getSorting().getStrength().toString()));
        }
        if (!attribute.getSorting().getLocale().isEmpty()) {
            aaB.sortlocale(attribute.getSorting().getLocale());
        }
        aaB.arity(attribute.arity());
        aaB.lowerbound(attribute.lowerBound());
        aaB.upperbound(attribute.upperBound());
        aaB.densepostinglistthreshold(attribute.densePostingListThreshold());
        if (attribute.tensorType().isPresent()) {
            aaB.tensortype(attribute.tensorType().get().toString());
        }
        aaB.imported(imported);
        var dma = attribute.distanceMetric();
        aaB.distancemetric(AttributesConfig.Attribute.Distancemetric.Enum.valueOf(dma.toString()));
        if (attribute.hnswIndexParams().isPresent()) {
            var ib = new AttributesConfig.Attribute.Index.Builder();
            var params = attribute.hnswIndexParams().get();
            ib.hnsw.enabled(true);
            ib.hnsw.maxlinkspernode(params.maxLinksPerNode());
            ib.hnsw.neighborstoexploreatinsert(params.neighborsToExploreAtInsert());
            ib.hnsw.multithreadedindexing(params.multiThreadedIndexing());
            aaB.index(ib);
        }
        Dictionary dictionary = attribute.getDictionary();
        if (dictionary != null) {
            aaB.dictionary.type(convert(dictionary.getType()));
            aaB.dictionary.match(convert(dictionary.getMatch()));
        }
        aaB.match(convertMatch(attribute.getCase()));
        return aaB;
    }

    private static AttributesConfig.Attribute.Dictionary.Type.Enum convert(Dictionary.Type type) {
        switch (type) {
            case BTREE:
                return AttributesConfig.Attribute.Dictionary.Type.BTREE;
            case HASH:
                return AttributesConfig.Attribute.Dictionary.Type.HASH;
            case BTREE_AND_HASH:
                return AttributesConfig.Attribute.Dictionary.Type.BTREE_AND_HASH;
        }
        return AttributesConfig.Attribute.Dictionary.Type.BTREE;
    }
    private static AttributesConfig.Attribute.Dictionary.Match.Enum convert(Case type) {
        switch (type) {
            case CASED:
                return AttributesConfig.Attribute.Dictionary.Match.CASED;
            case UNCASED:
                return AttributesConfig.Attribute.Dictionary.Match.UNCASED;
        }
        return AttributesConfig.Attribute.Dictionary.Match.UNCASED;
    }
    private static AttributesConfig.Attribute.Match.Enum convertMatch(Case type) {
        switch (type) {
            case CASED:
                return AttributesConfig.Attribute.Match.CASED;
            case UNCASED:
                return AttributesConfig.Attribute.Match.UNCASED;
        }
        return AttributesConfig.Attribute.Match.UNCASED;
    }

    public void getConfig(AttributesConfig.Builder builder, FieldSet fs, long maxUnCommittedMemory) {
        for (Attribute attribute : attributes.values()) {
            if (isAttributeInFieldSet(attribute, fs)) {
                AttributesConfig.Attribute.Builder attrBuilder = getConfig(attribute.getName(), attribute, false);
                attrBuilder.maxuncommittedmemory(maxUnCommittedMemory);
                builder.attribute(attrBuilder);
            }
        }
        if (fs == FieldSet.ALL) {
            for (Map.Entry<String, Attribute> entry : importedAttributes.entrySet()) {
                AttributesConfig.Attribute.Builder attrBuilder = getConfig(entry.getKey(), entry.getValue(), true);
                attrBuilder.maxuncommittedmemory(maxUnCommittedMemory);
                builder.attribute(attrBuilder);
            }
        }
    }

}
