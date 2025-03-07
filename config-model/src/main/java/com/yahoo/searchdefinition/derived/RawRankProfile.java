// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition.derived;

import ai.vespa.rankingexpression.importer.configmodelview.ImportedMlModels;
import com.google.common.collect.ImmutableList;
import com.yahoo.collections.Pair;
import com.yahoo.compress.Compressor;
import com.yahoo.config.model.api.ModelContext;
import com.yahoo.search.query.profile.QueryProfileRegistry;
import com.yahoo.searchdefinition.OnnxModel;
import com.yahoo.searchdefinition.LargeRankExpressions;
import com.yahoo.searchdefinition.RankExpressionBody;
import com.yahoo.searchdefinition.document.RankType;
import com.yahoo.searchdefinition.RankProfile;
import com.yahoo.searchdefinition.expressiontransforms.OnnxModelTransformer;
import com.yahoo.searchlib.rankingexpression.ExpressionFunction;
import com.yahoo.searchlib.rankingexpression.RankingExpression;
import com.yahoo.searchlib.rankingexpression.parser.ParseException;
import com.yahoo.searchlib.rankingexpression.rule.ReferenceNode;
import com.yahoo.searchlib.rankingexpression.rule.SerializationContext;
import com.yahoo.tensor.TensorType;
import com.yahoo.vespa.config.search.RankProfilesConfig;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A rank profile derived from a search definition, containing exactly the features available natively in the server
 *
 * @author bratseth
 */
public class RawRankProfile implements RankProfilesConfig.Producer {

    /** A reusable compressor with default settings */
    private static final Compressor compressor = new Compressor();

    private static final String keyEndMarker = "\r=";
    private static final String valueEndMarker = "\r\n";

    // TODO: These are to expose coupling between the strings used here and elsewhere
    public static final String summaryFeatureFefPropertyPrefix = "vespa.summary.feature";
    public static final String matchFeatureFefPropertyPrefix = "vespa.match.feature";
    public static final String rankFeatureFefPropertyPrefix = "vespa.dump.feature";

    private final String name;

    private final Compressor.Compression compressedProperties;

    /**
     * Creates a raw rank profile from the given rank profile
     */
    public RawRankProfile(RankProfile rankProfile, LargeRankExpressions largeExpressions,
                          QueryProfileRegistry queryProfiles, ImportedMlModels importedModels,
                          AttributeFields attributeFields, ModelContext.Properties deployProperties) {
        this.name = rankProfile.name();
        compressedProperties = compress(new Deriver(rankProfile.compile(queryProfiles, importedModels),
                                                    attributeFields, deployProperties).derive(largeExpressions));
    }

    private Compressor.Compression compress(List<Pair<String, String>> properties) {
        StringBuilder b = new StringBuilder();
        for (Pair<String, String> property : properties)
            b.append(property.getFirst()).append(keyEndMarker).append(property.getSecond()).append(valueEndMarker);
        return compressor.compress(b.toString().getBytes(StandardCharsets.UTF_8));
    }

    private List<Pair<String, String>> decompress(Compressor.Compression compression) {
        String propertiesString = new String(compressor.decompress(compression), StandardCharsets.UTF_8);
        if (propertiesString.isEmpty()) return ImmutableList.of();

        ImmutableList.Builder<Pair<String, String>> properties = new ImmutableList.Builder<>();
        for (int pos = 0; pos < propertiesString.length();) {
            int keyEndPos = propertiesString.indexOf(keyEndMarker, pos);
            String key = propertiesString.substring(pos, keyEndPos);
            pos = keyEndPos + keyEndMarker.length();
            int valueEndPos = propertiesString.indexOf(valueEndMarker, pos);
            String value = propertiesString.substring(pos, valueEndPos);
            pos = valueEndPos + valueEndMarker.length();
            properties.add(new Pair<>(key, value));
        }
        return properties.build();
    }

    public String getName() { return name; }

    @Override
    public String toString() {
        return " rank profile " + name;
    }

