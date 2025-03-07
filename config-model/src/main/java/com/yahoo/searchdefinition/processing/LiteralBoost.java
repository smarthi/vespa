// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition.processing;

import com.yahoo.config.application.api.DeployLogger;
import com.yahoo.searchdefinition.RankProfileRegistry;
import com.yahoo.searchdefinition.Schema;
import com.yahoo.searchdefinition.document.SDField;
import com.yahoo.searchdefinition.RankProfile;
import com.yahoo.vespa.model.container.search.QueryProfiles;

import java.util.Iterator;

/**
 * Expresses literal boosts in terms of extra indices with rank boost.
 * One extra index named <i>indexname</i>_exact is added for each index having
 * a fields with literal-boosts of zero or more (zero to support other
 * rank profiles setting a literal boost). Complete boost values in to fields
 * are translated to rank boosts to the implementation indices.
 * These indices has no positional
 * or phrase support and contains concatenated versions of each field value
 * of complete-boosted fields indexed to <i>indexname</i>. A search for indexname
 * will be rewritten to also search <i>indexname</i>_exaxt
 *
 * @author  bratseth
 */
public class LiteralBoost extends Processor {

    public LiteralBoost(Schema schema, DeployLogger deployLogger, RankProfileRegistry rankProfileRegistry, QueryProfiles queryProfiles) {
        super(schema, deployLogger, rankProfileRegistry, queryProfiles);
    }

    /** Adds extra search fields and indices to express literal boosts */
    @Override
    public void process(boolean validate, boolean documentsOnly) {
        checkRankModifierRankType(schema);
        addLiteralBoostsToFields(schema);
        reduceFieldLiteralBoosts(schema);
    }

    /** Checks if literal boost is given using rank: , and set the actual literal boost accordingly. */
    private void checkRankModifierRankType(Schema schema) {
        for (SDField field : schema.allConcreteFields()) {
            if (field.getLiteralBoost() > -1) continue; // Let explicit value take precedence
            if (field.getRanking().isLiteral())
                field.setLiteralBoost(100);
        }
    }

    /**
     * Ensures there are field boosts for all literal boosts mentioned in rank profiles.
     * This is required because boost indices will only be generated by looking
     * at field boosts
     */
    private void addLiteralBoostsToFields(Schema schema) {
        Iterator i = matchingRankSettingsIterator(schema, RankProfile.RankSetting.Type.LITERALBOOST);
        while (i.hasNext()) {
            RankProfile.RankSetting setting = (RankProfile.RankSetting)i.next();
            SDField field = schema.getConcreteField(setting.getFieldName());
            if (field == null) continue;
            if (field.getLiteralBoost() < 0)
                field.setLiteralBoost(0);
        }
    }

    private void reduceFieldLiteralBoosts(Schema schema) {
        for (SDField field : schema.allConcreteFields()) {
            if (field.getLiteralBoost() < 0) continue;
            reduceFieldLiteralBoost(field, schema);
        }
    }

    private void reduceFieldLiteralBoost(SDField field, Schema schema) {
        SDField literalField = addField(schema, field, "literal",
                                        "{ input " + field.getName() + " | tokenize | index " + field.getName() + "_literal; }",
                                        "literal-boost");
        literalField.setWeight(field.getWeight() + field.getLiteralBoost());
    }

}
