// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/document/base/testdocman.h>
#include <vespa/persistence/conformancetest/conformancetest.h>
#include <vespa/persistence/spi/test.h>
#include <vespa/persistence/spi/catchresult.h>
#include <vespa/persistence/spi/resource_usage_listener.h>
#include <vespa/persistence/spi/docentry.h>
#include <vespa/document/fieldset/fieldsets.h>
#include <vespa/document/update/documentupdate.h>
#include <vespa/document/update/assignvalueupdate.h>
#include <vespa/document/repo/documenttyperepo.h>
#include <vespa/document/test/make_bucket_space.h>
#include <vespa/vdslib/state/state.h>
#include <vespa/vdslib/state/node.h>
#include <vespa/vdslib/state/nodestate.h>
#include <vespa/vdslib/state/clusterstate.h>
#include <vespa/vdslib/distribution/distribution.h>
#include <vespa/vespalib/util/idestructorcallback.h>
#include <vespa/vespalib/util/size_literals.h>
#include <vespa/config-stor-distribution.h>
#include <limits>
#include <gtest/gtest.h>

using document::BucketId;
using document::BucketSpace;
using document::test::makeBucketSpace;
using storage::spi::test::makeSpiBucket;
using storage::spi::test::cloneDocEntry;

namespace storage::spi {

using PersistenceProviderUP = std::unique_ptr<PersistenceProvider>;
using DocEntryList = std::vector<DocEntry::UP>;

namespace {

std::unique_ptr<PersistenceProvider>
getSpi(ConformanceTest::PersistenceFactory &factory, const document::TestDocMan &testDocMan) {
    PersistenceProviderUP result(factory.getPersistenceImplementation(
                testDocMan.getTypeRepoSP(), *testDocMan.getTypeConfig()));
    EXPECT_TRUE(!result->initialize().hasError());
    return result;
}

enum SELECTION_FIELDS
{
    METADATA_ONLY = 0,
    ALL_FIELDS = 1
};

CreateIteratorResult
createIterator(PersistenceProvider& spi,
               const Bucket& b,
               const Selection& sel,
               IncludedVersions versions = NEWEST_DOCUMENT_ONLY,
               int fields = ALL_FIELDS)
{
    document::FieldSet::SP fieldSet;
    if (fields & ALL_FIELDS) {
        fieldSet = std::make_shared<document::AllFields>();
    } else {
        fieldSet = std::make_shared<document::DocIdOnly>();
    }

    Context context(Priority(0), Trace::TraceLevel(0));
    return spi.createIterator(b, std::move(fieldSet), sel, versions, context);
}

Selection
createSelection(const string& docSel)
{
    return Selection(DocumentSelection(docSel));
}


ClusterState
createClusterState(const lib::State& nodeState = lib::State::UP)
{
    using storage::lib::Distribution;
    using storage::lib::Node;
    using storage::lib::NodeState;
    using storage::lib::NodeType;
    using storage::lib::State;
    using vespa::config::content::StorDistributionConfigBuilder;
    typedef StorDistributionConfigBuilder::Group Group;
    typedef Group::Nodes Nodes;
    storage::lib::ClusterState cstate;
    StorDistributionConfigBuilder dc;

    cstate.setNodeState(Node(NodeType::STORAGE, 0),
                        NodeState(NodeType::STORAGE,
                                  nodeState,
                                  "dummy desc",
                                  1.0));
    cstate.setClusterState(State::UP);
    dc.redundancy = 1;
    dc.readyCopies = 1;
    dc.group.push_back(Group());
    Group &g(dc.group[0]);
    g.index = "invalid";
    g.name = "invalid";
    g.capacity = 1.0;
    g.partitions = "";
    g.nodes.push_back(Nodes());
    Nodes &n(g.nodes[0]);
    n.index = 0;
    Distribution dist(dc);
    return ClusterState(cstate, 0, dist);
}

struct DocAndTimestamp
{
    Document::SP doc;
    spi::Timestamp timestamp;

    DocAndTimestamp(const Document::SP& docptr, spi::Timestamp ts)
        : doc(docptr), timestamp(ts)
    {
    }
};

/**
 * A chunk represents the set of data received by the caller for any
 * single invocation of iterate().
 */
struct Chunk
{
    DocEntryList _entries;
};

struct DocEntryIndirectTimestampComparator
{
    bool operator()(const DocEntry::UP& e1,
                    const DocEntry::UP& e2) const
    {
        return e1->getTimestamp() < e2->getTimestamp();
    }
};

/**
 * Do a full bucket iteration, returning a vector of DocEntry chunks.
 */
std::vector<Chunk>
doIterate(PersistenceProvider& spi,
          IteratorId id,
          uint64_t maxByteSize,
          size_t maxChunks = 0,
          bool allowEmptyResult = false)
{
    (void)allowEmptyResult;

    std::vector<Chunk> chunks;

    while (true) {
        Context context(Priority(0), Trace::TraceLevel(0));
        IterateResult result(spi.iterate(id, maxByteSize, context));

        EXPECT_EQ(Result::ErrorType::NONE, result.getErrorCode());

        chunks.push_back(Chunk{result.steal_entries()});
        if (result.isCompleted()
            || (maxChunks != 0 && chunks.size() >= maxChunks))
        {
            break;
        }
    }
    return chunks;
}

size_t
getRemoveEntryCount(const DocEntryList& entries)
{
    size_t ret = 0;
    for (size_t i = 0; i < entries.size(); ++i) {
        if (entries[i]->isRemove()) {
            ++ret;
        }
    }
    return ret;
}

DocEntryList
getEntriesFromChunks(const std::vector<Chunk>& chunks)
{
    DocEntryList ret;
    for (size_t chunk = 0; chunk < chunks.size(); ++chunk) {
        for (size_t i = 0; i < chunks[chunk]._entries.size(); ++i) {
            ret.push_back(cloneDocEntry(*chunks[chunk]._entries[i]));
        }
    }
    std::sort(ret.begin(),
              ret.end(),
              DocEntryIndirectTimestampComparator());
    return ret;
}


DocEntryList
iterateBucket(PersistenceProvider& spi,
              const Bucket& bucket,
              IncludedVersions versions)
{
    DocEntryList ret;
    DocumentSelection docSel("");
    Selection sel(docSel);

    Context context(Priority(0), Trace::TraceLevel(0));
    CreateIteratorResult iter = spi.createIterator(
            bucket,
            std::make_shared<document::AllFields>(),
            sel,
            versions,
            context);

    EXPECT_EQ(Result::ErrorType::NONE, iter.getErrorCode());

    while (true) {
        IterateResult result =
            spi.iterate(iter.getIteratorId(),
                         std::numeric_limits<int64_t>().max(), context);
        if (result.getErrorCode() != Result::ErrorType::NONE) {
            return DocEntryList();
        }
        auto list = result.steal_entries();
        std::move(list.begin(), list.end(), std::back_inserter(ret));
        if (result.isCompleted()) {
            break;
        }
    }

    spi.destroyIterator(iter.getIteratorId(), context);
    std::sort(ret.begin(),
              ret.end(),
              DocEntryIndirectTimestampComparator());
    return ret;
}

void
verifyDocs(const std::vector<DocAndTimestamp>& wanted,
           const std::vector<Chunk>& chunks,
           const std::set<string>& removes = std::set<string>())
{
    DocEntryList retrieved = getEntriesFromChunks(chunks);
    size_t removeCount = getRemoveEntryCount(retrieved);
    // Ensure that we've got the correct number of puts and removes
    EXPECT_EQ(removes.size(), removeCount);
    EXPECT_EQ(wanted.size(), retrieved.size() - removeCount);

    size_t wantedIdx = 0;
    for (size_t i = 0; i < retrieved.size(); ++i) {
        DocEntry& entry(*retrieved[i]);
        if (entry.getDocument() != 0) {
            if (!(*wanted[wantedIdx].doc == *entry.getDocument())) {
                FAIL() << "Documents differ! Wanted:\n"
                       << wanted[wantedIdx].doc->toString(true)
                       << "\n\nGot:\n"
                       << entry.getDocument()->toString(true);
            }
            EXPECT_EQ(wanted[wantedIdx].timestamp, entry.getTimestamp());
            size_t serSize = wanted[wantedIdx].doc->serialize().size();
            EXPECT_EQ(serSize, size_t(entry.getSize()));
            ++wantedIdx;
        } else {
            // Remove-entry
            EXPECT_TRUE(entry.getDocumentId() != 0);
            size_t serSize = entry.getDocumentId()->getSerializedSize();
            EXPECT_EQ(serSize, size_t(entry.getSize()));
            if (removes.find(entry.getDocumentId()->toString()) == removes.end()) {
                FAIL() << "Got unexpected remove entry for document id "
                       << *entry.getDocumentId();
            }
        }
    }
}

// Feed numDocs documents, starting from timestamp 1000
std::vector<DocAndTimestamp>
feedDocs(PersistenceProvider& spi,
         document::TestDocMan& testDocMan,
         Bucket& bucket,
         size_t numDocs,
         uint32_t minSize = 110,
         uint32_t maxSize = 110)
{
    std::vector<DocAndTimestamp> docs;
    Context context(Priority(0), Trace::TraceLevel(0));
    for (uint32_t i = 0; i < numDocs; ++i) {
        Document::SP doc(
                testDocMan.createRandomDocumentAtLocation(
                        bucket.getBucketId().getId() & 0xffffffff,
                        i,
                        minSize,
                        maxSize));
        Result result = spi.put(bucket, Timestamp(1000 + i), doc, context);
        EXPECT_TRUE(!result.hasError());
        docs.push_back(DocAndTimestamp(doc, Timestamp(1000 + i)));
    }
    return docs;
}

}  // namespace

// Set by test runner.
std::unique_ptr<ConformanceTest::PersistenceFactory>(*ConformanceTest::_factoryFactory)(const std::string &docType) = nullptr;

ConformanceTest::ConformanceTest()
    : ConformanceTest("")
{
}

ConformanceTest::ConformanceTest(const std::string &docType)
    : _factory(_factoryFactory(docType))
{
}

SingleDocTypeConformanceTest::SingleDocTypeConformanceTest()
    : ConformanceTest("testdoctype1")
{
}

/**
 * Tests that one can put and remove entries to the persistence
 * implementation, and iterate over the content. This functionality is
 * needed by most other tests in order to verify correct behavior, so
 * this needs to work for other tests to work.
 */
TEST_F(ConformanceTest, testBasics)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));

    Context context(Priority(0), Trace::TraceLevel(0));
    Bucket bucket(makeSpiBucket(BucketId(8, 0x01)));
    Document::SP doc1 = testDocMan.createRandomDocumentAtLocation(0x01, 1);
    Document::SP doc2 = testDocMan.createRandomDocumentAtLocation(0x01, 2);
    spi->createBucket(bucket, context);
    EXPECT_EQ(
            Result(),
            Result(spi->put(bucket, Timestamp(1), doc1, context)));

    EXPECT_EQ(
            Result(),
            Result(spi->put(bucket, Timestamp(2), doc2, context)));

    EXPECT_EQ(
            Result(),
            Result(spi->remove(bucket, Timestamp(3), doc1->getId(), context)));

    // Iterate first without removes, then with.
    for (int iterPass = 0; iterPass < 2; ++iterPass) {
        bool includeRemoves = (iterPass == 1);

        DocumentSelection docSel("true");
        Selection sel(docSel);

        CreateIteratorResult iter = spi->createIterator(
                bucket,
                std::make_shared<document::AllFields>(),
                sel,
                includeRemoves
                    ?  NEWEST_DOCUMENT_OR_REMOVE : NEWEST_DOCUMENT_ONLY,
                context);

        EXPECT_EQ(Result(), Result(iter));

        IterateResult result =
            spi->iterate(iter.getIteratorId(),
                         std::numeric_limits<int64_t>().max(), context);

        EXPECT_EQ(Result(), Result(result));
        EXPECT_TRUE(result.isCompleted());
        spi->destroyIterator(iter.getIteratorId(), context);

        Timestamp timeDoc1(0);
        Timestamp timeDoc2(0);
        Timestamp timeRemoveDoc1(0);

        for (uint32_t i=0; i<result.getEntries().size(); ++i) {
            const DocumentId* did = result.getEntries()[i]->getDocumentId();
            ASSERT_TRUE(did != nullptr) << "Supplied FieldSet requires id";

            if (*did == doc1->getId()) {
                if (!includeRemoves) {
                    FAIL() << "Got removed document 1 when iterating without removes";
                }
                if (result.getEntries()[i]->isRemove()) {
                    timeRemoveDoc1 = result.getEntries()[i]->getTimestamp();
                } else {
                    timeDoc1 = result.getEntries()[i]->getTimestamp();
                }
            } else if (*did == doc2->getId()) {
                if (result.getEntries()[i]->isRemove()) {
                    FAIL() << "Document 2 should not be removed";
                } else {
                    timeDoc2 = result.getEntries()[i]->getTimestamp();
                }
            } else {
                FAIL() << "Unknown document " << *did;
            }
        }

        EXPECT_EQ(Timestamp(2), timeDoc2);
        EXPECT_TRUE(timeDoc1 == Timestamp(0) || timeRemoveDoc1 != Timestamp(0));
    }
}