    @Override
    public void getConfig(RankProfilesConfig.Builder builder) {
        RankProfilesConfig.Rankprofile.Builder b = new RankProfilesConfig.Rankprofile.Builder().name(getName());
        getRankProperties(b);
        builder.rankprofile(b);
    }

    private void getRankProperties(RankProfilesConfig.Rankprofile.Builder b) {
        RankProfilesConfig.Rankprofile.Fef.Builder fefB = new RankProfilesConfig.Rankprofile.Fef.Builder();
        for (Pair<String, String> p : decompress(compressedProperties))
            fefB.property(new RankProfilesConfig.Rankprofile.Fef.Property.Builder().name(p.getFirst()).value(p.getSecond()));
        b.fef(fefB);
    }

    /**
     * Returns the properties of this as an unmodifiable list.
     * Note: This method is expensive.
     */
    public List<Pair<String, String>> configProperties() { return decompress(compressedProperties); }

    private static class Deriver {

        private final Map<String, FieldRankSettings> fieldRankSettings = new java.util.LinkedHashMap<>();
        private final Set<ReferenceNode> summaryFeatures;
        private final Set<ReferenceNode> matchFeatures;
        private final Set<ReferenceNode> rankFeatures;
        private final List<RankProfile.RankProperty> rankProperties;

        /**
         * Rank properties for weight settings to make these available to feature executors
         */
        private final List<RankProfile.RankProperty> boostAndWeightRankProperties = new ArrayList<>();

        private final boolean ignoreDefaultRankFeatures;
        private final RankProfile.MatchPhaseSettings matchPhaseSettings;
        private final int rerankCount;
        private final int keepRankCount;
        private final int numThreadsPerSearch;
        private final int minHitsPerThread;
        private final int numSearchPartitions;
        private final double termwiseLimit;
        private final double rankScoreDropLimit;

        /**
         * The rank type definitions used to derive settings for the native rank features
         */
        private final NativeRankTypeDefinitionSet nativeRankTypeDefinitions = new NativeRankTypeDefinitionSet("default");
        private final Map<String, String> attributeTypes;
        private final Map<String, String> queryFeatureTypes;
        private final Set<String> filterFields = new java.util.LinkedHashSet<>();
        private final String rankprofileName;

        private RankingExpression firstPhaseRanking;
        private RankingExpression secondPhaseRanking;

        /**
         * Creates a raw rank profile from the given rank profile
         */
        Deriver(RankProfile compiled, AttributeFields attributeFields, ModelContext.Properties deployProperties) {
            rankprofileName = compiled.name();
            attributeTypes = compiled.getAttributeTypes();
            queryFeatureTypes = compiled.getQueryFeatureTypes();
            firstPhaseRanking = compiled.getFirstPhaseRanking();
            secondPhaseRanking = compiled.getSecondPhaseRanking();
            summaryFeatures = new LinkedHashSet<>(compiled.getSummaryFeatures());
            matchFeatures = new LinkedHashSet<>(compiled.getMatchFeatures());
            rankFeatures = compiled.getRankFeatures();
            rerankCount = compiled.getRerankCount();
            matchPhaseSettings = compiled.getMatchPhaseSettings();
            numThreadsPerSearch = compiled.getNumThreadsPerSearch();
            minHitsPerThread = compiled.getMinHitsPerThread();
            numSearchPartitions = compiled.getNumSearchPartitions();
            termwiseLimit = compiled.getTermwiseLimit().orElse(deployProperties.featureFlags().defaultTermwiseLimit());
            keepRankCount = compiled.getKeepRankCount();
            rankScoreDropLimit = compiled.getRankScoreDropLimit();
            ignoreDefaultRankFeatures = compiled.getIgnoreDefaultRankFeatures();
            rankProperties = new ArrayList<>(compiled.getRankProperties());

            Map<String, RankProfile.RankingExpressionFunction> functions = compiled.getFunctions();
            List<ExpressionFunction> functionExpressions = functions.values().stream().map(f -> f.function()).collect(Collectors.toList());
            Map<String, String> functionProperties = new LinkedHashMap<>();
            SerializationContext functionSerializationContext = new SerializationContext(functionExpressions);

            if (firstPhaseRanking != null) {
                functionProperties.putAll(firstPhaseRanking.getRankProperties(functionSerializationContext));
            }
            if (secondPhaseRanking != null) {
                functionProperties.putAll(secondPhaseRanking.getRankProperties(functionSerializationContext));
            }

            derivePropertiesAndFeaturesFromFunctions(functions, functionProperties, functionSerializationContext);
            deriveOnnxModelFunctionsAndFeatures(compiled);

            deriveRankTypeSetting(compiled, attributeFields);
            deriveFilterFields(compiled);
            deriveWeightProperties(compiled);
        }

