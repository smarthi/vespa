// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "querytermdata.h"
#include "searchenvironment.h"
#include "searchvisitor.h"
#include <vespa/persistence/spi/docentry.h>
#include <vespa/document/datatype/positiondatatype.h>
#include <vespa/document/datatype/documenttype.h>
#include <vespa/document/datatype/weightedsetdatatype.h>
#include <vespa/document/datatype/mapdatatype.h>
#include <vespa/searchlib/aggregation/modifiers.h>
#include <vespa/searchlib/common/packets.h>
#include <vespa/searchlib/uca/ucaconverter.h>
#include <vespa/searchlib/features/setup.h>
#include <vespa/vespalib/geo/zcurve.h>
#include <vespa/vespalib/objects/nbostream.h>
#include <vespa/vespalib/util/exceptions.h>
#include <vespa/vespalib/util/size_literals.h>
#include <vespa/fnet/databuffer.h>
#include "matching_elements_filler.h"

#include <vespa/log/log.h>
LOG_SETUP(".visitor.instance.searchvisitor");

namespace streaming {

using document::DataType;
using document::PositionDataType;
using search::AttributeGuard;
using search::AttributeVector;
using search::aggregation::HitsAggregationResult;
using search::attribute::IAttributeVector;
using search::expression::ConfigureStaticParams;
using search::streaming::Query;
using search::streaming::QueryTermList;
using storage::StorageComponent;
using storage::VisitorEnvironment;
using vdslib::Parameters;
using vsm::DocsumFilter;
using vsm::FieldPath;
using vsm::StorageDocument;
using vsm::StringFieldIdTMap;

class ForceWordfolderInit
{
public:
    ForceWordfolderInit();
};

ForceWordfolderInit::ForceWordfolderInit()
{
    Fast_NormalizeWordFolder::Setup(Fast_NormalizeWordFolder::DO_ACCENT_REMOVAL |
                                    Fast_NormalizeWordFolder::DO_SHARP_S_SUBSTITUTION |
                                    Fast_NormalizeWordFolder::DO_LIGATURE_SUBSTITUTION |
                                    Fast_NormalizeWordFolder::DO_MULTICHAR_EXPANSION);
}

static ForceWordfolderInit _G_forceNormWordFolderInit;

// Leftovers from FS4 protocol with limited use here.
enum queryflags {
    QFLAG_DUMP_FEATURES        = 0x00040000
};


AttributeVector::SP
createMultiValueAttribute(const vespalib::string & name, const document::FieldValue & fv, bool arrayType)
{
    const DataType * ndt = fv.getDataType();
    if (ndt->inherits(document::CollectionDataType::classId)) {
        ndt = &(static_cast<const document::CollectionDataType *>(ndt))->getNestedType();
    }
    LOG(debug, "Create %s attribute '%s' with data type '%s' (%s)",
        arrayType ? "array" : "weighted set", name.c_str(), ndt->getName().c_str(), fv.getClass().name());
    if (ndt->getId() == DataType::T_BYTE ||
        ndt->getId() == DataType::T_INT ||
        ndt->getId() == DataType::T_LONG)
    {
        return arrayType ? std::make_shared<search::MultiIntegerExtAttribute>(name)
                         : std::make_shared<search::WeightedSetIntegerExtAttribute>(name);
    } else if (ndt->getId() == DataType::T_DOUBLE ||
               ndt->getId() == DataType::T_FLOAT)
    {
        return arrayType ? std::make_shared<search::MultiFloatExtAttribute>(name)
                         : std::make_shared<search::WeightedSetFloatExtAttribute>(name);
    } else if (ndt->getId() == DataType::T_STRING) {
        return arrayType ? std::make_shared<search::MultiStringExtAttribute>(name)
                         : std::make_shared<search::WeightedSetStringExtAttribute>(name);
    } else {
        LOG(debug, "Can not make an multivalue attribute out of %s with data type '%s' (%s)",
            name.c_str(), ndt->getName().c_str(), fv.getClass().name());
    }
    return AttributeVector::SP();
}

AttributeVector::SP
createAttribute(const vespalib::string & name, const document::FieldValue & fv)
{
    LOG(debug, "Create single value attribute '%s' with value type '%s'", name.c_str(), fv.getClass().name());
    if (fv.inherits(document::ByteFieldValue::classId) || fv.inherits(document::IntFieldValue::classId) || fv.inherits(document::LongFieldValue::classId)) {
        return std::make_shared<search::SingleIntegerExtAttribute>(name);
    } else if (fv.inherits(document::DoubleFieldValue::classId) || fv.inherits(document::FloatFieldValue::classId)) {
        return std::make_shared<search::SingleFloatExtAttribute>(name);
    } else if (fv.inherits(document::StringFieldValue::classId)) {
        return std::make_shared<search::SingleStringExtAttribute>(name);
    } else {
        LOG(debug, "Can not make an attribute out of %s of type '%s'.", name.c_str(), fv.getClass().name());
    }
    return AttributeVector::SP();
}

SearchVisitor::SummaryGenerator::SummaryGenerator() :
    HitsAggregationResult::SummaryGenerator(),
    _callback(),
    _docsumState(_callback),
    _docsumFilter(),
    _docsumWriter(nullptr),
    _rawBuf(4_Ki)
{
}

SearchVisitor::SummaryGenerator::~SummaryGenerator() = default;


vespalib::ConstBufferRef
SearchVisitor::SummaryGenerator::fillSummary(AttributeVector::DocId lid, const HitsAggregationResult::SummaryClassType & summaryClass)
{
    if (_docsumWriter != nullptr) {
        _rawBuf.reset();
        _docsumState._args.setResultClassName(summaryClass);
        uint32_t docsumLen = _docsumWriter->WriteDocsum(lid, &_docsumState, _docsumFilter.get(), &_rawBuf);
        return vespalib::ConstBufferRef(_rawBuf.GetDrainPos(), docsumLen);
    }
    return vespalib::ConstBufferRef();
}

void SearchVisitor::HitsResultPreparator::execute(vespalib::Identifiable & obj)
{
    auto & hitsAggr(static_cast<HitsAggregationResult &>(obj));
    hitsAggr.setSummaryGenerator(_summaryGenerator);
    _numHitsAggregators++;
}

bool SearchVisitor::HitsResultPreparator::check(const vespalib::Identifiable & obj) const
{
    return obj.getClass().inherits(HitsAggregationResult::classId);
}

SearchVisitor::GroupingEntry::GroupingEntry(Grouping * grouping) :
    _grouping(grouping),
    _count(0),
    _limit(grouping->getMaxN(std::numeric_limits<size_t>::max()))
{
}

SearchVisitor::GroupingEntry::~GroupingEntry() = default;

void SearchVisitor::GroupingEntry::aggregate(const document::Document & doc, search::HitRank rank)
{
    if (_count < _limit) {
        _grouping->aggregate(doc, rank);
        _count++;
    }
}

SearchVisitor::~SearchVisitor() {
    if (! isCompletedCalled()) {
        HitCounter hc;
        completedVisitingInternal(hc);
    }
}

SearchVisitor::SearchVisitor(StorageComponent& component,
                             VisitorEnvironment& vEnv,
                             const Parameters& params) :
    Visitor(component),
    _env(dynamic_cast<SearchEnvironment &>(vEnv)),
    _params(params),
    _vsmAdapter(nullptr),
    _docSearchedCount(0),
    _hitCount(0),
    _hitsRejectedCount(0),
    _query(),
    _queryResult(std::make_unique<documentapi::QueryResultMessage>()),
    _fieldSearcherMap(),
    _docTypeMapping(),
    _fieldSearchSpecMap(),
    _snippetModifierManager(),
    _summaryGenerator(),
    _summaryClass("default"),
    _attrMan(),
    _attrCtx(_attrMan.createContext()),
    _groupingList(),
    _attributeFields(),
    _sortList(),
    _searchBuffer(std::make_shared<vsm::SearcherBuf>()),
    _tmpSortBuffer(256),
    _documentIdAttributeBacking(std::make_shared<search::SingleStringExtAttribute>("[docid]") ),
    _rankAttributeBacking(std::make_shared<search::SingleFloatExtAttribute>("[rank]") ),
    _documentIdAttribute(dynamic_cast<search::SingleStringExtAttribute &>(*_documentIdAttributeBacking)),
    _rankAttribute(dynamic_cast<search::SingleFloatExtAttribute &>(*_rankAttributeBacking)),
    _shouldFillRankAttribute(false),
    _syntheticFieldsController(),
    _rankController()
{
    LOG(debug, "Created SearchVisitor");
}

void SearchVisitor::init(const Parameters & params)
{
    VISITOR_TRACE(6, "About to lazily init VSM adapter");
    _attrMan.add(_documentIdAttributeBacking);
    _attrMan.add(_rankAttributeBacking);
    Parameters::ValueRef valueRef;
    if ( params.lookup("summaryclass", valueRef) ) {
        _summaryClass = vespalib::string(valueRef.data(), valueRef.size());
        LOG(debug, "Received summary class: %s", _summaryClass.c_str());
    }

    size_t wantedSummaryCount(10);
    if (params.lookup("summarycount", valueRef) ) {
        vespalib::string tmp(valueRef.data(), valueRef.size());
        wantedSummaryCount = strtoul(tmp.c_str(), nullptr, 0);
        LOG(debug, "Received summary count: %ld", wantedSummaryCount);
    }
    _queryResult->getSearchResult().setWantedHitCount(wantedSummaryCount);

    if (params.lookup("rankprofile", valueRef) ) {
        vespalib::string tmp(valueRef.data(), valueRef.size());
        _rankController.setRankProfile(tmp);
        LOG(debug, "Received rank profile: %s", _rankController.getRankProfile().c_str());
    }

    int queryFlags = params.get("queryflags", 0);
    if (queryFlags) {
        bool dumpFeatures = (queryFlags & QFLAG_DUMP_FEATURES) != 0;
        _summaryGenerator.getDocsumState()._args.dumpFeatures(dumpFeatures);
        _rankController.setDumpFeatures(dumpFeatures);
        LOG(debug, "QFLAG_DUMP_FEATURES: %s", _rankController.getDumpFeatures() ? "true" : "false");
    }

    if (params.lookup("rankproperties", valueRef) && ! valueRef.empty()) {
        LOG(spam, "Received rank properties of %zd bytes", valueRef.size());
        uint32_t len = valueRef.size();
        char * data = const_cast<char *>(valueRef.data());
        FNET_DataBuffer src(data, len);
        uint32_t cnt = src.ReadInt32();
        len -= sizeof(uint32_t);
        LOG(debug, "Properties count: '%u'", cnt);
        for (uint32_t i = 0; i < cnt; ++i) {
            search::fs4transport::FS4Properties prop;
            if (!prop.decode(src, len)) {
                LOG(warning, "Could not decode rank properties");
            } else {
                LOG(debug, "Properties[%u]: name '%s', size '%u'", i, prop.getName(), prop.size());
                if (strcmp(prop.getName(), "rank") == 0) { // pick up rank properties
                    for (uint32_t j = 0; j < prop.size(); ++j) {
                        LOG(debug, "Properties[%u][%u]: key '%s' -> value '%s'", i, j, prop.getKey(j), prop.getValue(j));
                        _rankController.getQueryProperties().add(vespalib::string(prop.getKey(j), prop.getKeyLen(j)),
                                                                 vespalib::string(prop.getValue(j), prop.getValueLen(j)));
                    }
                }
            }
        }
    } else {
        LOG(debug, "No rank properties received");
    }

    vespalib::string location;
    if (params.lookup("location", valueRef)) {
        location = vespalib::string(valueRef.data(), valueRef.size());
        LOG(debug, "Location = '%s'", location.c_str());
        _summaryGenerator.getDocsumState()._args.setLocation(valueRef);
    }

    Parameters::ValueRef searchClusterBlob;
    if (params.lookup("searchcluster", searchClusterBlob)) {
        LOG(spam, "Received searchcluster blob of %zd bytes", searchClusterBlob.size());
        vespalib::string searchCluster(searchClusterBlob.data(), searchClusterBlob.size());
        _vsmAdapter = _env.getVSMAdapter(searchCluster);

        if ( params.lookup("sort", valueRef) ) {
            search::uca::UcaConverterFactory ucaFactory;
            _sortSpec = search::common::SortSpec(vespalib::string(valueRef.data(), valueRef.size()), ucaFactory);
            LOG(debug, "Received sort specification: '%s'", _sortSpec.getSpec().c_str());
        }

        Parameters::ValueRef queryBlob;
        if ( params.lookup("query", queryBlob) ) {
            LOG(spam, "Received query blob of %zu bytes", queryBlob.size());
            VISITOR_TRACE(9, vespalib::make_string("Setting up for query blob of %zu bytes", queryBlob.size()));
            QueryTermDataFactory addOnFactory;
            _query = Query(addOnFactory, search::QueryPacketT(queryBlob.data(), queryBlob.size()));
            _searchBuffer->reserve(0x10000);

            int stackCount = 0;
            if (params.get("querystackcount", stackCount)) {
                _summaryGenerator.getDocsumState()._args.SetStackDump(queryBlob.size(), (const char*)queryBlob.data());
            } else {
                LOG(warning, "Request without query stack count");
            }

            std::vector<vespalib::string> additionalFields;
            registerAdditionalFields(_vsmAdapter->getDocsumTools()->getFieldSpecs(), additionalFields);

            StringFieldIdTMap fieldsInQuery;
            setupFieldSearchers(additionalFields, fieldsInQuery);

            setupSnippetModifiers();

            setupScratchDocument(fieldsInQuery);

            _syntheticFieldsController.setup(_fieldSearchSpecMap.nameIdMap(), fieldsInQuery);

            setupAttributeVectors();

            setupAttributeVectorsForSorting(_sortSpec);

            const RankManager * rm = _env.getRankManager(searchCluster);
            _rankController.setRankManagerSnapshot(rm->getSnapshot());
            _rankController.setupRankProcessors(_query, location, wantedSummaryCount, _attrMan, _attributeFields);
            // Depends on hitCollector setup.
            setupDocsumObjects();

        } else {
            LOG(warning, "No query received");
        }

        if (params.lookup("aggregation", valueRef) ) {
            std::vector<char> newAggrBlob;
            newAggrBlob.resize(valueRef.size());
            memcpy(&newAggrBlob[0], valueRef.data(), newAggrBlob.size());
            LOG(debug, "Received new aggregation blob of %zd bytes", newAggrBlob.size());
            setupGrouping(newAggrBlob);
        }

    } else {
        LOG(warning, "No searchcluster specified");
    }

    if ( params.lookup("unique", valueRef) ) {
        LOG(spam, "Received unique specification of %zd bytes", valueRef.size());
    } else {
        LOG(debug, "No unique specification received");
    }
    VISITOR_TRACE(6, "Completed lazy VSM adapter initialization");
}

SearchVisitorFactory::SearchVisitorFactory(const config::ConfigUri & configUri)
    : VisitorFactory(),
      _configUri(configUri)
{}

VisitorEnvironment::UP
SearchVisitorFactory::makeVisitorEnvironment(StorageComponent&)
{
    return std::make_unique<SearchEnvironment>(_configUri);
}

storage::Visitor*
SearchVisitorFactory::makeVisitor(StorageComponent& component,
                                  storage::VisitorEnvironment& env,
                                  const vdslib::Parameters& params)
{
    return new SearchVisitor(component, env, params);
}

void
SearchVisitor::AttributeInserter::onPrimitive(uint32_t, const Content & c)
{
    const document::FieldValue & value = c.getValue();
    LOG(debug, "AttributeInserter: Adding value '%s'(%d) to attribute '%s' for docid '%d'",
        value.toString().c_str(), c.getWeight(), _attribute.getName().c_str(), _docId);
    search::IExtendAttribute & attr = *_attribute.getExtendInterface();
    const vespalib::Identifiable::RuntimeClass & aInfo = _attribute.getClass();
    if (aInfo.inherits(search::IntegerAttribute::classId)) {
        attr.add(value.getAsLong(), c.getWeight());
    } else if (aInfo.inherits(search::FloatingPointAttribute::classId)) {
        attr.add(value.getAsDouble(), c.getWeight());
    } else if (aInfo.inherits(search::StringAttribute::classId)) {
        attr.add(value.getAsString().c_str(), c.getWeight());
    } else {
        assert(false && "We got an attribute vector that is of an unknown type");
    }
}

SearchVisitor::AttributeInserter::AttributeInserter(AttributeVector & attribute, AttributeVector::DocId docId) :
    _attribute(attribute),
    _docId(docId)
{
}

SearchVisitor::PositionInserter::PositionInserter(AttributeVector & attribute, AttributeVector::DocId docId) :
    AttributeInserter(attribute, docId),
    _fieldX(PositionDataType::getInstance().getField(PositionDataType::FIELD_X)),
    _fieldY(PositionDataType::getInstance().getField(PositionDataType::FIELD_Y))
{
}

SearchVisitor::PositionInserter::~PositionInserter() = default;

void
SearchVisitor::PositionInserter::onPrimitive(uint32_t, const Content & c)
{
    (void) c;
}

void
SearchVisitor::PositionInserter::onStructStart(const Content & c)
{
    const auto & value = static_cast<const document::StructuredFieldValue &>(c.getValue());
    LOG(debug, "PositionInserter: Adding value '%s'(%d) to attribute '%s' for docid '%d'",
        value.toString().c_str(), c.getWeight(), _attribute.getName().c_str(), _docId);

    value.getValue(_fieldX, _valueX);
    value.getValue(_fieldY, _valueY);
    int64_t zcurve = vespalib::geo::ZCurve::encode(_valueX.getValue(), _valueY.getValue());
    LOG(debug, "X=%d, Y=%d, zcurve=%" PRId64, _valueX.getValue(), _valueY.getValue(), zcurve);
    search::IExtendAttribute & attr = *_attribute.getExtendInterface();
    attr.add(zcurve, c.getWeight());
}

void
SearchVisitor::RankController::processHintedAttributes(const IndexEnvironment & indexEnv, bool rank,
                                                       const search::IAttributeManager & attrMan,
                                                       std::vector<AttrInfo> & attributeFields)
{
    const std::set<vespalib::string> & attributes = (rank ? indexEnv.getHintedRankAttributes() : indexEnv.getHintedDumpAttributes());
    for (const vespalib::string & name : attributes) {
        LOG(debug, "Process attribute access hint (%s): '%s'", rank ? "rank" : "dump", name.c_str());
        const search::fef::FieldInfo * fieldInfo = indexEnv.getFieldByName(name);
        if (fieldInfo != nullptr) {
            bool found = false;
            uint32_t fid = fieldInfo->id();
            for (size_t j = 0; !found && (j < attributeFields.size()); ++j) {
                found = (attributeFields[j]._field == fid);
            }
            if (!found) {
                AttributeGuard::UP attr(attrMan.getAttribute(name));
                if (attr->valid()) {
                    LOG(debug, "Add attribute '%s' with field id '%u' to the list of needed attributes", name.c_str(), fid);
                    attributeFields.emplace_back(fid, std::move(attr));
                } else {
                    LOG(warning, "Cannot locate attribute '%s' in the attribute manager. "
                        "Ignore access hint about this attribute", name.c_str());
                }
            }
        } else {
            LOG(warning, "Cannot locate field '%s' in the index environment. Ignore access hint about this attribute",
                name.c_str());
        }
    }
}

SearchVisitor::RankController::RankController() :
    _rankProfile("default"),
    _rankManagerSnapshot(nullptr),
    _rankSetup(nullptr),
    _queryProperties(),
    _hasRanking(false),
    _rankProcessor(),
    _dumpFeatures(false),
    _dumpProcessor()
{
}

SearchVisitor::RankController::~RankController() = default;

void
SearchVisitor::RankController::setupRankProcessors(Query & query,
                                                   const vespalib::string & location,
                                                   size_t wantedHitCount,
                                                   const search::IAttributeManager & attrMan,
                                                   std::vector<AttrInfo> & attributeFields)
{
    _rankSetup = &_rankManagerSnapshot->getRankSetup(_rankProfile);

    // register attribute vectors needed for ranking
    const IndexEnvironment & indexEnv = _rankManagerSnapshot->getIndexEnvironment(_rankProfile);
    processHintedAttributes(indexEnv, true, attrMan, attributeFields);

    _rankProcessor = std::make_unique<RankProcessor>(_rankManagerSnapshot, _rankProfile, query, location, _queryProperties, &attrMan);
    LOG(debug, "Initialize rank processor");
    _rankProcessor->initForRanking(wantedHitCount);

    if (_dumpFeatures) {
        // register attribute vectors needed for dumping
        processHintedAttributes(indexEnv, false, attrMan, attributeFields);

        _dumpProcessor = std::make_unique<RankProcessor>(_rankManagerSnapshot, _rankProfile, query, location, _queryProperties, &attrMan);
        LOG(debug, "Initialize dump processor");
        _dumpProcessor->initForDumping(wantedHitCount);
    }

    _hasRanking = true;
}


void
SearchVisitor::RankController::onDocumentMatch(uint32_t docId)
{
    // unpacking into match data
    _rankProcessor->unpackMatchData(docId);
    if (_dumpFeatures) {
        _dumpProcessor->unpackMatchData(docId);
    }
}

void
SearchVisitor::RankController::rankMatchedDocument(uint32_t docId)
{
    _rankProcessor->runRankProgram(docId);
    LOG(debug, "Rank score for matched document %u: %f",
        docId,
        _rankProcessor->getRankScore());
    if (_dumpFeatures) {
        _dumpProcessor->runRankProgram(docId);
        // we must transfer the score to this match data to make sure that the same hits
        // are kept on the hit collector used in the dump processor as the one used in the rank processor
        _dumpProcessor->setRankScore(_rankProcessor->getRankScore());
    }
}

bool
SearchVisitor::RankController::keepMatchedDocument()
{
    // also make sure that NaN scores are added
    return (!(_rankProcessor->getRankScore() <= _rankSetup->getRankScoreDropLimit()));
}

bool
SearchVisitor::RankController::collectMatchedDocument(bool hasSorting,
                                                      SearchVisitor & visitor,
                                                      const std::vector<char> & tmpSortBuffer,
                                                      const StorageDocument * document)
{
    bool amongTheBest(false);
    uint32_t docId = _rankProcessor->getDocId();
    if (!hasSorting) {
        amongTheBest = _rankProcessor->getHitCollector().addHit(document, docId, _rankProcessor->getMatchData(),
                                                                _rankProcessor->getRankScore());
        if (amongTheBest && _dumpFeatures) {
            _dumpProcessor->getHitCollector().addHit(nullptr, docId, _dumpProcessor->getMatchData(), _dumpProcessor->getRankScore());
        }
    } else {
        size_t pos = visitor.fillSortBuffer();
        LOG(spam, "SortBlob is %ld bytes", pos);
        amongTheBest = _rankProcessor->getHitCollector().addHit(document, docId, _rankProcessor->getMatchData(),
                                                                _rankProcessor->getRankScore(), &tmpSortBuffer[0], pos);
        if (amongTheBest && _dumpFeatures) {
            _dumpProcessor->getHitCollector().addHit(nullptr, docId, _dumpProcessor->getMatchData(),
                                                     _dumpProcessor->getRankScore(), &tmpSortBuffer[0], pos);
        }
    }
    return amongTheBest;
}

void
SearchVisitor::RankController::onCompletedVisiting(vsm::GetDocsumsStateCallback & docsumsStateCallback, vdslib::SearchResult & searchResult)
{
    if (_hasRanking) {
        // fill the search result with the hits from the hit collector
        _rankProcessor->fillSearchResult(searchResult);

        // calculate summary features and set them on the callback object
        if (!_rankSetup->getSummaryFeatures().empty()) {
            LOG(debug, "Calculate summary features");
            search::FeatureSet::SP sf = _rankProcessor->calculateFeatureSet();
            docsumsStateCallback.setSummaryFeatures(sf);
        }

        // calculate rank features and set them on the callback object
        if (_dumpFeatures) {
            LOG(debug, "Calculate rank features");
            search::FeatureSet::SP rf = _dumpProcessor->calculateFeatureSet();
            docsumsStateCallback.setRankFeatures(rf);
        }
    }
}

SearchVisitor::SyntheticFieldsController::SyntheticFieldsController() :
    _documentIdFId(StringFieldIdTMap::npos)
{
}

void
SearchVisitor::SyntheticFieldsController::setup(const StringFieldIdTMap & fieldRegistry,
                                                const StringFieldIdTMap & /*fieldsInQuery*/)
{
    _documentIdFId = fieldRegistry.fieldNo("documentid");
    assert(_documentIdFId != StringFieldIdTMap::npos);
}

void
SearchVisitor::SyntheticFieldsController::onDocument(StorageDocument & document)
{
    (void) document;
}

void
SearchVisitor::SyntheticFieldsController::onDocumentMatch(StorageDocument & document,
                                                          const vespalib::string & documentId)
{
    document.setField(_documentIdFId, std::make_unique<document::StringFieldValue>(documentId));
}

void
SearchVisitor::registerAdditionalFields(const std::vector<vsm::DocsumTools::FieldSpec> & docsumSpec,
                                        std::vector<vespalib::string> & fieldList)
{
    for (const vsm::DocsumTools::FieldSpec & spec : docsumSpec) {
        fieldList.push_back(spec.getOutputName());
        const std::vector<vespalib::string> & inputNames = spec.getInputNames();
        for (size_t j = 0; j < inputNames.size(); ++j) {
            fieldList.push_back(inputNames[j]);
            if (PositionDataType::isZCurveFieldName(inputNames[j])) {
                fieldList.emplace_back(PositionDataType::cutZCurveFieldName(inputNames[j]));
            }
        }
    }
    // fields used during sorting
    fieldList.emplace_back("[docid]");
    fieldList.emplace_back("[rank]");
    fieldList.emplace_back("documentid");
}

void
SearchVisitor::setupFieldSearchers(const std::vector<vespalib::string> & additionalFields,
                                   StringFieldIdTMap & fieldsInQuery)
{
    // Create mapping from field name to field id, from field id to search spec,
    // and from index name to list of field ids
    _fieldSearchSpecMap.buildFromConfig(_vsmAdapter->getFieldsConfig());
    // Add extra elements to mapping from field name to field id
    _fieldSearchSpecMap.buildFromConfig(additionalFields);

    // Reconfig field searchers based on the query
    _fieldSearchSpecMap.reconfigFromQuery(_query);

    // Map field name to field id for all fields in the query
    _fieldSearchSpecMap.buildFieldsInQuery(_query, fieldsInQuery);
    // Connect field names in the query to field searchers
    _fieldSearchSpecMap.buildSearcherMap(fieldsInQuery.map(), _fieldSearcherMap);

    // prepare the field searchers
    _fieldSearcherMap.prepare(_fieldSearchSpecMap.documentTypeMap(), _searchBuffer, _query);
}

void
SearchVisitor::setupSnippetModifiers()
{
    QueryTermList qtl;
    _query.getLeafs(qtl);
    _snippetModifierManager.setup(qtl, _fieldSearchSpecMap.specMap(), _fieldSearchSpecMap.documentTypeMap().begin()->second);
}

void
SearchVisitor::setupScratchDocument(const StringFieldIdTMap & fieldsInQuery)
{
    if (_fieldSearchSpecMap.documentTypeMap().empty()) {
        throw vespalib::IllegalStateException("Illegal config: There must be at least 1 document type in the 'vsmfields' config");
    }
    // Setup document type mapping
    if (_fieldSearchSpecMap.documentTypeMap().size() != 1) {
        LOG(warning, "We have %zd document types in the vsmfields config when we expected 1. Using the first one",
            _fieldSearchSpecMap.documentTypeMap().size());
    }
    _fieldsUnion = fieldsInQuery.map();
    for(const auto & entry :_fieldSearchSpecMap.nameIdMap().map()) {
        if (_fieldsUnion.find(entry.first) == _fieldsUnion.end()) {
            LOG(debug, "Adding field '%s' from _fieldSearchSpecMap", entry.first.c_str());
            _fieldsUnion[entry.first] = entry.second;
        }
    }
    // Init based on default document type and mapping from field name to field id
    _docTypeMapping.init(_fieldSearchSpecMap.documentTypeMap().begin()->first,
                         _fieldsUnion, *_component.getTypeRepo()->documentTypeRepo);
    _docTypeMapping.prepareBaseDoc(_fieldPathMap);
}

void
SearchVisitor::setupDocsumObjects()
{
    auto docsumFilter = std::make_unique<DocsumFilter>(_vsmAdapter->getDocsumTools(),
                                                       _rankController.getRankProcessor()->getHitCollector());
    docsumFilter->init(_fieldSearchSpecMap.nameIdMap(), *_fieldPathMap);
    docsumFilter->setSnippetModifiers(_snippetModifierManager.getModifiers());
    _summaryGenerator.setFilter(std::move(docsumFilter));
    if (_vsmAdapter->getDocsumTools().get()) {
        GetDocsumsState * ds(&_summaryGenerator.getDocsumState());
        _vsmAdapter->getDocsumTools()->getDocsumWriter()->InitState(_attrMan, ds);
       _summaryGenerator.setDocsumWriter(*_vsmAdapter->getDocsumTools()->getDocsumWriter());
       for (const IAttributeVector * v : ds->_attributes) {
           if (v != nullptr) {
               vespalib::string name(v->getName());
               vsm::FieldIdT fid = _fieldSearchSpecMap.nameIdMap().fieldNo(name);
               if ( fid != StringFieldIdTMap::npos ) {
                   AttributeGuard::UP attr(_attrMan.getAttribute(name));
                   if (attr->valid()) {
                       size_t index(_attributeFields.size());
                       for (size_t j(0); j < index; j++) {
                           if (_attributeFields[j]._field == fid) {
                               index = j;
                           }
                        }
                        if (index == _attributeFields.size()) {
                            _attributeFields.emplace_back(fid, std::move(attr));
                        }
                   } else {
                       LOG(warning, "Attribute '%s' is not valid", name.c_str());
                   }
               } else {
                   LOG(warning, "No field with name '%s'. Odd ....", name.c_str());
               }
           }
       }
    } else {
        LOG(warning, "No docsum tools available");
    }
}

void
SearchVisitor::setupAttributeVectors()
{
    for (const FieldPath & fieldPath : *_fieldPathMap) {
        if ( ! fieldPath.empty() ) {
            setupAttributeVector(fieldPath);
        }
    }
}

void SearchVisitor::setupAttributeVector(const FieldPath &fieldPath) {
    vespalib::string attrName(fieldPath.front().getName());
    for (FieldPath::const_iterator ft(fieldPath.begin() + 1), fmt(fieldPath.end()); ft != fmt; ft++) {
        attrName.append(".");
        attrName.append((*ft)->getName());
    }

    enum FieldDataType { OTHER = 0, ARRAY, WSET };
    FieldDataType typeSeen = OTHER;
    for (const auto & entry : fieldPath) {
        int dataTypeId(entry->getDataType().getClass().id());
        if (dataTypeId == document::ArrayDataType::classId) {
            typeSeen = ARRAY;
        } else if (dataTypeId == document::MapDataType::classId) {
            typeSeen = ARRAY;
        } else if (dataTypeId == document::WeightedSetDataType::classId) {
            typeSeen = WSET;
        }
    }
    const document::FieldValue & fv = fieldPath.back().getFieldValueToSet();
    AttributeVector::SP attr;
    if (typeSeen == ARRAY) {
        attr = createMultiValueAttribute(attrName, fv, true);
    } else if (typeSeen == WSET) {
        attr = createMultiValueAttribute (attrName, fv, false);
    } else {
        attr = createAttribute(attrName, fv);
    }

    if (attr) {
        LOG(debug, "Adding attribute '%s' for field '%s' with data type '%s' (%s)",
            attr->getName().c_str(), attrName.c_str(), fv.getDataType()->getName().c_str(), fv.getClass().name());
        if ( ! _attrMan.add(attr) ) {
            LOG(warning, "Failed adding attribute '%s' for field '%s' with data type '%s' (%s)",
                attr->getName().c_str(), attrName.c_str(), fv.getDataType()->getName().c_str(), fv.getClass().name());
        }
    } else {
        LOG(debug, "Cannot setup attribute for field '%s' with data type '%s' (%s). Aggregation and sorting will not work for this field",
            attrName.c_str(), fv.getDataType()->getName().c_str(), fv.getClass().name());
    }
}

void
SearchVisitor::setupAttributeVectorsForSorting(const search::common::SortSpec & sortList)
{
    if ( ! sortList.empty() ) {
        for (const search::common::SortInfo & sInfo : sortList) {
            vsm::FieldIdT fid = _fieldSearchSpecMap.nameIdMap().fieldNo(sInfo._field);
            if ( fid != StringFieldIdTMap::npos ) {
                AttributeGuard::UP attr(_attrMan.getAttribute(sInfo._field));
                if (attr->valid()) {
                    if (!(*attr)->hasMultiValue()) {
                        size_t index(_attributeFields.size());
                        for(size_t j(0); j < index; j++) {
                            if (_attributeFields[j]._field == fid) {
                                index = j;
                                _attributeFields[index]._ascending = sInfo._ascending;
                                _attributeFields[index]._converter = sInfo._converter.get();
                            }
                        }
                        if (index == _attributeFields.size()) {
                            _attributeFields.emplace_back(fid, std::move(attr), sInfo._ascending, sInfo._converter.get());
                        }
                        _sortList.push_back(index);
                    } else {
                        LOG(warning, "Attribute '%s' is not sortable", sInfo._field.c_str());
                    }
                } else {
                    LOG(warning, "Attribute '%s' is not valid", sInfo._field.c_str());
                }
            } else {
                LOG(warning, "Cannot locate field '%s' in field name registry", sInfo._field.c_str());
            }
        }
    } else {
        LOG(debug, "No sort specification received");
    }
}

void
SearchVisitor::setupGrouping(const std::vector<char> & groupingBlob)
{
    vespalib::nbostream iss(&groupingBlob[0], groupingBlob.size());
    vespalib::NBOSerializer is(iss);
    uint32_t numGroupings(0);
    is >> numGroupings;
    for(size_t i(0); i < numGroupings; i++) {
        auto ag = std::make_unique<Grouping>();
        ag->deserialize(is);
        GroupingList::value_type groupingPtr(ag.release());
        Grouping & grouping = *groupingPtr;
        Attribute2DocumentAccessor attr2Doc;
        grouping.select(attr2Doc, attr2Doc);
        LOG(debug, "Grouping # %ld with id(%d)", i, grouping.getId());
        try {
            ConfigureStaticParams stuff(_attrCtx.get(), &_docTypeMapping.getCurrentDocumentType());
            grouping.configureStaticStuff(stuff);
            HitsResultPreparator preparator(_summaryGenerator);
            grouping.select(preparator, preparator);
            grouping.preAggregate(false);
            if (!grouping.getAll() || (preparator.getNumHitsAggregators() == 0)) {
                _groupingList.push_back(groupingPtr);
            } else {
                LOG(warning, "You can not collect hits with an all aggregator yet.");
            }
        } catch (const std::exception & e) {
            LOG(error, "Could not locate attribute for grouping number %ld : %s", i, e.what());
        }
    }
}

class SingleDocumentStore : public vsm::IDocSumCache
{
public:
    explicit SingleDocumentStore(const StorageDocument & doc) : _doc(doc) { }
    const vsm::Document & getDocSum(const search::DocumentIdT & docId) const override {
        (void) docId;
        return _doc;
    }
private:
    const StorageDocument & _doc;
};

bool
SearchVisitor::compatibleDocumentTypes(const document::DocumentType& typeA,
                                       const document::DocumentType& typeB) const
{
    if (&typeA == &typeB) {
        return true;
    } else {
        return (typeA.getName() == typeB.getName());
    }
}

void
SearchVisitor::handleDocuments(const document::BucketId&,
                               DocEntryList & entries,
                               HitCounter& hitCounter)
{
    (void) hitCounter;
    if (_vsmAdapter == nullptr) {
        init(_params);
    }
    if ( ! _rankController.valid() ) {
        //Prevent continuing with bad config.
        return;
    }
    document::DocumentId emptyId;
    LOG(debug, "SearchVisitor '%s' handling block of %zu documents", _id.c_str(), entries.size());
    size_t highestFieldNo(_fieldSearchSpecMap.nameIdMap().highestFieldNo());

    const document::DocumentType* defaultDocType = _docTypeMapping.getDefaultDocumentType();
    assert(defaultDocType);
    for (const auto & entry : entries) {
        auto document = std::make_unique<StorageDocument>(entry->releaseDocument(), _fieldPathMap, highestFieldNo);

        try {
            if (defaultDocType != nullptr
                && !compatibleDocumentTypes(*defaultDocType, document->docDoc().getType()))
            {
                LOG(debug, "Skipping document of type '%s' when handling only documents of type '%s'",
                    document->docDoc().getType().getName().c_str(), defaultDocType->getName().c_str());
            } else {
                if (handleDocument(*document)) {
                    _backingDocuments.push_back(std::move(document));
                }
            }
        } catch (const std::exception & e) {
            LOG(warning, "Caught exception handling document '%s'. Exception='%s'",
                document->docDoc().getId().getScheme().toString().c_str(), e.what());
        }
    }
}

bool
SearchVisitor::handleDocument(StorageDocument & document)
{
    bool needToKeepDocument(false);
    _syntheticFieldsController.onDocument(document);
    group(document.docDoc(), 0, true);
    if (match(document)) {
        RankProcessor & rp = *_rankController.getRankProcessor();
        vespalib::string documentId(document.docDoc().getId().getScheme().toString());
        LOG(debug, "Matched document with id '%s'", documentId.c_str());

        document.setDocId(rp.getDocId());

        fillAttributeVectors(documentId, document);

        _rankController.rankMatchedDocument(rp.getDocId());

        if (_shouldFillRankAttribute) {
            _rankAttribute.add(rp.getRankScore());
        }

        if (_rankController.keepMatchedDocument()) {

            bool amongTheBest = _rankController.collectMatchedDocument(!_sortList.empty(), *this, _tmpSortBuffer, &document);

            _syntheticFieldsController.onDocumentMatch(document, documentId);

            SingleDocumentStore single(document);
            _summaryGenerator.setDocsumCache(single);
            group(document.docDoc(), rp.getRankScore(), false);

            if (amongTheBest) {
                document.saveCachedFields();
                needToKeepDocument = true;
            }

        } else {
            _hitsRejectedCount++;
            LOG(debug, "Do not keep document with id '%s' because rank score (%f) <= rank score drop limit (%f)",
                documentId.c_str(),
                rp.getRankScore(),
                _rankController.getRankSetup()->getRankScoreDropLimit());
        }
    } else {
        LOG(debug, "Did not match document with id '%s'", document.docDoc().getId().getScheme().toString().c_str());
    }
    return needToKeepDocument;
}

void
SearchVisitor::group(const document::Document & doc, search::HitRank rank, bool all)
{
    LOG(spam, "Group all: %s",  all ? "true" : "false");
    for(GroupingEntry & grouping : _groupingList) {
        if (all == grouping->getAll()) {
            grouping.aggregate(doc, rank);
            LOG(spam, "Actually group document with id '%s'", doc.getId().getScheme().toString().c_str());
        }
    }
}

bool
SearchVisitor::match(const StorageDocument & doc)
{
    for (vsm::FieldSearcherContainer & fSearch : _fieldSearcherMap) {
        fSearch->search(doc);
    }
    bool hit(_query.evaluate());
    if (hit) {
        _hitCount++;
        LOG(spam, "Match in doc %d", doc.getDocId());

        _rankController.onDocumentMatch(_hitCount - 1); // send in the local docId to use for this hit
    }
    _docSearchedCount++;
    _query.reset();
    return hit;
}

void
SearchVisitor::fillAttributeVectors(const vespalib::string & documentId, const StorageDocument & document)
{
    for (const AttrInfo & finfo : _attributeFields) {
        const AttributeGuard &finfoGuard(*finfo._attr);
        bool isPosition = finfoGuard->getClass().inherits(search::IntegerAttribute::classId) && PositionDataType::isZCurveFieldName(finfoGuard->getName());
        LOG(debug, "Filling attribute '%s',  isPosition='%s'", finfoGuard->getName().c_str(), isPosition ? "true" : "false");
        uint32_t fieldId = finfo._field;
        if (isPosition) {
            vespalib::stringref org = PositionDataType::cutZCurveFieldName(finfoGuard->getName());
            fieldId = _fieldsUnion.find(org)->second;
        }
        const StorageDocument::SubDocument & subDoc = document.getComplexField(fieldId);
        auto & attrV = const_cast<AttributeVector & >(*finfoGuard);
        AttributeVector::DocId docId(0);
        attrV.addDoc(docId);
        if (subDoc.getFieldValue() != nullptr) {
            LOG(debug, "value = '%s'", subDoc.getFieldValue()->toString().c_str());
            if (isPosition) {
                LOG(spam, "Position");
                PositionInserter pi(attrV, docId);
                subDoc.getFieldValue()->iterateNested(subDoc.getRange(), pi);
            } else {
                AttributeInserter ai(attrV, docId);
                subDoc.getFieldValue()->iterateNested(subDoc.getRange(), ai);
            }
        } else if (finfoGuard->getName() == "[docid]") {
            _documentIdAttribute.add(documentId.c_str());
            // assert((_docsumCache.cache().size() + 1) == _documentIdAttribute.getNumDocs());
        } else if (finfoGuard->getName() == "[rank]") {
            _shouldFillRankAttribute = true;
        }
    }
}

size_t
SearchVisitor::fillSortBuffer()
{
    size_t pos(0);
    for (size_t index : _sortList) {
        const AttrInfo & finfo = _attributeFields[index];
        int written(0);
        const AttributeGuard &finfoGuard(*finfo._attr);
        LOG(debug, "Adding sortdata for document %d for attribute '%s'",
            finfoGuard->getNumDocs() - 1, finfoGuard->getName().c_str());
//        assert((_docsumCache.cache().size() + 1) == finfo._attr->getNumDocs());
        do {
            if (finfo._ascending) {
                written = finfoGuard->serializeForAscendingSort(finfoGuard->getNumDocs()-1, &_tmpSortBuffer[0]+pos, _tmpSortBuffer.size() - pos, finfo._converter);
            } else {
                written = finfoGuard->serializeForDescendingSort(finfoGuard->getNumDocs()-1, &_tmpSortBuffer[0]+pos, _tmpSortBuffer.size() - pos, finfo._converter);
            }
            if (written == -1) {
                _tmpSortBuffer.resize(_tmpSortBuffer.size()*2);
            }
        } while (written == -1);
        pos += written;
    }
    return pos;
}

void SearchVisitor::completedBucket(const document::BucketId&, HitCounter&)
{
    LOG(debug, "Completed bucket");
}

void SearchVisitor::completedVisitingInternal(HitCounter& hitCounter)
{
    if (_vsmAdapter == nullptr) {
        init(_params);
    }
    LOG(debug, "Completed visiting");
    vdslib::SearchResult & searchResult(_queryResult->getSearchResult());
    vdslib::DocumentSummary & documentSummary(_queryResult->getDocumentSummary());
    LOG(debug, "Hit count: %lu", searchResult.getHitCount());

    _rankController.onCompletedVisiting(_summaryGenerator.getDocsumCallback(), searchResult);
    LOG(debug, "Hit count: %lu", searchResult.getHitCount());

    /// Now I can sort. No more documentid access order.
    searchResult.sort();
    searchResult.setTotalHitCount(_hitCount - _hitsRejectedCount);

    const char* docId;
    vdslib::SearchResult::RankType rank;
    for (uint32_t i = 0; i < searchResult.getHitCount(); i++) {
        searchResult.getHit(i, docId, rank);
        hitCounter.addHit(document::DocumentId(docId), 0);
    }

    generateGroupingResults();

    generateDocumentSummaries();
    _backingDocuments.clear();

    documentSummary.sort();
    LOG(debug, "Docsum count: %lu", documentSummary.getSummaryCount());
}

void SearchVisitor::completedVisiting(HitCounter& hitCounter)
{
    completedVisitingInternal(hitCounter);
    sendMessage(documentapi::DocumentMessage::UP(_queryResult.release()));
}

void
SearchVisitor::generateGroupingResults()
{
    vdslib::SearchResult & searchResult(_queryResult->getSearchResult());
    for (auto & groupingPtr : _groupingList) {
        Grouping & grouping(*groupingPtr);
        LOG(debug, "grouping before postAggregate: %s", grouping.asString().c_str());
        grouping.postAggregate();
        grouping.postMerge();
        grouping.sortById();
        LOG(debug, "grouping after postAggregate: %s", grouping.asString().c_str());
        vespalib::nbostream os;
        vespalib::NBOSerializer nos(os);
        grouping.serialize(nos);
        vespalib::MallocPtr blob(os.size());
        memcpy(blob, os.data(), os.size());
        searchResult.getGroupingList().add(grouping.getId(), blob);
    }
}

void
SearchVisitor::generateDocumentSummaries()
{
    if ( ! _rankController.valid()) {
        return;
    }
    auto& hit_collector = _rankController.getRankProcessor()->getHitCollector();
    _summaryGenerator.setDocsumCache(hit_collector);
    vdslib::SearchResult & searchResult(_queryResult->getSearchResult());
    _summaryGenerator.getDocsumCallback().set_matching_elements_filler(std::make_unique<MatchingElementsFiller>(_fieldSearcherMap, _query, hit_collector, searchResult));
    vdslib::DocumentSummary & documentSummary(_queryResult->getDocumentSummary());
    for (size_t i(0), m(searchResult.getHitCount()); (i < m) && (i < searchResult.getWantedHitCount()); i++ ) {
        const char * docId(nullptr);
        vdslib::SearchResult::RankType rank(0);
        uint32_t lid = searchResult.getHit(i, docId, rank);
        vespalib::ConstBufferRef docsum = _summaryGenerator.fillSummary(lid, _summaryClass);
        documentSummary.addSummary(docId, docsum.c_str(), docsum.size());
        LOG(debug, "Adding summary %ld: globalDocId(%s), localDocId(%u), rank(%f), bytes(%lu)",
            i, docId, lid, rank, docsum.size());
    }
}


}
