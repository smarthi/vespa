// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition.processing;

import com.yahoo.config.application.api.DeployLogger;
import com.yahoo.searchdefinition.RankProfileRegistry;
import com.yahoo.searchdefinition.Schema;
import com.yahoo.searchdefinition.document.Matching;
import com.yahoo.searchdefinition.document.Matching.Type;
import com.yahoo.searchdefinition.document.SDField;
import com.yahoo.vespa.indexinglanguage.ExpressionVisitor;
import com.yahoo.vespa.indexinglanguage.expressions.Expression;
import com.yahoo.vespa.indexinglanguage.expressions.IndexExpression;
import com.yahoo.vespa.model.container.search.QueryProfiles;

import java.util.HashMap;
import java.util.Map;

/**
 * Warn on inconsistent match settings for any index
 *
 * @author vegardh
 */
public class MatchConsistency extends Processor {

    public MatchConsistency(Schema schema,
                            DeployLogger deployLogger,
                            RankProfileRegistry rankProfileRegistry,
                            QueryProfiles queryProfiles) {
        super(schema, deployLogger, rankProfileRegistry, queryProfiles);
    }

    @Override
    public void process(boolean validate, boolean documentsOnly) {
        if ( ! validate) return;

        Map<String, Matching.Type> types = new HashMap<>();
        for (SDField field : schema.allConcreteFields()) {
            new MyVisitor(schema, field, types).visit(field.getIndexingScript());
        }
    }

    private void checkMatching(Schema schema, SDField field, Map<String, Type> types, String indexTo) {
        Type prevType = types.get(indexTo);
        if (prevType == null) {
            types.put(indexTo, field.getMatching().getType());
        } else if ( ! field.getMatching().getType().equals(prevType)) {
            warn(schema, field, "The matching type for index '" + indexTo + "' (got " + field.getMatching().getType() +
                                ") is inconsistent with that given for the same index in a previous field (had " +
                                prevType + ").");
        }
    }

    private class MyVisitor extends ExpressionVisitor {

        final Schema schema;
        final SDField field;
        final Map<String, Type> types;

        MyVisitor(Schema schema, SDField field, Map<String, Type> types) {
            this.schema = schema;
            this.field = field;
            this.types = types;
        }

        @Override
        protected void doVisit(Expression exp) {
            if (exp instanceof IndexExpression) {
                checkMatching(schema, field, types, ((IndexExpression)exp).getFieldName());
            }
        }
    }

}