        private void deriveFilterFields(RankProfile rp) {
            filterFields.addAll(rp.allFilterFields());
        }

        private void derivePropertiesAndFeaturesFromFunctions(Map<String, RankProfile.RankingExpressionFunction> functions,
                                                                     Map<String, String> functionProperties,
                                                                     SerializationContext functionContext) {
            if (functions.isEmpty()) return;

            replaceFunctionFeatures(summaryFeatures, functionContext);
            replaceFunctionFeatures(matchFeatures, functionContext);

            // First phase, second phase and summary features should add all required functions to the context.
            // However, we need to add any functions not referenced in those anyway for model-evaluation.
            deriveFunctionProperties(functions, functionProperties, functionContext);

            for (Map.Entry<String, String> e : functionProperties.entrySet()) {
                rankProperties.add(new RankProfile.RankProperty(e.getKey(), e.getValue()));
            }
        }

        private void deriveFunctionProperties(Map<String, RankProfile.RankingExpressionFunction> functions,
                                              Map<String, String> functionProperties,
                                              SerializationContext context) {
            for (Map.Entry<String, RankProfile.RankingExpressionFunction> e : functions.entrySet()) {
                String propertyName = RankingExpression.propertyName(e.getKey());
                if (context.serializedFunctions().containsKey(propertyName)) continue;

                String expressionString = e.getValue().function().getBody().getRoot().toString(context).toString();

                context.addFunctionSerialization(propertyName, expressionString);
                for (Map.Entry<String, TensorType> argumentType : e.getValue().function().argumentTypes().entrySet())
                    context.addArgumentTypeSerialization(e.getKey(), argumentType.getKey(), argumentType.getValue());
                if (e.getValue().function().returnType().isPresent())
                    context.addFunctionTypeSerialization(e.getKey(), e.getValue().function().returnType().get());
                // else if (e.getValue().function().arguments().isEmpty()) TODO: Enable this check when we resolve all types
                //     throw new IllegalStateException("Type of function '" + e.getKey() + "' is not resolved");
            }
            functionProperties.putAll(context.serializedFunctions());
        }

        private void replaceFunctionFeatures(Set<ReferenceNode> features, SerializationContext context) {
            if (features == null) return;
            Map<String, ReferenceNode> functionFeatures = new LinkedHashMap<>();
            for (Iterator<ReferenceNode> i = features.iterator(); i.hasNext(); ) {
                ReferenceNode referenceNode = i.next();
                // Is the feature a function?
                ExpressionFunction function = context.getFunction(referenceNode.getName());
                if (function != null) {
                    String propertyName = RankingExpression.propertyName(referenceNode.getName());
                    String expressionString = function.getBody().getRoot().toString(context).toString();
                    context.addFunctionSerialization(propertyName, expressionString);
                    ReferenceNode newReferenceNode = new ReferenceNode("rankingExpression(" + referenceNode.getName() + ")", referenceNode.getArguments().expressions(), referenceNode.getOutput());
                    functionFeatures.put(referenceNode.getName(), newReferenceNode);
                    i.remove(); // Will add the expanded one in next block
                }
            }
            // Then, replace the features that were functions
            for (Map.Entry<String, ReferenceNode> e : functionFeatures.entrySet()) {
                features.add(e.getValue());
            }
        }