/**
 * Test that listing of buckets works as intended.
 */
TEST_F(ConformanceTest, testListBuckets)
{
    //TODO: enable when supported by provider in storage
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));

    BucketId bucketId1(8, 0x01);
    BucketId bucketId2(8, 0x02);
    BucketId bucketId3(8, 0x03);
    Bucket bucket1(makeSpiBucket(bucketId1));
    Bucket bucket2(makeSpiBucket(bucketId2));
    Bucket bucket3(makeSpiBucket(bucketId3));

    Document::SP doc1 = testDocMan.createRandomDocumentAtLocation(0x01, 1);
    Document::SP doc2 = testDocMan.createRandomDocumentAtLocation(0x02, 2);
    Document::SP doc3 = testDocMan.createRandomDocumentAtLocation(0x03, 3);
    Context context(Priority(0), Trace::TraceLevel(0));
    spi->createBucket(bucket1, context);
    spi->createBucket(bucket2, context);
    spi->createBucket(bucket3, context);

    spi->put(bucket1, Timestamp(1), doc1, context);
    spi->put(bucket2, Timestamp(2), doc2, context);
    spi->put(bucket3, Timestamp(3), doc3, context);

    {
        BucketIdListResult result = spi->listBuckets(makeBucketSpace());
        const BucketIdListResult::List &bucketList = result.getList();
        EXPECT_EQ(3u, (uint32_t)bucketList.size());
        EXPECT_TRUE(std::find(bucketList.begin(), bucketList.end(), bucketId1) != bucketList.end());
        EXPECT_TRUE(std::find(bucketList.begin(), bucketList.end(), bucketId2) != bucketList.end());
        EXPECT_TRUE(std::find(bucketList.begin(), bucketList.end(), bucketId3) != bucketList.end());
    }
}

/**
 * Test that bucket info is generated in a legal fashion. (Such that
 * split/join/merge can work as intended)
 */
TEST_F(ConformanceTest, testBucketInfo)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));

    Bucket bucket(makeSpiBucket(BucketId(8, 0x01)));

    Document::SP doc1 = testDocMan.createRandomDocumentAtLocation(0x01, 1);
    Document::SP doc2 = testDocMan.createRandomDocumentAtLocation(0x01, 2);
    Context context(Priority(0), Trace::TraceLevel(0));
    spi->createBucket(bucket, context);

    spi->put(bucket, Timestamp(2), doc2, context);

    const BucketInfo info1 = spi->getBucketInfo(bucket).getBucketInfo();

    {
        EXPECT_EQ(1, (int)info1.getDocumentCount());
        EXPECT_TRUE(info1.getChecksum() != 0);
    }

    spi->put(bucket, Timestamp(3), doc1, context);

    const BucketInfo info2 = spi->getBucketInfo(bucket).getBucketInfo();

    {
        EXPECT_EQ(2, (int)info2.getDocumentCount());
        EXPECT_TRUE(info2.getChecksum() != 0);
        EXPECT_TRUE(info2.getChecksum() != info1.getChecksum());
    }

    spi->put(bucket, Timestamp(4), doc1, context);

    const BucketInfo info3 = spi->getBucketInfo(bucket).getBucketInfo();

    {
        EXPECT_EQ(2, (int)info3.getDocumentCount());
        EXPECT_TRUE(info3.getChecksum() != 0);
        EXPECT_TRUE(info3.getChecksum() != info2.getChecksum());
    }

    spi->remove(bucket, Timestamp(5), doc1->getId(), context);

    const BucketInfo info4 = spi->getBucketInfo(bucket).getBucketInfo();

    {
        EXPECT_EQ(1, (int)info4.getDocumentCount());
        EXPECT_TRUE(info4.getChecksum() != 0);
        EXPECT_EQ(info4.getChecksum(), info4.getChecksum());
    }
}

/**
 * Test that given a set of operations with certain timestamps, the bucket
 * info is the same no matter what order we feed these in.
 */
TEST_F(ConformanceTest, testOrderIndependentBucketInfo)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));

    Bucket bucket(makeSpiBucket(BucketId(8, 0x01)));

    Document::SP doc1 = testDocMan.createRandomDocumentAtLocation(0x01, 1);
    Document::SP doc2 = testDocMan.createRandomDocumentAtLocation(0x01, 2);
    Context context(Priority(0), Trace::TraceLevel(0));
    spi->createBucket(bucket, context);

    BucketChecksum checksumOrdered(0);
    {
        spi->put(bucket, Timestamp(2), doc1, context);
        spi->put(bucket, Timestamp(3), doc2, context);
        const BucketInfo info(spi->getBucketInfo(bucket).getBucketInfo());

        checksumOrdered = info.getChecksum();
        EXPECT_TRUE(checksumOrdered != 0);
    }

    spi->deleteBucket(bucket, context);
    spi->createBucket(bucket, context);
    {
        const BucketInfo info(spi->getBucketInfo(bucket).getBucketInfo());
        EXPECT_EQ(BucketChecksum(0), info.getChecksum());
    }

    BucketChecksum checksumUnordered(0);
    {
        // Swap order of puts
        spi->put(bucket, Timestamp(3), doc2, context);
        spi->put(bucket, Timestamp(2), doc1, context);
        const BucketInfo info(spi->getBucketInfo(bucket).getBucketInfo());

        checksumUnordered = info.getChecksum();
        EXPECT_TRUE(checksumUnordered != 0);
    }
    EXPECT_EQ(checksumOrdered, checksumUnordered);
}

/** Test that the various document operations work as intended. */
TEST_F(ConformanceTest, testPut)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));

    Bucket bucket(makeSpiBucket(BucketId(8, 0x01)));
    Document::SP doc1 = testDocMan.createRandomDocumentAtLocation(0x01, 1);
    Document::SP doc2 = testDocMan.createRandomDocumentAtLocation(0x01, 2);
    spi->createBucket(bucket, context);

    Result result = spi->put(bucket, Timestamp(3), doc1, context);

    {
        const BucketInfo info = spi->getBucketInfo(bucket).getBucketInfo();

        EXPECT_EQ(1, (int)info.getDocumentCount());
        EXPECT_TRUE(info.getEntryCount() >= info.getDocumentCount());
        EXPECT_TRUE(info.getChecksum() != 0);
        EXPECT_TRUE(info.getDocumentSize() > 0);
        EXPECT_TRUE(info.getUsedSize() >= info.getDocumentSize());
    }
}

TEST_F(ConformanceTest, testPutNewDocumentVersion)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));

    Bucket bucket(makeSpiBucket(BucketId(8, 0x01)));
    Document::SP doc1 = testDocMan.createRandomDocumentAtLocation(0x01, 1);
    Document::SP doc2(doc1->clone());
    doc2->setValue("content", document::StringFieldValue("hiho silver"));
    spi->createBucket(bucket, context);

    Result result = spi->put(bucket, Timestamp(3), doc1, context);
    {
        const BucketInfo info = spi->getBucketInfo(bucket).getBucketInfo();

        EXPECT_EQ(1, (int)info.getDocumentCount());
        EXPECT_TRUE(info.getEntryCount() >= info.getDocumentCount());
        EXPECT_TRUE(info.getChecksum() != 0);
        EXPECT_TRUE(info.getDocumentSize() > 0);
        EXPECT_TRUE(info.getUsedSize() >= info.getDocumentSize());
    }

    result = spi->put(bucket, Timestamp(4), doc2, context);
    {
        const BucketInfo info = spi->getBucketInfo(bucket).getBucketInfo();

        EXPECT_EQ(1, (int)info.getDocumentCount());
        EXPECT_TRUE(info.getEntryCount() >= info.getDocumentCount());
        EXPECT_TRUE(info.getChecksum() != 0);
        EXPECT_TRUE(info.getDocumentSize() > 0);
        EXPECT_TRUE(info.getUsedSize() >= info.getDocumentSize());
    }

    GetResult gr = spi->get(bucket, document::AllFields(), doc1->getId(),
                            context);

    EXPECT_EQ(Result::ErrorType::NONE, gr.getErrorCode());
    EXPECT_EQ(Timestamp(4), gr.getTimestamp());
    EXPECT_FALSE(gr.is_tombstone());

    if (!((*doc2)==gr.getDocument())) {
        std::cerr << "Document returned is not the expected one: \n"
                  << "Expected: " << doc2->toString(true) << "\n"
                  << "Got: " << gr.getDocument().toString(true) << "\n";

        EXPECT_TRUE(false);
    }
}

TEST_F(ConformanceTest, testPutOlderDocumentVersion)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));

    Bucket bucket(makeSpiBucket(BucketId(8, 0x01)));
    Document::SP doc1 = testDocMan.createRandomDocumentAtLocation(0x01, 1);
    Document::SP doc2(doc1->clone());
    doc2->setValue("content", document::StringFieldValue("hiho silver"));
    spi->createBucket(bucket, context);

    Result result = spi->put(bucket, Timestamp(5), doc1, context);
    const BucketInfo info1 = spi->getBucketInfo(bucket).getBucketInfo();
    {
        EXPECT_EQ(1, (int)info1.getDocumentCount());
        EXPECT_TRUE(info1.getEntryCount() >= info1.getDocumentCount());
        EXPECT_TRUE(info1.getChecksum() != 0);
        EXPECT_TRUE(info1.getDocumentSize() > 0);
        EXPECT_TRUE(info1.getUsedSize() >= info1.getDocumentSize());
    }

    result = spi->put(bucket, Timestamp(4), doc2, context);
    {
        const BucketInfo info2 = spi->getBucketInfo(bucket).getBucketInfo();

        EXPECT_EQ(1, (int)info2.getDocumentCount());
        EXPECT_TRUE(info2.getEntryCount() >= info1.getDocumentCount());
        EXPECT_EQ(info1.getChecksum(), info2.getChecksum());
        EXPECT_EQ(info1.getDocumentSize(),
                             info2.getDocumentSize());
        EXPECT_TRUE(info2.getUsedSize() >= info1.getDocumentSize());
    }

    GetResult gr = spi->get(bucket, document::AllFields(), doc1->getId(),
                            context);

    EXPECT_EQ(Result::ErrorType::NONE, gr.getErrorCode());
    EXPECT_EQ(Timestamp(5), gr.getTimestamp());
    EXPECT_EQ(*doc1, gr.getDocument());
    EXPECT_FALSE(gr.is_tombstone());
}

