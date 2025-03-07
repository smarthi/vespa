// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchlib.rankingexpression.rule;

import com.google.common.collect.ImmutableMap;
import com.yahoo.searchlib.rankingexpression.ExpressionFunction;
import com.yahoo.searchlib.rankingexpression.RankingExpression;
import com.yahoo.tensor.TensorType;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Context needed to serialize an expression to a string. This has the lifetime of a single serialization
 *
 * @author bratseth
 */
public class SerializationContext extends FunctionReferenceContext {
    
    /** Serialized form of functions indexed by name */
    private final Map<String, String> serializedFunctions;

    /** Create a context for a single serialization task */
    public SerializationContext() {
        this(Collections.emptyList());
    }

    /** Create a context for a single serialization task */
    public SerializationContext(Collection<ExpressionFunction> functions) {
        this(functions, Collections.emptyMap(), new LinkedHashMap<>());
    }

    /** Create a context for a single serialization task */
    public SerializationContext(Map<String, ExpressionFunction> functions) {
        this(functions.values());
    }

    /** Create a context for a single serialization task */
    public SerializationContext(Collection<ExpressionFunction> functions, Map<String, String> bindings) {
        this(functions, bindings, new LinkedHashMap<>());
    }

    /**
     * Create a context for a single serialization task
     *
     * @param functions the functions of this
     * @param bindings the arguments of this
     * @param serializedFunctions a cache of serializedFunctions - the ownership of this map
     *        is <b>transferred</b> to this and will be modified in it
     */
    public SerializationContext(Collection<ExpressionFunction> functions, Map<String, String> bindings,
                                Map<String, String> serializedFunctions) {
        this(toMap(functions), bindings, serializedFunctions);
    }

    private static Map<String, ExpressionFunction> toMap(Collection<ExpressionFunction> list) {
        Map<String,ExpressionFunction> mapBuilder = new HashMap<>();
        for (ExpressionFunction function : list)
            mapBuilder.put(function.getName(), function);
        return Map.copyOf(mapBuilder);
    }

    /**
     * Create a context for a single serialization task
     *
     * @param functions the functions of this
     * @param bindings the arguments of this
     * @param serializedFunctions a cache of serializedFunctions - the ownership of this map
     *        is <b>transferred</b> to this and will be modified in it
     */
    public SerializationContext(Map<String,ExpressionFunction> functions, Map<String, String> bindings,
                                Map<String, String> serializedFunctions) {
        super(functions, bindings);
        this.serializedFunctions = serializedFunctions;
    }

    /** @deprecated Use {@link #SerializationContext(Map, Map, Map) instead}*/
    @Deprecated(forRemoval = true, since = "7")
    public SerializationContext(ImmutableMap<String,ExpressionFunction> functions, Map<String, String> bindings,
                                Map<String, String> serializedFunctions) {
        this((Map<String, ExpressionFunction>)functions, bindings, serializedFunctions);
    }

    /** Adds the serialization of a function */
    public void addFunctionSerialization(String name, String expressionString) {
        serializedFunctions.put(name, expressionString);
    }

    /** Adds the serialization of the an argument type to a function */
    public void addArgumentTypeSerialization(String functionName, String argumentName, TensorType type) {
        serializedFunctions.put("rankingExpression(" + functionName + ")." + argumentName + ".type", type.toString());
    }

    /** Adds the serialization of the return type of a function */
    public void addFunctionTypeSerialization(String functionName, TensorType type) {
        if (type.rank() == 0) return; // no explicit type implies scalar (aka rank 0 tensor)
        serializedFunctions.put("rankingExpression(" + functionName + ").type", type.toString());
    }

    @Override
    public SerializationContext withBindings(Map<String, String> bindings) {
        return new SerializationContext(getFunctions(), bindings, this.serializedFunctions);
    }

    /** Returns a fresh context without bindings */
    @Override
    public SerializationContext withoutBindings() {
        return new SerializationContext(getFunctions(), null, this.serializedFunctions);
    }

    public Map<String, String> serializedFunctions() { return serializedFunctions; }

    public boolean needSerialization(String functionName) {
        return ! serializedFunctions().containsKey(RankingExpression.propertyName(functionName));
    }

}