        private void deriveWeightProperties(RankProfile rankProfile) {

            for (RankProfile.RankSetting setting : rankProfile.rankSettings()) {
                if (!setting.getType().equals(RankProfile.RankSetting.Type.WEIGHT)) {
                    continue;
                }
                boostAndWeightRankProperties.add(new RankProfile.RankProperty("vespa.fieldweight." + setting.getFieldName(),
                        String.valueOf(setting.getIntValue())));
            }
        }

        /**
         * Adds the type boosts from a rank profile
         */
        private void deriveRankTypeSetting(RankProfile rankProfile, AttributeFields attributeFields) {
            for (Iterator<RankProfile.RankSetting> i = rankProfile.rankSettingIterator(); i.hasNext(); ) {
                RankProfile.RankSetting setting = i.next();
                if (setting.getType() != RankProfile.RankSetting.Type.RANKTYPE) continue;

                deriveNativeRankTypeSetting(setting.getFieldName(), (RankType) setting.getValue(), attributeFields,
                                            hasDefaultRankTypeSetting(rankProfile, setting.getFieldName()));
            }
        }

        private void deriveNativeRankTypeSetting(String fieldName, RankType rankType, AttributeFields attributeFields,
                                                 boolean isDefaultSetting) {
            if (isDefaultSetting) return;

            NativeRankTypeDefinition definition = nativeRankTypeDefinitions.getRankTypeDefinition(rankType);
            if (definition == null) throw new IllegalArgumentException("In field '" + fieldName + "': " +
                                                                       rankType + " is known but has no implementation. " +
                                                                       "Supported rank types: " +
                                                                       nativeRankTypeDefinitions.types().keySet());

            FieldRankSettings settings = deriveFieldRankSettings(fieldName);
            for (Iterator<NativeTable> i = definition.rankSettingIterator(); i.hasNext(); ) {
                NativeTable table = i.next();
                // only add index field tables if we are processing an index field and
                // only add attribute field tables if we are processing an attribute field
                if ((FieldRankSettings.isIndexFieldTable(table) && attributeFields.getAttribute(fieldName) == null) ||
                    (FieldRankSettings.isAttributeFieldTable(table) && attributeFields.getAttribute(fieldName) != null)) {
                    settings.addTable(table);
                }
            }
        }

        private boolean hasDefaultRankTypeSetting(RankProfile rankProfile, String fieldName) {
            RankProfile.RankSetting setting =
                    rankProfile.getRankSetting(fieldName, RankProfile.RankSetting.Type.RANKTYPE);
            return setting != null && setting.getValue().equals(RankType.DEFAULT);
        }

        private FieldRankSettings deriveFieldRankSettings(String fieldName) {
            FieldRankSettings settings = fieldRankSettings.get(fieldName);
            if (settings == null) {
                settings = new FieldRankSettings(fieldName);
                fieldRankSettings.put(fieldName, settings);
            }
            return settings;
        }