TEST_F(ConformanceTest, testPutDuplicate)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));

    Bucket bucket(makeSpiBucket(BucketId(8, 0x01)));
    Document::SP doc1 = testDocMan.createRandomDocumentAtLocation(0x01, 1);
    spi->createBucket(bucket, context);
    EXPECT_EQ(Result(),
                         spi->put(bucket, Timestamp(3), doc1, context));

    BucketChecksum checksum;
    {
        const BucketInfo info = spi->getBucketInfo(bucket).getBucketInfo();
        EXPECT_EQ(1, (int)info.getDocumentCount());
        checksum = info.getChecksum();
    }
    EXPECT_EQ(Result(),
                         spi->put(bucket, Timestamp(3), doc1, context));

    {
        const BucketInfo info = spi->getBucketInfo(bucket).getBucketInfo();
        EXPECT_EQ(1, (int)info.getDocumentCount());
        EXPECT_EQ(checksum, info.getChecksum());
    }
    DocEntryList entries = iterateBucket(*spi, bucket, ALL_VERSIONS);
    EXPECT_EQ(size_t(1), entries.size());
}

TEST_F(ConformanceTest, testRemove)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));

    Bucket bucket(makeSpiBucket(BucketId(8, 0x01)));
    Document::SP doc1 = testDocMan.createRandomDocumentAtLocation(0x01, 1);
    Document::SP doc2 = testDocMan.createRandomDocumentAtLocation(0x01, 2);
    spi->createBucket(bucket, context);

    Result result = spi->put(bucket, Timestamp(3), doc1, context);

    {
        const BucketInfo info = spi->getBucketInfo(bucket).getBucketInfo();

        EXPECT_EQ(1, (int)info.getDocumentCount());
        EXPECT_TRUE(info.getChecksum() != 0);

        DocEntryList entries = iterateBucket(*spi, bucket, NEWEST_DOCUMENT_ONLY);
        EXPECT_EQ(size_t(1), entries.size());
    }

    // Add a remove entry
    RemoveResult result2 = spi->remove(bucket,
                                       Timestamp(5),
                                       doc1->getId(),
                                       context);

    {
        const BucketInfo info = spi->getBucketInfo(bucket).getBucketInfo();

        EXPECT_EQ(0, (int)info.getDocumentCount());
        EXPECT_EQ(0, (int)info.getChecksum());
        EXPECT_EQ(true, result2.wasFound());
    }
    {
        DocEntryList entries = iterateBucket(*spi, bucket,NEWEST_DOCUMENT_ONLY);
        EXPECT_EQ(size_t(0), entries.size());
    }
    {
        DocEntryList entries = iterateBucket(*spi, bucket,NEWEST_DOCUMENT_OR_REMOVE);

        EXPECT_EQ(size_t(1), entries.size());
    }

    // Result tagged as document not found
    RemoveResult result3 = spi->remove(bucket,
                                       Timestamp(7),
                                       doc1->getId(),
                                       context);
    {
        const BucketInfo info = spi->getBucketInfo(bucket).getBucketInfo();

        EXPECT_EQ(0, (int)info.getDocumentCount());
        EXPECT_EQ(0, (int)info.getChecksum());
        EXPECT_EQ(false, result3.wasFound());
    }

    Result result4 = spi->put(bucket, Timestamp(9), doc1, context);

    EXPECT_TRUE(!result4.hasError());

    RemoveResult result5 = spi->remove(bucket,
                                       Timestamp(9),
                                       doc1->getId(),
                                       context);
    {
        const BucketInfo info = spi->getBucketInfo(bucket).getBucketInfo();

        EXPECT_EQ(0, (int)info.getDocumentCount());
        EXPECT_EQ(0, (int)info.getChecksum());
        EXPECT_EQ(true, result5.wasFound());
        EXPECT_TRUE(!result5.hasError());
    }

    GetResult getResult = spi->get(bucket,
                                document::AllFields(),
                                doc1->getId(),
                                context);

    EXPECT_EQ(Result::ErrorType::NONE, getResult.getErrorCode());
    EXPECT_EQ(Timestamp(9), getResult.getTimestamp());
    EXPECT_TRUE(getResult.is_tombstone());
    EXPECT_FALSE(getResult.hasDocument());
}

TEST_F(ConformanceTest, testRemoveMulti)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));

    BucketId bucketId1(8, 0x01);
    Bucket bucket1(makeSpiBucket(bucketId1));
    Context context(Priority(0), Trace::TraceLevel(0));
    spi->createBucket(bucket1, context);

    std::vector<Document::SP> docs;
    for (size_t i(0); i < 30; i++) {
        docs.push_back(testDocMan.createRandomDocumentAtLocation(0x01, i));
    }

    std::vector<PersistenceProvider::TimeStampAndDocumentId> ids;
    for (size_t i(0); i < docs.size(); i++) {
        spi->put(bucket1, Timestamp(i), docs[i], context);
        if (i & 0x1) {
            ids.emplace_back(Timestamp(i), docs[i]->getId());
        }
    }

    auto onDone = std::make_unique<CatchResult>();
    auto future = onDone->future_result();
    spi->removeAsync(bucket1, ids, context, std::move(onDone));
    auto result = future.get();
    ASSERT_TRUE(result);
    auto removeResult = dynamic_cast<spi::RemoveResult *>(result.get());
    ASSERT_TRUE(removeResult != nullptr);
    EXPECT_EQ(15u, removeResult->num_removed());
}

TEST_F(ConformanceTest, testRemoveMerge)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));

    Bucket bucket(makeSpiBucket(BucketId(8, 0x01)));
    Document::SP doc1 = testDocMan.createRandomDocumentAtLocation(0x01, 1);
    DocumentId removeId("id:fraggle:testdoctype1:n=1:rock");
    spi->createBucket(bucket, context);

    Result result = spi->put(bucket, Timestamp(3), doc1, context);

    // Remove a document that does not exist
    {
        RemoveResult removeResult = spi->remove(bucket,
                                                Timestamp(10),
                                                removeId,
                                                context);
        EXPECT_EQ(Result::ErrorType::NONE, removeResult.getErrorCode());
        EXPECT_EQ(false, removeResult.wasFound());
    }
    {
        const BucketInfo info = spi->getBucketInfo(bucket).getBucketInfo();

        EXPECT_EQ(uint32_t(1), info.getDocumentCount());
        EXPECT_EQ(uint32_t(2), info.getEntryCount());
        EXPECT_TRUE(info.getChecksum() != 0);
    }

    // Remove entry should exist afterwards
    {
        DocEntryList entries = iterateBucket(*spi, bucket, ALL_VERSIONS);
        EXPECT_EQ(size_t(2), entries.size());
        // Timestamp-sorted by iterateBucket
        EXPECT_EQ(removeId, *entries.back()->getDocumentId());
        EXPECT_EQ(Timestamp(10), entries.back()->getTimestamp());
        EXPECT_TRUE(entries.back()->isRemove());
    }
    // Add a _newer_ remove for the same document ID we already removed
    {
        RemoveResult removeResult = spi->remove(bucket,
                                                Timestamp(11),
                                                removeId,
                                                context);
        EXPECT_EQ(Result::ErrorType::NONE, removeResult.getErrorCode());
        EXPECT_EQ(false, removeResult.wasFound());
    }
    // Old entry may or may not be present, depending on the provider.
    {
        const BucketInfo info = spi->getBucketInfo(bucket).getBucketInfo();

        EXPECT_EQ(uint32_t(1), info.getDocumentCount());
        EXPECT_TRUE(info.getEntryCount() >= 2);
        EXPECT_TRUE(info.getChecksum() != 0);
    }
    // Must have new remove. We don't check for the presence of the old remove.
    {
        DocEntryList entries = iterateBucket(*spi, bucket, ALL_VERSIONS);
        EXPECT_TRUE(entries.size() >= 2);
        EXPECT_EQ(removeId, *entries.back()->getDocumentId());
        EXPECT_EQ(Timestamp(11), entries.back()->getTimestamp());
        EXPECT_TRUE(entries.back()->isRemove());
    }
    // Add an _older_ remove for the same document ID we already removed.
    // It may or may not be present in a subsequent iteration, but the
    // newest timestamp must still be present.
    {
        RemoveResult removeResult = spi->remove(bucket,
                                                Timestamp(7),
                                                removeId,
                                                context);
        EXPECT_EQ(Result::ErrorType::NONE, removeResult.getErrorCode());
        EXPECT_EQ(false, removeResult.wasFound());
    }
    {
        const BucketInfo info = spi->getBucketInfo(bucket).getBucketInfo();

        EXPECT_EQ(uint32_t(1), info.getDocumentCount());
        EXPECT_TRUE(info.getEntryCount() >= 2);
        EXPECT_TRUE(info.getChecksum() != 0);
    }
    // Must have newest remove. We don't check for the presence of the old remove.
    {
        DocEntryList entries = iterateBucket(*spi, bucket, ALL_VERSIONS);
        EXPECT_TRUE(entries.size() >= 2);
        EXPECT_EQ(removeId, *entries.back()->getDocumentId());
        EXPECT_EQ(Timestamp(11), entries.back()->getTimestamp());
        EXPECT_TRUE(entries.back()->isRemove());
    }
}

