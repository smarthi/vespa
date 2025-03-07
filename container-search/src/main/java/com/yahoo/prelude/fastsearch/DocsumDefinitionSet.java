// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.prelude.fastsearch;

import com.google.common.collect.ImmutableMap;
import com.yahoo.slime.BinaryFormat;
import com.yahoo.data.access.Inspector;
import com.yahoo.slime.Slime;
import com.yahoo.data.access.slime.SlimeAdapter;
import com.yahoo.prelude.ConfigurationException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static com.yahoo.data.access.Type.OBJECT;

/**
 * A set of docsum definitions
 *
 * @author bratseth
 * @author Bjørn Borud
 */
public final class DocsumDefinitionSet {

    public static final int SLIME_MAGIC_ID = 0x55555555;
    private final static Logger log = Logger.getLogger(DocsumDefinitionSet.class.getName());

    private final Map<String, DocsumDefinition> definitionsByName;

    public DocsumDefinitionSet(DocumentdbInfoConfig.Documentdb config) {
        this(toDocsums(config));
    }

    public DocsumDefinitionSet(Collection<DocsumDefinition> docsumDefinitions) {
        this.definitionsByName = ImmutableMap.copyOf(docsumDefinitions.stream().collect(Collectors.toMap(DocsumDefinition::getName, p -> p)));
    }

    /**
     * Returns the summary definition of the given name, or the default if not found.
     *
     * @throws ConfigurationException if the requested summary class is not found and there is none called "default"
     */
    public DocsumDefinition getDocsum(String summaryClass) {
        DocsumDefinition ds = definitionsByName.get(summaryClass);
        if (ds == null) {
            ds = definitionsByName.get("default");
        }
        if (ds == null) {
            throw new ConfigurationException("Fetched hit with summary class " + summaryClass +
                                             ", but this summary class is not in current summary config (" + toString() + ")" +
                                             " (that is, you asked for something unknown, and no default was found)");
        }
        return ds;
    }

    /** Do we have a summary definition with the given name */
    public boolean hasDocsum(String summaryClass) {
        return definitionsByName.containsKey(summaryClass);
    }

    /**
     * Makes data available for decoding for the given hit.
     *
     * @param summaryClass the requested summary class
     * @param data docsum data from backend
     * @param hit the Hit corresponding to this document summary
     * @return Error message or null on success.
     * @throws ConfigurationException if the summary class of this hit is missing
     */
    public final String lazyDecode(String summaryClass, byte[] data, FastHit hit) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        long docsumClassId = buffer.getInt();
        if (docsumClassId != SLIME_MAGIC_ID) {
            throw new IllegalArgumentException("Only expecting SchemaLess docsums - summary class:" + summaryClass + " hit:" + hit);
        }
        DocsumDefinition docsumDefinition = getDocsum(summaryClass);
        Slime value = BinaryFormat.decode(buffer.array(), buffer.arrayOffset()+buffer.position(), buffer.remaining());
        Inspector docsum = new SlimeAdapter(value.get());
        if (docsum.type() != OBJECT) {
            return "Hit " + hit + " failed: " + docsum.asString();
        }
        hit.addSummary(docsumDefinition, docsum);
        return null;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, DocsumDefinition> e : definitionsByName.entrySet() ) {
            if (sb.length() != 0) {
                sb.append(",");
            }
            sb.append("[").append(e.getKey()).append(",").append(e.getValue().getName()).append("]");
        }
        return sb.toString();
    }

    public int size() {
        return definitionsByName.size();
    }

    private static Collection<DocsumDefinition> toDocsums(DocumentdbInfoConfig.Documentdb config) {
        Collection<DocsumDefinition> docsums = new ArrayList<>();
        for (int i = 0; i < config.summaryclass().size(); ++i)
            docsums.add(new DocsumDefinition(config.summaryclass(i)));
        if (docsums.isEmpty())
            log.warning("No summary classes found in DocumentdbInfoConfig.Documentdb");
        return docsums;
    }

}