        /** Derives the properties this produces */
        public List<Pair<String, String>> derive(LargeRankExpressions largeRankExpressions) {
            List<Pair<String, String>>  properties = new ArrayList<>();
            for (RankProfile.RankProperty property : rankProperties) {
                if (RankingExpression.propertyName(RankProfile.FIRST_PHASE).equals(property.getName())) {
                    // Could have been set by function expansion. Set expressions, then skip this property.
                    try {
                        firstPhaseRanking = new RankingExpression(property.getValue());
                    } catch (ParseException e) {
                        throw new IllegalArgumentException("Could not parse first phase expression", e);
                    }
                }
                else if (RankingExpression.propertyName(RankProfile.SECOND_PHASE).equals(property.getName())) {
                    try {
                        secondPhaseRanking = new RankingExpression(property.getValue());
                    } catch (ParseException e) {
                        throw new IllegalArgumentException("Could not parse second phase expression", e);
                    }
                }
                else {
                    properties.add(new Pair<>(property.getName(), property.getValue()));
                }
            }
            properties.addAll(deriveRankingPhaseRankProperties(firstPhaseRanking, RankProfile.FIRST_PHASE));
            properties.addAll(deriveRankingPhaseRankProperties(secondPhaseRanking, RankProfile.SECOND_PHASE));
            for (FieldRankSettings settings : fieldRankSettings.values()) {
                properties.addAll(settings.deriveRankProperties());
            }
            for (RankProfile.RankProperty property : boostAndWeightRankProperties) {
                properties.add(new Pair<>(property.getName(), property.getValue()));
            }
            for (ReferenceNode feature : summaryFeatures) {
                properties.add(new Pair<>(summaryFeatureFefPropertyPrefix, feature.toString()));
            }
            for (ReferenceNode feature : matchFeatures) {
                properties.add(new Pair<>(matchFeatureFefPropertyPrefix, feature.toString()));
            }
            for (ReferenceNode feature : rankFeatures) {
                properties.add(new Pair<>(rankFeatureFefPropertyPrefix, feature.toString()));
            }
            if (numThreadsPerSearch > 0) {
                properties.add(new Pair<>("vespa.matching.numthreadspersearch", numThreadsPerSearch + ""));
            }
            if (minHitsPerThread > 0) {
                properties.add(new Pair<>("vespa.matching.minhitsperthread", minHitsPerThread + ""));
            }
            if (numSearchPartitions >= 0) {
                properties.add(new Pair<>("vespa.matching.numsearchpartitions", numSearchPartitions + ""));
            }
            if (termwiseLimit < 1.0) {
                properties.add(new Pair<>("vespa.matching.termwise_limit", termwiseLimit + ""));
            }
            if (matchPhaseSettings != null) {
                properties.add(new Pair<>("vespa.matchphase.degradation.attribute", matchPhaseSettings.getAttribute()));
                properties.add(new Pair<>("vespa.matchphase.degradation.ascendingorder", matchPhaseSettings.getAscending() + ""));
                properties.add(new Pair<>("vespa.matchphase.degradation.maxhits", matchPhaseSettings.getMaxHits() + ""));
                properties.add(new Pair<>("vespa.matchphase.degradation.maxfiltercoverage", matchPhaseSettings.getMaxFilterCoverage() + ""));
                properties.add(new Pair<>("vespa.matchphase.degradation.samplepercentage", matchPhaseSettings.getEvaluationPoint() + ""));
                properties.add(new Pair<>("vespa.matchphase.degradation.postfiltermultiplier", matchPhaseSettings.getPrePostFilterTippingPoint() + ""));
                RankProfile.DiversitySettings diversitySettings = matchPhaseSettings.getDiversity();
                if (diversitySettings != null) {
                    properties.add(new Pair<>("vespa.matchphase.diversity.attribute", diversitySettings.getAttribute()));
                    properties.add(new Pair<>("vespa.matchphase.diversity.mingroups", String.valueOf(diversitySettings.getMinGroups())));
                    properties.add(new Pair<>("vespa.matchphase.diversity.cutoff.factor", String.valueOf(diversitySettings.getCutoffFactor())));
                    properties.add(new Pair<>("vespa.matchphase.diversity.cutoff.strategy", String.valueOf(diversitySettings.getCutoffStrategy())));
                }
            }
            if (rerankCount > -1) {
                properties.add(new Pair<>("vespa.hitcollector.heapsize", rerankCount + ""));
            }
            if (keepRankCount > -1) {
                properties.add(new Pair<>("vespa.hitcollector.arraysize", keepRankCount + ""));
            }
            if (rankScoreDropLimit > -Double.MAX_VALUE) {
                properties.add(new Pair<>("vespa.hitcollector.rankscoredroplimit", rankScoreDropLimit + ""));
            }
            if (ignoreDefaultRankFeatures) {
                properties.add(new Pair<>("vespa.dump.ignoredefaultfeatures", String.valueOf(true)));
            }
            for (String fieldName : filterFields) {
                properties.add(new Pair<>("vespa.isfilterfield." + fieldName, String.valueOf(true)));
            }
            for (Map.Entry<String, String> attributeType : attributeTypes.entrySet()) {
                properties.add(new Pair<>("vespa.type.attribute." + attributeType.getKey(), attributeType.getValue()));
            }
            for (Map.Entry<String, String> queryFeatureType : queryFeatureTypes.entrySet()) {
                properties.add(new Pair<>("vespa.type.query." + queryFeatureType.getKey(), queryFeatureType.getValue()));
            }
            if (properties.size() >= 1000000) throw new IllegalArgumentException("Too many rank properties");
            distributeLargeExpressionsAsFiles(properties, largeRankExpressions);
            return properties;
        }