TEST_F(ConformanceTest, testUpdate)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Document::SP doc1 = testDocMan.createRandomDocumentAtLocation(0x01, 1);
    Context context(Priority(0), Trace::TraceLevel(0));

    Bucket bucket(makeSpiBucket(BucketId(8, 0x01)));
    spi->createBucket(bucket, context);

    const document::DocumentType *docType(
            testDocMan.getTypeRepo().getDocumentType("testdoctype1"));
    document::DocumentUpdate::SP update(new DocumentUpdate(testDocMan.getTypeRepo(), *docType, doc1->getId()));
    std::shared_ptr<document::AssignValueUpdate> assignUpdate(new document::AssignValueUpdate(document::IntFieldValue(42)));
    document::FieldUpdate fieldUpdate(docType->getField("headerval"));
    fieldUpdate.addUpdate(*assignUpdate);
    update->addUpdate(fieldUpdate);

    {
        UpdateResult result = spi->update(bucket, Timestamp(3), update,
                                          context);
        EXPECT_EQ(Result(), Result(result));
        EXPECT_EQ(Timestamp(0), result.getExistingTimestamp());
    }

    spi->put(bucket, Timestamp(3), doc1, context);
    {
        UpdateResult result = spi->update(bucket, Timestamp(4), update,
                                          context);

        EXPECT_EQ(Result::ErrorType::NONE, result.getErrorCode());
        EXPECT_EQ(Timestamp(3), result.getExistingTimestamp());
    }

    {
        GetResult result = spi->get(bucket,
                                    document::AllFields(),
                                    doc1->getId(),
                                    context);

        EXPECT_EQ(Result::ErrorType::NONE, result.getErrorCode());
        EXPECT_EQ(Timestamp(4), result.getTimestamp());
        EXPECT_FALSE(result.is_tombstone());
        EXPECT_EQ(document::IntFieldValue(42),
                             static_cast<document::IntFieldValue&>(
                                     *result.getDocument().getValue("headerval")));
    }

    spi->remove(bucket, Timestamp(5), doc1->getId(), context);

    {
        GetResult result = spi->get(bucket,
                                    document::AllFields(),
                                    doc1->getId(),
                                    context);

        EXPECT_EQ(Result::ErrorType::NONE, result.getErrorCode());
        EXPECT_EQ(Timestamp(5), result.getTimestamp());
        EXPECT_FALSE(result.hasDocument());
        EXPECT_TRUE(result.is_tombstone());
    }

    {
        UpdateResult result = spi->update(bucket, Timestamp(6), update,
                                          context);

        EXPECT_EQ(Result::ErrorType::NONE, result.getErrorCode());
        EXPECT_EQ(Timestamp(0), result.getExistingTimestamp());
    }

    {
        GetResult result = spi->get(bucket, document::AllFields(), doc1->getId(), context);
        EXPECT_EQ(Result::ErrorType::NONE, result.getErrorCode());
        EXPECT_EQ(Timestamp(5), result.getTimestamp());
        EXPECT_FALSE(result.hasDocument());
        EXPECT_TRUE(result.is_tombstone());
    }

    update->setCreateIfNonExistent(true);
    {
        // Document does not exist (and therefore its condition cannot match by definition),
        // but since CreateIfNonExistent is set it should be auto-created anyway.
        UpdateResult result = spi->update(bucket, Timestamp(7), update, context);
        EXPECT_EQ(Result::ErrorType::NONE, result.getErrorCode());
        EXPECT_EQ(Timestamp(7), result.getExistingTimestamp());
    }

    {
        GetResult result = spi->get(bucket, document::AllFields(), doc1->getId(), context);
        EXPECT_EQ(Result::ErrorType::NONE, result.getErrorCode());
        EXPECT_EQ(Timestamp(7), result.getTimestamp());
        EXPECT_FALSE(result.is_tombstone());
        EXPECT_EQ(document::IntFieldValue(42),
                             reinterpret_cast<document::IntFieldValue&>(
                                     *result.getDocument().getValue("headerval")));
    }
}

TEST_F(ConformanceTest, testGet)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Document::SP doc1 = testDocMan.createRandomDocumentAtLocation(0x01, 1);
    Context context(Priority(0), Trace::TraceLevel(0));

    Bucket bucket(makeSpiBucket(BucketId(8, 0x01)));
    spi->createBucket(bucket, context);

    {
        GetResult result = spi->get(bucket, document::AllFields(),
                                    doc1->getId(), context);

        EXPECT_EQ(Result::ErrorType::NONE, result.getErrorCode());
        EXPECT_EQ(Timestamp(0), result.getTimestamp());
        EXPECT_FALSE(result.is_tombstone());
    }

    spi->put(bucket, Timestamp(3), doc1, context);

    {
        GetResult result = spi->get(bucket, document::AllFields(),
                                    doc1->getId(), context);
        EXPECT_EQ(*doc1, result.getDocument());
        EXPECT_EQ(Timestamp(3), result.getTimestamp());
        EXPECT_FALSE(result.is_tombstone());
    }

    spi->remove(bucket, Timestamp(4), doc1->getId(), context);

    {
        GetResult result = spi->get(bucket, document::AllFields(),
                                    doc1->getId(), context);

        EXPECT_EQ(Result::ErrorType::NONE, result.getErrorCode());
        EXPECT_EQ(Timestamp(4), result.getTimestamp());
        EXPECT_TRUE(result.is_tombstone());
    }
}

/** Test that iterating special cases works. */
TEST_F(ConformanceTest, testIterateCreateIterator)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));
    Bucket b(makeSpiBucket(BucketId(8, 0x1)));
    spi->createBucket(b, context);

    spi::CreateIteratorResult result(
            createIterator(*spi, b, createSelection("")));
    EXPECT_EQ(Result::ErrorType::NONE, result.getErrorCode());
    // Iterator ID 0 means invalid iterator, so cannot be returned
    // from a successful createIterator call.
    EXPECT_TRUE(result.getIteratorId() != IteratorId(0));

    spi->destroyIterator(result.getIteratorId(), context);
}

TEST_F(ConformanceTest, testIterateWithUnknownId)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));
    Bucket b(makeSpiBucket(BucketId(8, 0x1)));
    spi->createBucket(b, context);

    IteratorId unknownId(123);
    IterateResult result(spi->iterate(unknownId, 1024, context));
    EXPECT_EQ(Result::ErrorType::PERMANENT_ERROR, result.getErrorCode());
}

TEST_F(ConformanceTest, testIterateDestroyIterator)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));
    Bucket b(makeSpiBucket(BucketId(8, 0x1)));
    spi->createBucket(b, context);

    CreateIteratorResult iter(createIterator(*spi, b, createSelection("")));
    {
        IterateResult result(spi->iterate(iter.getIteratorId(), 1024, context));
        EXPECT_EQ(Result::ErrorType::NONE, result.getErrorCode());
    }

    {
        Result destroyResult(
                spi->destroyIterator(iter.getIteratorId(), context));
        EXPECT_TRUE(!destroyResult.hasError());
    }
    // Iteration should now fail
    {
        IterateResult result(spi->iterate(iter.getIteratorId(), 1024, context));
        EXPECT_EQ(Result::ErrorType::PERMANENT_ERROR, result.getErrorCode());
    }
    {
        Result destroyResult(
                spi->destroyIterator(iter.getIteratorId(), context));
        EXPECT_TRUE(!destroyResult.hasError());
    }
}

TEST_F(ConformanceTest, testIterateAllDocs)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));
    Bucket b(makeSpiBucket(BucketId(8, 0x1)));
    spi->createBucket(b, context);

    std::vector<DocAndTimestamp> docs(feedDocs(*spi, testDocMan, b, 100));
    CreateIteratorResult iter(createIterator(*spi, b, createSelection("")));

    std::vector<Chunk> chunks = doIterate(*spi, iter.getIteratorId(), 4_Ki);
    verifyDocs(docs, chunks);

    spi->destroyIterator(iter.getIteratorId(), context);
}

TEST_F(ConformanceTest, testIterateAllDocsNewestVersionOnly)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));
    Bucket b(makeSpiBucket(BucketId(8, 0x1)));
    spi->createBucket(b, context);

    std::vector<DocAndTimestamp> docs(feedDocs(*spi, testDocMan, b, 100));
    std::vector<DocAndTimestamp> newDocs;

    for (size_t i = 0; i < docs.size(); ++i) {
        Document::SP newDoc(docs[i].doc->clone());
        Timestamp newTimestamp(2000 + i);
        newDoc->setValue("headerval", document::IntFieldValue(5678 + i));
        spi->put(b, newTimestamp, newDoc, context);
        newDocs.push_back(DocAndTimestamp(newDoc, newTimestamp));
    }

    CreateIteratorResult iter(createIterator(*spi, b, createSelection("")));

    std::vector<Chunk> chunks = doIterate(*spi, iter.getIteratorId(), 4_Ki);
    verifyDocs(newDocs, chunks);

    spi->destroyIterator(iter.getIteratorId(), context);
}

TEST_F(ConformanceTest, testIterateChunked)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));
    Bucket b(makeSpiBucket(BucketId(8, 0x1)));
    spi->createBucket(b, context);

    std::vector<DocAndTimestamp> docs(feedDocs(*spi, testDocMan, b, 100));
    CreateIteratorResult iter(createIterator(*spi, b, createSelection("")));

    // Max byte size is 1, so only 1 document should be included in each chunk.
    std::vector<Chunk> chunks = doIterate(*spi, iter.getIteratorId(), 1);
    EXPECT_EQ(size_t(100), chunks.size());
    verifyDocs(docs, chunks);

    spi->destroyIterator(iter.getIteratorId(), context);
}

TEST_F(ConformanceTest, testMaxByteSize)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));
    Bucket b(makeSpiBucket(BucketId(8, 0x1)));
    spi->createBucket(b, context);

    std::vector<DocAndTimestamp> docs(
            feedDocs(*spi, testDocMan, b, 100, 4_Ki, 4096));

    Selection sel(createSelection(""));
    CreateIteratorResult iter(createIterator(*spi, b, sel));

    // Docs are 4k each and iterating with max combined size of 10k.
    // Should receive no more than 3 docs in each chunk
    std::vector<Chunk> chunks = doIterate(*spi, iter.getIteratorId(), 10000);
    if (chunks.size() < 33) {
        FAIL() << "Expected >= 33 chunks, but got " << chunks.size();
    }
    verifyDocs(docs, chunks);

    spi->destroyIterator(iter.getIteratorId(), context);
}

TEST_F(ConformanceTest, testIterateMatchTimestampRange)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));
    Bucket b(makeSpiBucket(BucketId(8, 0x1)));
    spi->createBucket(b, context);

    std::vector<DocAndTimestamp> docsToVisit;
    Timestamp fromTimestamp(1010);
    Timestamp toTimestamp(1060);

    for (uint32_t i = 0; i < 99; i++) {
        Timestamp timestamp(1000 + i);
        document::Document::SP doc(
                testDocMan.createRandomDocumentAtLocation(
                        1, timestamp, 110, 110));

        spi->put(b, timestamp, doc, context);
        if (timestamp >= fromTimestamp && timestamp <= toTimestamp) {
            docsToVisit.push_back(
                    DocAndTimestamp(doc, Timestamp(1000 + i)));
        }
    }

    Selection sel = Selection(DocumentSelection(""));
    sel.setFromTimestamp(fromTimestamp);
    sel.setToTimestamp(toTimestamp);

    CreateIteratorResult iter(createIterator(*spi, b, sel));

    std::vector<Chunk> chunks = doIterate(*spi, iter.getIteratorId(), 2_Ki);
    verifyDocs(docsToVisit, chunks);

    spi->destroyIterator(iter.getIteratorId(), context);
}

TEST_F(ConformanceTest, testIterateExplicitTimestampSubset)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));
    Bucket b(makeSpiBucket(BucketId(8, 0x1)));
    spi->createBucket(b, context);

    std::vector<DocAndTimestamp> docsToVisit;
    std::vector<Timestamp> timestampsToVisit;
    std::set<vespalib::string> removes;

    for (uint32_t i = 0; i < 99; i++) {
        Timestamp timestamp(1000 + i);
        document::Document::SP doc(
                testDocMan.createRandomDocumentAtLocation(
                        1, timestamp, 110, 110));

        spi->put(b, timestamp, doc, context);
        if (timestamp % 3 == 0) {
            docsToVisit.push_back(
                    DocAndTimestamp(doc, Timestamp(1000 + i)));
            timestampsToVisit.push_back(Timestamp(timestamp));
        }
    }
    // Timestamp subset should include removes without
    // having to explicitly specify it
    EXPECT_TRUE(spi->remove(b,
                               Timestamp(2000),
                               docsToVisit.front().doc->getId(), context)
                   .wasFound());

    timestampsToVisit.push_back(Timestamp(2000));
    removes.insert(docsToVisit.front().doc->getId().toString());
    docsToVisit.erase(docsToVisit.begin());
    timestampsToVisit.erase(timestampsToVisit.begin());

    Selection sel(createSelection(""));
    sel.setTimestampSubset(timestampsToVisit);

    CreateIteratorResult iter(createIterator(*spi, b, sel));

    std::vector<Chunk> chunks = doIterate(*spi, iter.getIteratorId(), 2_Ki);
    verifyDocs(docsToVisit, chunks, removes);

    spi->destroyIterator(iter.getIteratorId(), context);
}

