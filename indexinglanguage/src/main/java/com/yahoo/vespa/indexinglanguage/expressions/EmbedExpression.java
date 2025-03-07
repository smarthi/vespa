// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.indexinglanguage.expressions;

import com.yahoo.document.ArrayDataType;
import com.yahoo.document.DataType;
import com.yahoo.document.DocumentType;
import com.yahoo.document.Field;
import com.yahoo.document.TensorDataType;
import com.yahoo.document.datatypes.StringFieldValue;
import com.yahoo.document.datatypes.TensorFieldValue;
import com.yahoo.language.process.Embedder;
import com.yahoo.tensor.Tensor;
import com.yahoo.tensor.TensorType;

/**
 * Embeds a string in a tensor space using the configured Embedder component
 *
 * @author bratseth
 */
public class EmbedExpression extends Expression  {

    private final Embedder embedder;

    /** The destination the embedding will be written to on the form [schema name].[field name] */
    private String destination;

    /** The target type we are embedding into. */
    private TensorType targetType;

    public EmbedExpression(Embedder embedder) {
        super(DataType.STRING);
        this.embedder = embedder;
    }

    @Override
    public void setStatementOutput(DocumentType documentType, Field field) {
        targetType = toTargetTensor(field.getDataType());
        destination = documentType.getName() + "." + field.getName();
    }

    @Override
    protected void doExecute(ExecutionContext context) {
        StringFieldValue input = (StringFieldValue) context.getValue();
        Tensor tensor = embedder.embed(input.getString(),
                                       new Embedder.Context(destination).setLanguage(context.getLanguage()),
                                       targetType);
        context.setValue(new TensorFieldValue(tensor));
    }

    @Override
    protected void doVerify(VerificationContext context) {
        String outputField = context.getOutputField();
        if (outputField == null)
            throw new VerificationException(this, "No output field in this statement: " +
                                                  "Don't know what tensor type to embed into.");
        targetType = toTargetTensor(context.getInputType(this, outputField));
        context.setValueType(createdOutputType());
    }

    @Override
    public DataType createdOutputType() {
        return new TensorDataType(targetType);
    }

    private static TensorType toTargetTensor(DataType dataType) {
        if (dataType instanceof ArrayDataType) return toTargetTensor(((ArrayDataType) dataType).getNestedType());
        if  ( ! ( dataType instanceof TensorDataType))
            throw new IllegalArgumentException("Expected a tensor data type but got " + dataType);
        return ((TensorDataType)dataType).getTensorType();

    }

    @Override
    public String toString() { return "embed"; }

    @Override
    public int hashCode() { return 1; }

    @Override
    public boolean equals(Object o) { return o instanceof EmbedExpression; }

}