        private void distributeLargeExpressionsAsFiles(List<Pair<String, String>> properties, LargeRankExpressions largeRankExpressions) {
            for (ListIterator<Pair<String, String>> iter = properties.listIterator(); iter.hasNext();) {
                Pair<String, String> property = iter.next();
                String expression = property.getSecond();
                if (expression.length() > largeRankExpressions.limit()) {
                    String propertyName = property.getFirst();
                    String functionName = RankingExpression.extractScriptName(propertyName);
                    if (functionName != null) {
                        String mangledName = rankprofileName + "." + functionName;
                        largeRankExpressions.add(new RankExpressionBody(mangledName, ByteBuffer.wrap(expression.getBytes(StandardCharsets.UTF_8))));
                        iter.set(new Pair<>(RankingExpression.propertyExpressionName(functionName), mangledName));
                    }
                }
            }
        }

        private List<Pair<String, String>> deriveRankingPhaseRankProperties(RankingExpression expression, String phase) {
            List<Pair<String, String>> properties = new ArrayList<>();
            if (expression == null) return properties;

            String name = expression.getName();
            if ("".equals(name))
                name = phase;

            if (expression.getRoot() instanceof ReferenceNode) {
                properties.add(new Pair<>("vespa.rank." + phase, expression.getRoot().toString()));
            } else {
                properties.add(new Pair<>("vespa.rank." + phase, "rankingExpression(" + name + ")"));
                properties.add(new Pair<>(RankingExpression.propertyName(name), expression.getRoot().toString()));
            }
            return properties;
        }

        private void deriveOnnxModelFunctionsAndFeatures(RankProfile rankProfile) {
            if (rankProfile.schema() == null) return;
            if (rankProfile.schema().onnxModels().asMap().isEmpty()) return;
            replaceOnnxFunctionInputs(rankProfile);
            replaceImplicitOnnxConfigFeatures(summaryFeatures, rankProfile);
            replaceImplicitOnnxConfigFeatures(matchFeatures, rankProfile);
        }

        private void replaceOnnxFunctionInputs(RankProfile rankProfile) {
            Set<String> functionNames = rankProfile.getFunctions().keySet();
            if (functionNames.isEmpty()) return;
            for (OnnxModel onnxModel: rankProfile.schema().onnxModels().asMap().values()) {
                for (Map.Entry<String, String> mapping : onnxModel.getInputMap().entrySet()) {
                    String source = mapping.getValue();
                    if (functionNames.contains(source)) {
                        onnxModel.addInputNameMapping(mapping.getKey(), "rankingExpression(" + source + ")");
                    }
                }
            }
        }

        private void replaceImplicitOnnxConfigFeatures(Set<ReferenceNode> features, RankProfile rankProfile) {
            if (features == null || features.isEmpty()) return;
            Set<ReferenceNode> replacedFeatures = new HashSet<>();
            for (Iterator<ReferenceNode> i = features.iterator(); i.hasNext(); ) {
                ReferenceNode referenceNode = i.next();
                ReferenceNode replacedNode = (ReferenceNode) OnnxModelTransformer.transformFeature(referenceNode, rankProfile);
                if (referenceNode != replacedNode) {
                    replacedFeatures.add(replacedNode);
                    i.remove();
                }
            }
            features.addAll(replacedFeatures);
        }

    }

}