TEST_F(ConformanceTest, testIterateRemoves)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));
    Bucket b(makeSpiBucket(BucketId(8, 0x1)));
    spi->createBucket(b, context);

    int docCount = 10;
    std::vector<DocAndTimestamp> docs(feedDocs(*spi, testDocMan, b, docCount));
    std::set<vespalib::string> removedDocs;
    std::vector<DocAndTimestamp> nonRemovedDocs;

    for (int i = 0; i < docCount; ++i) {
        if (i % 3 == 0) {
            removedDocs.insert(docs[i].doc->getId().toString());
            EXPECT_TRUE(spi->remove(b,
                                       Timestamp(2000 + i),
                                       docs[i].doc->getId(),
                                       context)
                           .wasFound());
        } else {
            nonRemovedDocs.push_back(docs[i]);
        }
    }

    // First, test iteration without removes
    {
        Selection sel(createSelection(""));
        CreateIteratorResult iter(createIterator(*spi, b, sel));

        std::vector<Chunk> chunks = doIterate(*spi, iter.getIteratorId(), 4_Ki);
        verifyDocs(nonRemovedDocs, chunks);
        spi->destroyIterator(iter.getIteratorId(), context);
    }

    {
        Selection sel(createSelection(""));
        CreateIteratorResult iter(createIterator(*spi, b, sel, NEWEST_DOCUMENT_OR_REMOVE));

        std::vector<Chunk> chunks = doIterate(*spi, iter.getIteratorId(), 4_Ki);
        DocEntryList entries = getEntriesFromChunks(chunks);
        EXPECT_EQ(docs.size(), entries.size());
        verifyDocs(nonRemovedDocs, chunks, removedDocs);

        spi->destroyIterator(iter.getIteratorId(), context);
    }
}

TEST_F(ConformanceTest, testIterateMatchSelection)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));
    Bucket b(makeSpiBucket(BucketId(8, 0x1)));
    spi->createBucket(b, context);

    std::vector<DocAndTimestamp> docsToVisit;

    for (uint32_t i = 0; i < 99; i++) {
        document::Document::SP doc(testDocMan.createRandomDocumentAtLocation(
                                           1, 1000 + i, 110, 110));
        doc->setValue("headerval", document::IntFieldValue(i));

        spi->put(b, Timestamp(1000 + i), doc, context);
        if ((i % 3) == 0) {
            docsToVisit.push_back(DocAndTimestamp(doc, Timestamp(1000 + i)));
        }
    }

    CreateIteratorResult iter(createIterator(*spi, b, createSelection("testdoctype1.headerval % 3 == 0")));

    std::vector<Chunk> chunks = doIterate(*spi, iter.getIteratorId(), 2_Mi);
    verifyDocs(docsToVisit, chunks);

    spi->destroyIterator(iter.getIteratorId(), context);
}

TEST_F(ConformanceTest, testIterationRequiringDocumentIdOnlyMatching)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));
    Bucket b(makeSpiBucket(BucketId(8, 0x1)));
    spi->createBucket(b, context);

    feedDocs(*spi, testDocMan, b, 100);
    DocumentId removedId("id:blarg:testdoctype1:n=1:unknowndoc");

    // Document does not already exist, remove should create a
    // remove entry for it regardless.
    EXPECT_TRUE(!spi->remove(b, Timestamp(2000), removedId, context).wasFound());

    Selection sel(createSelection("id == '" + removedId.toString() + "'"));

    CreateIteratorResult iter(createIterator(*spi, b, sel, NEWEST_DOCUMENT_OR_REMOVE));
    EXPECT_TRUE(iter.getErrorCode() == Result::ErrorType::NONE);

    std::vector<Chunk> chunks = doIterate(*spi, iter.getIteratorId(), 4_Ki);
    std::vector<DocAndTimestamp> docs;
    std::set<vespalib::string> removes;
    removes.insert(removedId.toString());
    verifyDocs(docs, chunks, removes);

    spi->destroyIterator(iter.getIteratorId(), context);
}

TEST_F(ConformanceTest, testIterateBadDocumentSelection)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));
    Bucket b(makeSpiBucket(BucketId(8, 0x1)));
    spi->createBucket(b, context);
    {
        CreateIteratorResult iter(createIterator(*spi, b, createSelection("the muppet show")));
        if (iter.getErrorCode() == Result::ErrorType::NONE) {
            IterateResult result(spi->iterate(iter.getIteratorId(), 4_Ki, context));
            EXPECT_EQ(Result::ErrorType::NONE, result.getErrorCode());
            EXPECT_EQ(size_t(0), result.getEntries().size());
            EXPECT_EQ(true, result.isCompleted());
        } else {
            EXPECT_EQ(Result::ErrorType::PERMANENT_ERROR, iter.getErrorCode());
            EXPECT_EQ(IteratorId(0), iter.getIteratorId());
        }
    }
    {
        CreateIteratorResult iter(createIterator(*spi, b, createSelection("unknownddoctype.something=thatthing")));
        if (iter.getErrorCode() == Result::ErrorType::NONE) {
            IterateResult result(spi->iterate(iter.getIteratorId(), 4_Ki, context));
            EXPECT_EQ(Result::ErrorType::NONE, result.getErrorCode());
            EXPECT_EQ(size_t(0), result.getEntries().size());
            EXPECT_EQ(true, result.isCompleted());
        } else {
            EXPECT_EQ(Result::ErrorType::PERMANENT_ERROR, iter.getErrorCode());
            EXPECT_EQ(IteratorId(0), iter.getIteratorId());
        }
    }
}

TEST_F(ConformanceTest, testIterateAlreadyCompleted)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));
    Bucket b(makeSpiBucket(BucketId(8, 0x1)));
    spi->createBucket(b, context);

    std::vector<DocAndTimestamp> docs = feedDocs(*spi, testDocMan, b, 10);
    Selection sel(createSelection(""));
    CreateIteratorResult iter(createIterator(*spi, b, sel));

    std::vector<Chunk> chunks = doIterate(*spi, iter.getIteratorId(), 4_Ki);
    verifyDocs(docs, chunks);

    IterateResult result(spi->iterate(iter.getIteratorId(), 4_Ki, context));
    EXPECT_EQ(Result::ErrorType::NONE, result.getErrorCode());
    EXPECT_EQ(size_t(0), result.getEntries().size());
    EXPECT_TRUE(result.isCompleted());

    spi->destroyIterator(iter.getIteratorId(), context);
}

void
ConformanceTest::test_iterate_empty_or_missing_bucket(bool bucket_exists)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));
    Bucket b(makeSpiBucket(BucketId(8, 0x1)));
    if (bucket_exists) {
        spi->createBucket(b, context);
    }
    Selection sel(createSelection(""));

    CreateIteratorResult iter(createIterator(*spi, b, sel));

    IterateResult result(spi->iterate(iter.getIteratorId(), 4_Ki, context));
    EXPECT_EQ(Result::ErrorType::NONE, result.getErrorCode());
    EXPECT_EQ(size_t(0), result.getEntries().size());
    EXPECT_TRUE(result.isCompleted());

    spi->destroyIterator(iter.getIteratorId(), context);
}

TEST_F(ConformanceTest, test_iterate_empty_bucket)
{
    test_iterate_empty_or_missing_bucket(true);
}

TEST_F(ConformanceTest, test_iterate_missing_bucket)
{
    test_iterate_empty_or_missing_bucket(false);
}

TEST_F(ConformanceTest, testDeleteBucket)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));
    Document::SP doc1 = testDocMan.createRandomDocumentAtLocation(0x01, 1);

    Bucket bucket(makeSpiBucket(BucketId(8, 0x01)));
    spi->createBucket(bucket, context);

    spi->put(bucket, Timestamp(3), doc1, context);

    spi->deleteBucket(bucket, context);
    testDeleteBucketPostCondition(*spi, bucket, *doc1);
    if (_factory->hasPersistence()) {
        spi.reset();
        document::TestDocMan testDocMan2;
        spi = getSpi(*_factory, testDocMan2);
        testDeleteBucketPostCondition(*spi, bucket, *doc1);
    }
}


void
ConformanceTest::
testDeleteBucketPostCondition(const PersistenceProvider &spi,
                              const Bucket &bucket,
                              const Document &doc1)
{
    Context context(Priority(0), Trace::TraceLevel(0));
    {
        GetResult result = spi.get(bucket,
                                   document::AllFields(),
                                   doc1.getId(),
                                   context);

        EXPECT_EQ(Result::ErrorType::NONE, result.getErrorCode());
        EXPECT_EQ(Timestamp(0), result.getTimestamp());
    }
}


TEST_F(ConformanceTest, testSplitNormalCase)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));

    Bucket bucketA(makeSpiBucket(BucketId(3, 0x02)));
    Bucket bucketB(makeSpiBucket(BucketId(3, 0x06)));

    Bucket bucketC(makeSpiBucket(BucketId(2, 0x02)));
    spi->createBucket(bucketC, context);

    TimestampList tsList;
    for (uint32_t i = 0; i < 10; ++i) {
        Document::SP doc1 = testDocMan.createRandomDocumentAtLocation(0x02, i);
        spi->put(bucketC, Timestamp(i + 1), doc1, context);
    }

    for (uint32_t i = 10; i < 20; ++i) {
        Document::SP doc1 = testDocMan.createRandomDocumentAtLocation(0x06, i);
        spi->put(bucketC, Timestamp(i + 1), doc1, context);
    }

    spi->split(bucketC, bucketA, bucketB, context);
    testSplitNormalCasePostCondition(*spi, bucketA, bucketB, bucketC, testDocMan);
    if (_factory->hasPersistence()) {
        spi.reset();
        document::TestDocMan testDocMan2;
        spi = getSpi(*_factory, testDocMan2);
        testSplitNormalCasePostCondition(*spi, bucketA, bucketB, bucketC, testDocMan2);
    }
}


void
ConformanceTest::
testSplitNormalCasePostCondition(const PersistenceProvider &spi,
                                 const Bucket &bucketA,
                                 const Bucket &bucketB,
                                 const Bucket &bucketC,
                                 document::TestDocMan &testDocMan)
{
    EXPECT_EQ(10, (int)spi.getBucketInfo(bucketA).getBucketInfo().getDocumentCount());
    EXPECT_EQ(10, (int)spi.getBucketInfo(bucketB).getBucketInfo().getDocumentCount());

    document::AllFields fs;
    Context context(Priority(0), Trace::TraceLevel(0));
    for (uint32_t i = 0; i < 10; ++i) {
        Document::UP doc1 = testDocMan.createRandomDocumentAtLocation(0x02, i);
        EXPECT_TRUE(spi.get(bucketA, fs, doc1->getId(), context).hasDocument());
        EXPECT_TRUE(!spi.get(bucketC, fs, doc1->getId(), context).hasDocument());
        EXPECT_TRUE(!spi.get(bucketB, fs, doc1->getId(), context).hasDocument());
    }

    for (uint32_t i = 10; i < 20; ++i) {
        Document::UP doc1 = testDocMan.createRandomDocumentAtLocation(0x06, i);
        EXPECT_TRUE(spi.get(bucketB, fs, doc1->getId(), context).hasDocument());
        EXPECT_TRUE(!spi.get(bucketA, fs, doc1->getId(), context).hasDocument());
        EXPECT_TRUE(!spi.get(bucketC, fs, doc1->getId(), context).hasDocument());
    }
}

TEST_F(ConformanceTest, testSplitTargetExists)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));

    Bucket bucketA(makeSpiBucket(BucketId(3, 0x02)));
    Bucket bucketB(makeSpiBucket(BucketId(3, 0x06)));
    spi->createBucket(bucketB, context);

    Bucket bucketC(makeSpiBucket(BucketId(2, 0x02)));
    spi->createBucket(bucketC, context);

    TimestampList tsList;
    for (uint32_t i = 0; i < 10; ++i) {
        Document::SP doc1 = testDocMan.createRandomDocumentAtLocation(0x02, i);
        spi->put(bucketC, Timestamp(i + 1), doc1, context);
    }


    for (uint32_t i = 10; i < 20; ++i) {
        Document::SP doc1 = testDocMan.createRandomDocumentAtLocation(0x06, i);
        spi->put(bucketB, Timestamp(i + 1), doc1, context);
    }
    EXPECT_TRUE(!spi->getBucketInfo(bucketB).getBucketInfo().isActive());

    for (uint32_t i = 10; i < 20; ++i) {
        Document::SP doc1 = testDocMan.createRandomDocumentAtLocation(0x06, i);
        spi->put(bucketC, Timestamp(i + 1), doc1, context);
    }

    for (uint32_t i = 20; i < 25; ++i) {
        Document::SP doc1 = testDocMan.createRandomDocumentAtLocation(0x06, i);
        spi->put(bucketB, Timestamp(i + 1), doc1, context);
    }

    spi->split(bucketC, bucketA, bucketB, context);
    testSplitTargetExistsPostCondition(*spi, bucketA, bucketB, bucketC,testDocMan);
    if (_factory->hasPersistence()) {
        spi.reset();
        document::TestDocMan testDocMan2;
        spi = getSpi(*_factory, testDocMan2);
        testSplitTargetExistsPostCondition(*spi, bucketA, bucketB, bucketC,testDocMan2);
    }
}


void
ConformanceTest::
testSplitTargetExistsPostCondition(const PersistenceProvider &spi,
                                   const Bucket &bucketA,
                                   const Bucket &bucketB,
                                   const Bucket &bucketC,
                                   document::TestDocMan &testDocMan)
{
    EXPECT_EQ(10, (int)spi.getBucketInfo(bucketA).getBucketInfo().
                         getDocumentCount());
    EXPECT_EQ(15, (int)spi.getBucketInfo(bucketB).getBucketInfo().
                         getDocumentCount());

    document::AllFields fs;
    Context context(Priority(0), Trace::TraceLevel(0));
    for (uint32_t i = 0; i < 10; ++i) {
        Document::UP doc1 = testDocMan.createRandomDocumentAtLocation(0x02, i);
        EXPECT_TRUE(
                spi.get(bucketA, fs, doc1->getId(), context).hasDocument());
        EXPECT_TRUE(
                !spi.get(bucketC, fs, doc1->getId(), context).hasDocument());
        EXPECT_TRUE(
                !spi.get(bucketB, fs, doc1->getId(), context).hasDocument());
    }

    for (uint32_t i = 10; i < 25; ++i) {
        Document::UP doc1 = testDocMan.createRandomDocumentAtLocation(0x06, i);
        EXPECT_TRUE(
                spi.get(bucketB, fs, doc1->getId(), context).hasDocument());
        EXPECT_TRUE(
                !spi.get(bucketA, fs, doc1->getId(), context).hasDocument());
        EXPECT_TRUE(
                !spi.get(bucketC, fs, doc1->getId(), context).hasDocument());
    }
}

TEST_F(ConformanceTest, testSplitSingleDocumentInSource)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));

    Bucket target1(makeSpiBucket(BucketId(3, 0x02)));
    Bucket target2(makeSpiBucket(BucketId(3, 0x06)));

    Bucket source(makeSpiBucket(BucketId(2, 0x02)));
    spi->createBucket(source, context);

    // Create doc belonging in target2 after split.
    Document::SP doc = testDocMan.createRandomDocumentAtLocation(0x06, 0);
    spi->put(source, Timestamp(1), doc, context);

    spi->split(source, target1, target2, context);
    testSplitSingleDocumentInSourcePostCondition(*spi, source, target1, target2, testDocMan);

    if (_factory->hasPersistence()) {
        spi.reset();
        document::TestDocMan testDocMan2;
        spi = getSpi(*_factory, testDocMan2);
        testSplitSingleDocumentInSourcePostCondition(*spi, source, target1, target2, testDocMan2);
    }
}

void
ConformanceTest::testSplitSingleDocumentInSourcePostCondition(
        const PersistenceProvider& spi,
        const Bucket& source,
        const Bucket& target1,
        const Bucket& target2,
        document::TestDocMan& testDocMan)
{
    EXPECT_EQ(uint32_t(0), spi.getBucketInfo(source).getBucketInfo().getDocumentCount());
    EXPECT_EQ(uint32_t(0), spi.getBucketInfo(target1).getBucketInfo().getDocumentCount());
    EXPECT_EQ(uint32_t(1), spi.getBucketInfo(target2).getBucketInfo().getDocumentCount());

    document::AllFields fs;
    Context context(Priority(0), Trace::TraceLevel(0));
    Document::UP doc = testDocMan.createRandomDocumentAtLocation(0x06, 0);
    EXPECT_TRUE(spi.get(target2, fs, doc->getId(), context).hasDocument());
    EXPECT_TRUE(!spi.get(target1, fs, doc->getId(), context).hasDocument());
    EXPECT_TRUE(!spi.get(source, fs, doc->getId(), context).hasDocument());
}

void
ConformanceTest::createAndPopulateJoinSourceBuckets(
        PersistenceProvider& spi,
        const Bucket& source1,
        const Bucket& source2,
        document::TestDocMan& testDocMan)
{
    Context context(Priority(0), Trace::TraceLevel(0));

    spi.createBucket(source1, context);
    spi.createBucket(source2, context);

    for (uint32_t i = 0; i < 10; ++i) {
        Document::SP doc(
                testDocMan.createRandomDocumentAtLocation(
                    source1.getBucketId().getId(), i));
        spi.put(source1, Timestamp(i + 1), doc, context);
    }

    for (uint32_t i = 10; i < 20; ++i) {
        Document::SP doc(
                testDocMan.createRandomDocumentAtLocation(
                    source2.getBucketId().getId(), i));
        spi.put(source2, Timestamp(i + 1), doc, context);
    }
}

void
ConformanceTest::doTestJoinNormalCase(const Bucket& source1,
                                      const Bucket& source2,
                                      const Bucket& target)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));

    createAndPopulateJoinSourceBuckets(*spi, source1, source2, testDocMan);

    Context context(Priority(0), Trace::TraceLevel(0));
    spi->join(source1, source2, target, context);

    testJoinNormalCasePostCondition(*spi, source1, source2, target, testDocMan);
    if (_factory->hasPersistence()) {
        spi.reset();
        document::TestDocMan testDocMan2;
        spi = getSpi(*_factory, testDocMan2);
        testJoinNormalCasePostCondition(*spi, source1, source2, target, testDocMan2);
    }
}

TEST_F(ConformanceTest, testJoinNormalCase)
{
    Bucket source1(makeSpiBucket(BucketId(3, 0x02)));
    Bucket source2(makeSpiBucket(BucketId(3, 0x06)));
    Bucket target(makeSpiBucket(BucketId(2, 0x02)));
    doTestJoinNormalCase(source1, source2, target);
}

TEST_F(ConformanceTest, testJoinNormalCaseWithMultipleBitsDecreased)
{
    Bucket source1(makeSpiBucket(BucketId(3, 0x02)));
    Bucket source2(makeSpiBucket(BucketId(3, 0x06)));
    Bucket target(makeSpiBucket(BucketId(1, 0x00)));
    doTestJoinNormalCase(source1, source2, target);
}

void
ConformanceTest::
testJoinNormalCasePostCondition(const PersistenceProvider &spi,
                                const Bucket &bucketA,
                                const Bucket &bucketB,
                                const Bucket &bucketC,
                                document::TestDocMan &testDocMan)
{
    EXPECT_EQ(20, (int)spi.getBucketInfo(bucketC).getBucketInfo().getDocumentCount());

    document::AllFields fs;
    Context context(Priority(0), Trace::TraceLevel(0));
    for (uint32_t i = 0; i < 10; ++i) {
        Document::UP doc(
                testDocMan.createRandomDocumentAtLocation(
                    bucketA.getBucketId().getId(), i));
        EXPECT_TRUE(spi.get(bucketC, fs, doc->getId(), context).hasDocument());
        EXPECT_TRUE(!spi.get(bucketA, fs, doc->getId(), context).hasDocument());
    }

    for (uint32_t i = 10; i < 20; ++i) {
        Document::UP doc(testDocMan.createRandomDocumentAtLocation(bucketB.getBucketId().getId(), i));
        EXPECT_TRUE(spi.get(bucketC, fs, doc->getId(), context).hasDocument());
        EXPECT_TRUE(!spi.get(bucketB, fs, doc->getId(), context).hasDocument());
    }
}


TEST_F(ConformanceTest, testJoinTargetExists)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));

    Bucket bucketA(makeSpiBucket(BucketId(3, 0x02)));
    spi->createBucket(bucketA, context);

    Bucket bucketB(makeSpiBucket(BucketId(3, 0x06)));
    spi->createBucket(bucketB, context);

    Bucket bucketC(makeSpiBucket(BucketId(2, 0x02)));
    spi->createBucket(bucketC, context);

    for (uint32_t i = 0; i < 10; ++i) {
        Document::SP doc1 = testDocMan.createRandomDocumentAtLocation(0x02, i);
        spi->put(bucketA, Timestamp(i + 1), doc1, context);
    }


    for (uint32_t i = 10; i < 20; ++i) {
        Document::SP doc1 = testDocMan.createRandomDocumentAtLocation(0x06, i);
        spi->put(bucketB, Timestamp(i + 1), doc1, context);
    }

    for (uint32_t i = 20; i < 30; ++i) {
        Document::SP doc1 = testDocMan.createRandomDocumentAtLocation(0x06, i);
        spi->put(bucketC, Timestamp(i + 1), doc1, context);
    }

    spi->join(bucketA, bucketB, bucketC, context);
    testJoinTargetExistsPostCondition(*spi, bucketA, bucketB, bucketC, testDocMan);
    if (_factory->hasPersistence()) {
        spi.reset();
        document::TestDocMan testDocMan2;
        spi = getSpi(*_factory, testDocMan2);
        testJoinTargetExistsPostCondition(*spi, bucketA, bucketB, bucketC, testDocMan2);
    }
}


void
ConformanceTest::
testJoinTargetExistsPostCondition(const PersistenceProvider &spi,
                                  const Bucket &bucketA,
                                  const Bucket &bucketB,
                                  const Bucket &bucketC,
                                  document::TestDocMan &testDocMan)
{
    EXPECT_EQ(30, (int)spi.getBucketInfo(bucketC).getBucketInfo().getDocumentCount());

    document::AllFields fs;
    Context context(Priority(0), Trace::TraceLevel(0));
    for (uint32_t i = 0; i < 10; ++i) {
        Document::UP doc1 = testDocMan.createRandomDocumentAtLocation(0x02, i);
        EXPECT_TRUE(spi.get(bucketC, fs, doc1->getId(), context).hasDocument());
        EXPECT_TRUE(!spi.get(bucketA, fs, doc1->getId(), context).hasDocument());
    }

    for (uint32_t i = 10; i < 20; ++i) {
        Document::UP doc1 = testDocMan.createRandomDocumentAtLocation(0x06, i);
        EXPECT_TRUE(spi.get(bucketC, fs, doc1->getId(), context).hasDocument());
        EXPECT_TRUE(!spi.get(bucketB, fs, doc1->getId(), context).hasDocument());
    }

    for (uint32_t i = 20; i < 30; ++i) {
        Document::UP doc1 = testDocMan.createRandomDocumentAtLocation(0x06, i);
        EXPECT_TRUE(spi.get(bucketC, fs, doc1->getId(), context).hasDocument());
    }
}

void
ConformanceTest::populateBucket(const Bucket& b,
                                PersistenceProvider& spi,
                                Context& context,
                                uint32_t from,
                                uint32_t to,
                                document::TestDocMan& testDocMan)
{
    assert(from <= to);
    for (uint32_t i = from; i < to; ++i) {
        const uint32_t location = b.getBucketId().getId();
        Document::SP doc1 = testDocMan.createRandomDocumentAtLocation(location, i);
        spi.put(b, Timestamp(i + 1), doc1, context);
    }
}

TEST_F(ConformanceTest, testJoinOneBucket)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));

    Bucket bucketA(makeSpiBucket(BucketId(3, 0x02)));
    spi->createBucket(bucketA, context);

    Bucket bucketB(makeSpiBucket(BucketId(3, 0x06)));
    Bucket bucketC(makeSpiBucket(BucketId(2, 0x02)));

    populateBucket(bucketA, *spi, context, 0, 10, testDocMan);

    spi->join(bucketA, bucketB, bucketC, context);
    testJoinOneBucketPostCondition(*spi, bucketA, bucketC, testDocMan);
    if (_factory->hasPersistence()) {
        spi.reset();
        document::TestDocMan testDocMan2;
        spi = getSpi(*_factory, testDocMan2);
        testJoinOneBucketPostCondition(*spi, bucketA, bucketC, testDocMan2);
    }
}

void
ConformanceTest::
testJoinOneBucketPostCondition(const PersistenceProvider &spi,
                               const Bucket &bucketA,
                               const Bucket &bucketC,
                               document::TestDocMan &testDocMan)
{
    EXPECT_EQ(10, (int)spi.getBucketInfo(bucketC).getBucketInfo().getDocumentCount());

    document::AllFields fs;
    Context context(Priority(0), Trace::TraceLevel(0));
    for (uint32_t i = 0; i < 10; ++i) {
        Document::UP doc1 = testDocMan.createRandomDocumentAtLocation(0x02, i);
        EXPECT_TRUE(spi.get(bucketC, fs, doc1->getId(), context).hasDocument());
        EXPECT_TRUE(!spi.get(bucketA, fs, doc1->getId(), context).hasDocument());
    }
}

void
ConformanceTest::
testJoinSameSourceBucketsPostCondition(
        const PersistenceProvider& spi,
        const Bucket& source,
        const Bucket& target,
        document::TestDocMan& testDocMan)
{
    // Same post conditions as joinOneBucket case
    testJoinOneBucketPostCondition(spi, source, target, testDocMan);
}

void
ConformanceTest::doTestJoinSameSourceBuckets(const Bucket& source, const Bucket& target)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));

    spi->createBucket(source, context);
    populateBucket(source, *spi, context, 0, 10, testDocMan);

    spi->join(source, source, target, context);
    testJoinSameSourceBucketsPostCondition(*spi, source, target, testDocMan);
    if (_factory->hasPersistence()) {
        spi.reset();
        document::TestDocMan testDocMan2;
        spi = getSpi(*_factory, testDocMan2);
        testJoinSameSourceBucketsPostCondition(*spi, source, target, testDocMan2);
    }
}

TEST_F(ConformanceTest, testJoinSameSourceBuckets)
{
    Bucket source(makeSpiBucket(BucketId(3, 0x02)));
    Bucket target(makeSpiBucket(BucketId(2, 0x02)));
    doTestJoinSameSourceBuckets(source, target);
}

TEST_F(ConformanceTest, testJoinSameSourceBucketsWithMultipleBitsDecreased)
{
    Bucket source(makeSpiBucket(BucketId(3, 0x02)));
    Bucket target(makeSpiBucket(BucketId(1, 0x00)));
    doTestJoinSameSourceBuckets(source, target);
}

void
ConformanceTest::testJoinSameSourceBucketsTargetExistsPostCondition(
        const PersistenceProvider& spi,
        const Bucket& source,
        const Bucket& target,
        document::TestDocMan& testDocMan)
{
    EXPECT_EQ(20, (int)spi.getBucketInfo(target).getBucketInfo().getDocumentCount());

    document::AllFields fs;
    Context context(Priority(0), Trace::TraceLevel(0));
    for (uint32_t i = 0; i < 20; ++i) {
        Document::UP doc1 = testDocMan.createRandomDocumentAtLocation(0x02, i);
        EXPECT_TRUE(spi.get(target, fs, doc1->getId(), context).hasDocument());
        EXPECT_TRUE(!spi.get(source, fs, doc1->getId(), context).hasDocument());
    }
}

TEST_F(ConformanceTest, testJoinSameSourceBucketsTargetExists)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));

    Bucket source(makeSpiBucket(BucketId(3, 0x02)));
    spi->createBucket(source, context);

    Bucket target(makeSpiBucket(BucketId(2, 0x02)));
    spi->createBucket(target, context);

    populateBucket(source, *spi, context, 0, 10, testDocMan);
    populateBucket(target, *spi, context, 10, 20, testDocMan);

    spi->join(source, source, target, context);
    testJoinSameSourceBucketsTargetExistsPostCondition(
            *spi, source, target, testDocMan);
    if (_factory->hasPersistence()) {
        spi.reset();
        document::TestDocMan testDocMan2;
        spi = getSpi(*_factory, testDocMan2);
        testJoinSameSourceBucketsTargetExistsPostCondition(*spi, source, target, testDocMan2);
    }
}

TEST_F(ConformanceTest, testGetModifiedBuckets)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    EXPECT_EQ(0, (int)spi->getModifiedBuckets(makeBucketSpace()).getList().size());
}

TEST_F(ConformanceTest, testBucketActivation)
{
    if (!_factory->supportsActiveState()) {
        return;
    }

    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));
    Bucket bucket(makeSpiBucket(BucketId(8, 0x01)));

    spi->setClusterState(makeBucketSpace(), createClusterState());
    spi->createBucket(bucket, context);
    EXPECT_TRUE(!spi->getBucketInfo(bucket).getBucketInfo().isActive());

    spi->setActiveState(bucket, BucketInfo::ACTIVE);
    EXPECT_TRUE(spi->getBucketInfo(bucket).getBucketInfo().isActive());

        // Add and remove a document, so document goes to zero, to check that
        // active state isn't cleared then.
    Document::SP doc1 = testDocMan.createRandomDocumentAtLocation(0x01, 1);
    EXPECT_EQ(
            Result(),
            Result(spi->put(bucket, Timestamp(1), doc1, context)));
    EXPECT_EQ(
            Result(),
            Result(spi->remove(bucket, Timestamp(5), doc1->getId(), context)));
    EXPECT_TRUE(spi->getBucketInfo(bucket).getBucketInfo().isActive());

        // Setting node down should clear active flag.
    spi->setClusterState(makeBucketSpace(), createClusterState(lib::State::DOWN));
    EXPECT_TRUE(!spi->getBucketInfo(bucket).getBucketInfo().isActive());
    spi->setClusterState(makeBucketSpace(), createClusterState(lib::State::UP));
    EXPECT_TRUE(!spi->getBucketInfo(bucket).getBucketInfo().isActive());

        // Actively clearing it should of course also clear it
    spi->setActiveState(bucket, BucketInfo::ACTIVE);
    EXPECT_TRUE(spi->getBucketInfo(bucket).getBucketInfo().isActive());
    spi->setActiveState(bucket, BucketInfo::NOT_ACTIVE);
    EXPECT_TRUE(!spi->getBucketInfo(bucket).getBucketInfo().isActive());
}

TEST_F(SingleDocTypeConformanceTest, testBucketActivationSplitAndJoin)
{
    if (!_factory->supportsActiveState()) {
        return;
    }

    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));

    Bucket bucketA(makeSpiBucket(BucketId(3, 0x02)));
    Bucket bucketB(makeSpiBucket(BucketId(3, 0x06)));
    Bucket bucketC(makeSpiBucket(BucketId(2, 0x02)));
    Document::SP doc1 = testDocMan.createRandomDocumentAtLocation(0x02, 1);
    Document::SP doc2 = testDocMan.createRandomDocumentAtLocation(0x06, 2);

    spi->setClusterState(makeBucketSpace(), createClusterState());
    spi->createBucket(bucketC, context);
    spi->put(bucketC, Timestamp(1), doc1, context);
    spi->put(bucketC, Timestamp(2), doc2, context);

    spi->setActiveState(bucketC, BucketInfo::ACTIVE);
    EXPECT_TRUE(spi->getBucketInfo(bucketC).getBucketInfo().isActive());
    spi->split(bucketC, bucketA, bucketB, context);
    EXPECT_TRUE(spi->getBucketInfo(bucketA).getBucketInfo().isActive());
    EXPECT_TRUE(spi->getBucketInfo(bucketB).getBucketInfo().isActive());
    EXPECT_TRUE(!spi->getBucketInfo(bucketC).getBucketInfo().isActive());

    spi->setActiveState(bucketA, BucketInfo::NOT_ACTIVE);
    spi->setActiveState(bucketB, BucketInfo::NOT_ACTIVE);
    spi->join(bucketA, bucketB, bucketC, context);
    EXPECT_TRUE(!spi->getBucketInfo(bucketA).getBucketInfo().isActive());
    EXPECT_TRUE(!spi->getBucketInfo(bucketB).getBucketInfo().isActive());
    EXPECT_TRUE(!spi->getBucketInfo(bucketC).getBucketInfo().isActive());

    spi->split(bucketC, bucketA, bucketB, context);
    EXPECT_TRUE(!spi->getBucketInfo(bucketA).getBucketInfo().isActive());
    EXPECT_TRUE(!spi->getBucketInfo(bucketB).getBucketInfo().isActive());
    EXPECT_TRUE(!spi->getBucketInfo(bucketC).getBucketInfo().isActive());

    spi->setActiveState(bucketA, BucketInfo::ACTIVE);
    spi->join(bucketA, bucketB, bucketC, context);
    EXPECT_TRUE(!spi->getBucketInfo(bucketA).getBucketInfo().isActive());
    EXPECT_TRUE(!spi->getBucketInfo(bucketB).getBucketInfo().isActive());
    EXPECT_TRUE(spi->getBucketInfo(bucketC).getBucketInfo().isActive());

        // Redo test with empty bucket, to ensure new buckets are generated
        // even if empty
    spi->deleteBucket(bucketA, context);
    spi->deleteBucket(bucketB, context);
    spi->deleteBucket(bucketC, context);

    spi->createBucket(bucketC, context);
    spi->setActiveState(bucketC, BucketInfo::NOT_ACTIVE);
    spi->split(bucketC, bucketA, bucketB, context);
    EXPECT_TRUE(!spi->getBucketInfo(bucketA).getBucketInfo().isActive());
    EXPECT_TRUE(!spi->getBucketInfo(bucketB).getBucketInfo().isActive());
    spi->join(bucketA, bucketB, bucketC, context);
    EXPECT_TRUE(!spi->getBucketInfo(bucketA).getBucketInfo().isActive());
    EXPECT_TRUE(!spi->getBucketInfo(bucketB).getBucketInfo().isActive());
    EXPECT_TRUE(!spi->getBucketInfo(bucketC).getBucketInfo().isActive());

    spi->deleteBucket(bucketA, context);
    spi->deleteBucket(bucketB, context);
    spi->deleteBucket(bucketC, context);

    spi->createBucket(bucketC, context);
    spi->setActiveState(bucketC, BucketInfo::ACTIVE);
    spi->split(bucketC, bucketA, bucketB, context);
    EXPECT_TRUE(spi->getBucketInfo(bucketA).getBucketInfo().isActive());
    EXPECT_TRUE(spi->getBucketInfo(bucketB).getBucketInfo().isActive());
    EXPECT_TRUE(!spi->getBucketInfo(bucketC).getBucketInfo().isActive());
    spi->join(bucketA, bucketB, bucketC, context);
    EXPECT_TRUE(!spi->getBucketInfo(bucketA).getBucketInfo().isActive());
    EXPECT_TRUE(!spi->getBucketInfo(bucketB).getBucketInfo().isActive());
    EXPECT_TRUE(spi->getBucketInfo(bucketC).getBucketInfo().isActive());
}

TEST_F(ConformanceTest, testRemoveEntry)
{
    if (!_factory->supportsRemoveEntry()) {
        return;
    }
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));

    Bucket bucket(makeSpiBucket(BucketId(8, 0x01)));
    Document::SP doc1 = testDocMan.createRandomDocumentAtLocation(0x01, 1);
    Document::SP doc2 = testDocMan.createRandomDocumentAtLocation(0x01, 2);
    spi->createBucket(bucket, context);

    spi->put(bucket, Timestamp(3), doc1, context);
    BucketInfo info1 = spi->getBucketInfo(bucket).getBucketInfo();

    {
        spi->put(bucket, Timestamp(4), doc2, context);
        spi->removeEntry(bucket, Timestamp(4), context);
        BucketInfo info2 = spi->getBucketInfo(bucket).getBucketInfo();
        EXPECT_EQ(info1, info2);
    }

    // Test case where there exists a previous version of the document.
    {
        spi->put(bucket, Timestamp(5), doc1, context);
        spi->removeEntry(bucket, Timestamp(5), context);
        BucketInfo info2 = spi->getBucketInfo(bucket).getBucketInfo();
        EXPECT_EQ(info1, info2);
    }

    // Test case where the newest document version after removeEntrying is a remove.
    {
        spi->remove(bucket, Timestamp(6), doc1->getId(), context);
        BucketInfo info2 = spi->getBucketInfo(bucket).getBucketInfo();
        EXPECT_EQ(uint32_t(0), info2.getDocumentCount());

        spi->put(bucket, Timestamp(7), doc1, context);
        spi->removeEntry(bucket, Timestamp(7), context);
        BucketInfo info3 = spi->getBucketInfo(bucket).getBucketInfo();
        EXPECT_EQ(info2, info3);
    }
}

void assertBucketInfo(PersistenceProvider &spi, const Bucket &bucket, uint32_t expDocCount)
{
    const BucketInfo info = spi.getBucketInfo(bucket).getBucketInfo();
    EXPECT_EQ(expDocCount, info.getDocumentCount());
    EXPECT_TRUE(info.getEntryCount() >= info.getDocumentCount());
    EXPECT_TRUE(info.getChecksum() != 0);
    EXPECT_TRUE(info.getDocumentSize() > 0);
    EXPECT_TRUE(info.getUsedSize() >= info.getDocumentSize());
}

void assertBucketList(PersistenceProvider &spi,
                      BucketSpace &bucketSpace,
                      const std::vector<BucketId> &expBuckets)
{
    BucketIdListResult result = spi.listBuckets(bucketSpace);
    const BucketIdListResult::List &bucketList = result.getList();
    EXPECT_EQ(expBuckets.size(), bucketList.size());
    for (const auto &expBucket : expBuckets) {
        EXPECT_TRUE(std::find(bucketList.begin(), bucketList.end(), expBucket) != bucketList.end());
    }
}

TEST_F(ConformanceTest, testBucketSpaces)
{
    if (!_factory->supportsBucketSpaces()) {
        return;
    }
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));
    BucketSpace bucketSpace0(makeBucketSpace("testdoctype1"));
    BucketSpace bucketSpace1(makeBucketSpace("testdoctype2"));
    BucketSpace bucketSpace2(makeBucketSpace("no"));

    BucketId bucketId1(8, 0x01);
    BucketId bucketId2(8, 0x02);
    Bucket bucket01({ bucketSpace0, bucketId1 });
    Bucket bucket11({ bucketSpace1, bucketId1 });
    Bucket bucket12({ bucketSpace1, bucketId2 });
    Document::SP doc1 = testDocMan.createDocument("content", "id:test:testdoctype1:n=1:1", "testdoctype1");
    Document::SP doc2 = testDocMan.createDocument("content", "id:test:testdoctype1:n=1:2", "testdoctype1");
    Document::SP doc3 = testDocMan.createDocument("content", "id:test:testdoctype2:n=1:3", "testdoctype2");
    Document::SP doc4 = testDocMan.createDocument("content", "id:test:testdoctype2:n=2:4", "testdoctype2");
    spi->createBucket(bucket01, context);
    spi->createBucket(bucket11, context);
    spi->createBucket(bucket12, context);
    spi->put(bucket01, Timestamp(3), doc1, context);
    spi->put(bucket01, Timestamp(4), doc2, context);
    spi->put(bucket11, Timestamp(5), doc3, context);
    spi->put(bucket12, Timestamp(6), doc4, context);
    // Check bucket lists
    assertBucketList(*spi, bucketSpace0, { bucketId1 });
    assertBucketList(*spi, bucketSpace1, { bucketId1, bucketId2 });
    assertBucketList(*spi, bucketSpace2, { });
    // Check bucket info
    assertBucketInfo(*spi, bucket01, 2);
    assertBucketInfo(*spi, bucket11, 1);
    assertBucketInfo(*spi, bucket12, 1);
}

TEST_F(ConformanceTest, resource_usage)
{
    ResourceUsageListener resource_usage_listener;
    document::TestDocMan testDocMan;
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    EXPECT_EQ(0.0, resource_usage_listener.get_usage().get_disk_usage());
    EXPECT_EQ(0.0, resource_usage_listener.get_usage().get_memory_usage());
    auto register_guard = spi->register_resource_usage_listener(resource_usage_listener);
    EXPECT_EQ(0.5, resource_usage_listener.get_usage().get_disk_usage());
    EXPECT_EQ(0.4, resource_usage_listener.get_usage().get_memory_usage());
}

void
ConformanceTest::test_empty_bucket_info(bool bucket_exists, bool active)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));
    Bucket bucket(makeSpiBucket(BucketId(8, 0x01)));
    spi->setClusterState(makeBucketSpace(), createClusterState());
    if (bucket_exists) {
        spi->createBucket(bucket, context);
    }
    if (active) {
        spi->setActiveState(bucket, BucketInfo::ACTIVE);
    }
    auto info_result = spi->getBucketInfo(bucket);
    EXPECT_TRUE(!info_result.hasError());
    EXPECT_EQ(0u, info_result.getBucketInfo().getChecksum().getValue());
    EXPECT_EQ(0u, info_result.getBucketInfo().getEntryCount());
    EXPECT_EQ(0u, info_result.getBucketInfo().getDocumentCount());
    EXPECT_TRUE(info_result.getBucketInfo().isReady());
    EXPECT_EQ(active, info_result.getBucketInfo().isActive());
}

TEST_F(ConformanceTest, test_empty_bucket_gives_empty_bucket_info)
{
    test_empty_bucket_info(true, false);
}

TEST_F(ConformanceTest, test_missing_bucket_gives_empty_bucket_info)
{
    test_empty_bucket_info(false, false);
}

TEST_F(ConformanceTest, test_empty_bucket_can_be_activated)
{
    test_empty_bucket_info(true, true);
}

TEST_F(ConformanceTest, test_missing_bucket_can_be_activated)
{
    test_empty_bucket_info(false, true);
}

TEST_F(ConformanceTest, test_put_to_missing_bucket)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));
    Bucket bucket(makeSpiBucket(BucketId(8, 0x01)));
    std::shared_ptr<Document> doc1 = testDocMan.createRandomDocumentAtLocation(0x01, 1);
    auto put_result = spi->put(bucket, Timestamp(1), doc1, context);
    EXPECT_TRUE(!put_result.hasError());
    auto info_result = spi->getBucketInfo(bucket);
    EXPECT_TRUE(!info_result.hasError());
    EXPECT_NE(0u, info_result.getBucketInfo().getChecksum().getValue());
    EXPECT_EQ(1u, info_result.getBucketInfo().getEntryCount());
    EXPECT_EQ(1u, info_result.getBucketInfo().getDocumentCount());
}

TEST_F(ConformanceTest, test_remove_to_missing_bucket)
{
    document::TestDocMan testDocMan;
    _factory->clear();
    PersistenceProviderUP spi(getSpi(*_factory, testDocMan));
    Context context(Priority(0), Trace::TraceLevel(0));
    Bucket bucket(makeSpiBucket(BucketId(8, 0x01)));
    std::shared_ptr<Document> doc1 = testDocMan.createRandomDocumentAtLocation(0x01, 1);
    auto remove_result = spi->remove(bucket, Timestamp(1), doc1->getId(), context);
    EXPECT_TRUE(!remove_result.hasError());
    auto info_result = spi->getBucketInfo(bucket);
    EXPECT_TRUE(!info_result.hasError());
    EXPECT_EQ(0u, info_result.getBucketInfo().getChecksum().getValue());
    EXPECT_EQ(1u, info_result.getBucketInfo().getEntryCount());
    EXPECT_EQ(0u, info_result.getBucketInfo().getDocumentCount());
}

TEST_F(ConformanceTest, detectAndTestOptionalBehavior)
{
    // Report if implementation supports setting bucket size info.

    // Report if joining same bucket on multiple partitions work.
    // (Where target equals one of the sources). (If not supported service
    // layer must die if a bucket is found during init on multiple partitions)
    // Test functionality if it works.
}

}
