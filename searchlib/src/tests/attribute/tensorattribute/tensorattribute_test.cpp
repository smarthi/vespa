// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/document/base/exceptions.h>
#include <vespa/eval/eval/simple_value.h>
#include <vespa/eval/eval/tensor_spec.h>
#include <vespa/eval/eval/value.h>
#include <vespa/eval/eval/test/value_compare.h>
#include <vespa/fastos/file.h>
#include <vespa/searchlib/attribute/attribute_read_guard.h>
#include <vespa/searchlib/attribute/attributeguard.h>
#include <vespa/searchlib/queryeval/nearest_neighbor_blueprint.h>
#include <vespa/searchlib/tensor/default_nearest_neighbor_index_factory.h>
#include <vespa/searchlib/tensor/dense_tensor_attribute.h>
#include <vespa/searchlib/tensor/direct_tensor_attribute.h>
#include <vespa/searchlib/tensor/doc_vector_access.h>
#include <vespa/searchlib/tensor/distance_functions.h>
#include <vespa/searchlib/tensor/hnsw_index.h>
#include <vespa/searchlib/tensor/nearest_neighbor_index.h>
#include <vespa/searchlib/tensor/nearest_neighbor_index_factory.h>
#include <vespa/searchlib/tensor/nearest_neighbor_index_loader.h>
#include <vespa/searchlib/tensor/nearest_neighbor_index_saver.h>
#include <vespa/searchlib/tensor/serialized_fast_value_attribute.h>
#include <vespa/searchlib/tensor/tensor_attribute.h>
#include <vespa/searchlib/test/directory_handler.h>
#include <vespa/searchlib/util/fileutil.h>
#include <vespa/vespalib/data/fileheader.h>
#include <vespa/vespalib/io/fileutil.h>
#include <vespa/vespalib/test/insertion_operators.h>
#include <vespa/vespalib/testkit/test_kit.h>
#include <vespa/vespalib/util/mmap_file_allocator_factory.h>
#include <vespa/searchlib/util/bufferwriter.h>
#include <vespa/vespalib/util/threadstackexecutor.h>

#include <vespa/log/log.h>
LOG_SETUP("tensorattribute_test");

using document::WrongTensorTypeException;
using search::AddressSpaceUsage;
using search::AttributeGuard;
using search::AttributeVector;
using search::attribute::DistanceMetric;
using search::attribute::HnswIndexParams;
using search::queryeval::GlobalFilter;
using search::queryeval::NearestNeighborBlueprint;
using search::tensor::DefaultNearestNeighborIndexFactory;
using search::tensor::DenseTensorAttribute;
using search::tensor::DirectTensorAttribute;
using search::tensor::DocVectorAccess;
using search::tensor::SerializedFastValueAttribute;
using search::tensor::HnswIndex;
using search::tensor::HnswNode;
using search::tensor::NearestNeighborIndex;
using search::tensor::NearestNeighborIndexFactory;
using search::tensor::NearestNeighborIndexLoader;
using search::tensor::NearestNeighborIndexSaver;
using search::tensor::PrepareResult;
using search::tensor::TensorAttribute;
using vespalib::datastore::CompactionStrategy;
using vespalib::eval::TensorSpec;
using vespalib::eval::CellType;
using vespalib::eval::ValueType;
using vespalib::eval::Value;
using vespalib::eval::SimpleValue;

using DoubleVector = std::vector<double>;
using generation_t = vespalib::GenerationHandler::generation_t;

vespalib::string sparseSpec("tensor(x{},y{})");
vespalib::string denseSpec("tensor(x[2],y[3])");
vespalib::string vec_2d_spec("tensor(x[2])");

Value::UP createTensor(const TensorSpec &spec) {
    return SimpleValue::from_spec(spec);
}

TensorSpec
vec_2d(double x0, double x1)
{
    return TensorSpec(vec_2d_spec).add({{"x", 0}}, x0).add({{"x", 1}}, x1);
}

class MockIndexSaver : public NearestNeighborIndexSaver {
private:
    int _index_value;

public:
    MockIndexSaver(int index_value) : _index_value(index_value) {}
    void save(search::BufferWriter& writer) const override {
        writer.write(&_index_value, sizeof(int));
        writer.flush();
    }
};

class MockIndexLoader : public NearestNeighborIndexLoader {
private:
    int& _index_value;
    search::FileReader<int> _reader;

public:
    MockIndexLoader(int& index_value, FastOS_FileInterface& file)
        : _index_value(index_value),
          _reader(file)
    {}
    bool load_next() override {
        _index_value = _reader.readHostOrder();
        return false;
    }
};

class MockPrepareResult : public PrepareResult {
public:
    uint32_t docid;
    MockPrepareResult(uint32_t docid_in) : docid(docid_in) {}
};

class MockNearestNeighborIndex : public NearestNeighborIndex {
private:
    using Entry = std::pair<uint32_t, DoubleVector>;
    using EntryVector = std::vector<Entry>;

    const DocVectorAccess& _vectors;
    EntryVector _adds;
    EntryVector _removes;
    mutable EntryVector _prepare_adds;
    EntryVector _complete_adds;
    generation_t _transfer_gen;
    generation_t _trim_gen;
    mutable size_t _memory_usage_cnt;
    int _index_value;

public:
    MockNearestNeighborIndex(const DocVectorAccess& vectors)
        : _vectors(vectors),
          _adds(),
          _removes(),
          _prepare_adds(),
          _complete_adds(),
          _transfer_gen(std::numeric_limits<generation_t>::max()),
          _trim_gen(std::numeric_limits<generation_t>::max()),
          _memory_usage_cnt(0),
          _index_value(0)
    {
    }
    void clear() {
        _adds.clear();
        _removes.clear();
        _prepare_adds.clear();
        _complete_adds.clear();
    }
    int get_index_value() const {
        return _index_value;
    }
    void save_index_with_value(int value) {
        _index_value = value;
    }
    void expect_empty_add() const {
        EXPECT_TRUE(_adds.empty());
    }
    void expect_entry(uint32_t exp_docid, const DoubleVector& exp_vector, const EntryVector& entries) const {
        EXPECT_EQUAL(1u, entries.size());
        EXPECT_EQUAL(exp_docid, entries.back().first);
        EXPECT_EQUAL(exp_vector, entries.back().second);
    }
    void expect_add(uint32_t exp_docid, const DoubleVector& exp_vector) const {
        expect_entry(exp_docid, exp_vector, _adds);
    }
    void expect_adds(const EntryVector &exp_adds) const {
        EXPECT_EQUAL(exp_adds, _adds);
    }
    void expect_prepare_adds(const EntryVector &exp) const {
        EXPECT_EQUAL(exp, _prepare_adds);
    }
    void expect_complete_adds(const EntryVector &exp) const {
        EXPECT_EQUAL(exp, _complete_adds);
    }
    void expect_empty_remove() const {
        EXPECT_TRUE(_removes.empty());
    }
    void expect_remove(uint32_t exp_docid, const DoubleVector& exp_vector) const {
        expect_entry(exp_docid, exp_vector, _removes);
    }
    void expect_prepare_add(uint32_t exp_docid, const DoubleVector& exp_vector) const {
        expect_entry(exp_docid, exp_vector, _prepare_adds);
    }
    void expect_complete_add(uint32_t exp_docid, const DoubleVector& exp_vector) const {
        expect_entry(exp_docid, exp_vector, _complete_adds);
    }
    generation_t get_transfer_gen() const { return _transfer_gen; }
    generation_t get_trim_gen() const { return _trim_gen; }
    size_t memory_usage_cnt() const { return _memory_usage_cnt; }

    void add_document(uint32_t docid) override {
        auto vector = _vectors.get_vector(docid).typify<double>();
        _adds.emplace_back(docid, DoubleVector(vector.begin(), vector.end()));
    }
    std::unique_ptr<PrepareResult> prepare_add_document(uint32_t docid,
                                                        vespalib::eval::TypedCells vector,
                                                        vespalib::GenerationHandler::Guard guard) const override {
        (void) guard;
        auto d_vector = vector.typify<double>();
        _prepare_adds.emplace_back(docid, DoubleVector(d_vector.begin(), d_vector.end()));
        return std::make_unique<MockPrepareResult>(docid);
    }
    void complete_add_document(uint32_t docid,
                               std::unique_ptr<PrepareResult> prepare_result) override {
        auto* mock_result = dynamic_cast<MockPrepareResult*>(prepare_result.get());
        assert(mock_result);
        EXPECT_EQUAL(docid, mock_result->docid);
        auto vector = _vectors.get_vector(docid).typify<double>();
        _complete_adds.emplace_back(docid, DoubleVector(vector.begin(), vector.end()));
    }
    void remove_document(uint32_t docid) override {
        auto vector = _vectors.get_vector(docid).typify<double>();
        _removes.emplace_back(docid, DoubleVector(vector.begin(), vector.end()));
    }
    void transfer_hold_lists(generation_t current_gen) override {
        _transfer_gen = current_gen;
    }
    void trim_hold_lists(generation_t first_used_gen) override {
        _trim_gen = first_used_gen;
    }
    bool consider_compact(const CompactionStrategy&) override {
        return false;
    }
    vespalib::MemoryUsage update_stat(const CompactionStrategy&) override {
        ++_memory_usage_cnt;
        return vespalib::MemoryUsage();
    }
    vespalib::MemoryUsage memory_usage() const override {
        ++_memory_usage_cnt;
        return vespalib::MemoryUsage();
    }
    void populate_address_space_usage(AddressSpaceUsage&) const override {}
    void get_state(const vespalib::slime::Inserter&) const override {}
    void shrink_lid_space(uint32_t) override { }
    std::unique_ptr<NearestNeighborIndexSaver> make_saver() const override {
        if (_index_value != 0) {
            return std::make_unique<MockIndexSaver>(_index_value);
        }
        return std::unique_ptr<NearestNeighborIndexSaver>();
    }
    std::unique_ptr<NearestNeighborIndexLoader> make_loader(FastOS_FileInterface& file) override {
        return std::make_unique<MockIndexLoader>(_index_value, file);
    }
    std::vector<Neighbor> find_top_k(uint32_t k, vespalib::eval::TypedCells vector, uint32_t explore_k,
                                     double distance_threshold) const override
    {
        (void) k;
        (void) vector;
        (void) explore_k;
        (void) distance_threshold;
        return std::vector<Neighbor>();
    }
    std::vector<Neighbor> find_top_k_with_filter(uint32_t k, vespalib::eval::TypedCells vector,
                                                 const search::BitVector& filter, uint32_t explore_k,
                                                 double distance_threshold) const override
    {
        (void) k;
        (void) vector;
        (void) explore_k;
        (void) filter;
        (void) distance_threshold;
        return std::vector<Neighbor>();
    }

    
    const search::tensor::DistanceFunction *distance_function() const override {
        static search::tensor::SquaredEuclideanDistance my_dist_fun(vespalib::eval::CellType::DOUBLE);
        return &my_dist_fun;
    }
};

class MockNearestNeighborIndexFactory : public NearestNeighborIndexFactory {

    std::unique_ptr<NearestNeighborIndex> make(const DocVectorAccess& vectors,
                                               size_t vector_size,
                                               CellType cell_type,
                                               const search::attribute::HnswIndexParams& params) const override {
        (void) vector_size;
        (void) params;
        assert(cell_type == CellType::DOUBLE);
        return std::make_unique<MockNearestNeighborIndex>(vectors);
    }
};

const vespalib::string test_dir = "test_data/";
const vespalib::string attr_name = test_dir + "my_attr";

struct FixtureTraits {
    bool use_dense_tensor_attribute = false;
    bool use_direct_tensor_attribute = false;
    bool enable_hnsw_index = false;
    bool use_mock_index = false;
    bool use_mmap_file_allocator = false;

    FixtureTraits dense() && {
        use_dense_tensor_attribute = true;
        enable_hnsw_index = false;
        return *this;
    }

    FixtureTraits mmap_file_allocator() && {
        use_mmap_file_allocator = true;
        return *this;
    }

    FixtureTraits hnsw() && {
        use_dense_tensor_attribute = true;
        enable_hnsw_index = true;
        use_mock_index = false;
        return *this;
    }

    FixtureTraits mock_hnsw() && {
        use_dense_tensor_attribute = true;
        enable_hnsw_index = true;
        use_mock_index = true;
        return *this;
    }

    FixtureTraits direct() && {
        use_dense_tensor_attribute = false;
        use_direct_tensor_attribute = true;
        return *this;
    }

};

struct Fixture {
    using BasicType = search::attribute::BasicType;
    using CollectionType = search::attribute::CollectionType;
    using Config = search::attribute::Config;

    search::test::DirectoryHandler _dir_handler;
    Config _cfg;
    vespalib::string _name;
    vespalib::string _typeSpec;
    bool _use_mock_index;
    std::unique_ptr<NearestNeighborIndexFactory> _index_factory;
    std::shared_ptr<TensorAttribute> _tensorAttr;
    std::shared_ptr<AttributeVector> _attr;
    vespalib::ThreadStackExecutor _executor;
    bool _denseTensors;
    FixtureTraits _traits;

    Fixture(const vespalib::string &typeSpec,
            FixtureTraits traits = FixtureTraits())
        : _dir_handler(test_dir),
          _cfg(BasicType::TENSOR, CollectionType::SINGLE),
          _name(attr_name),
          _typeSpec(typeSpec),
          _index_factory(),
          _tensorAttr(),
          _attr(),
          _executor(1, 0x10000),
          _denseTensors(false),
          _traits(traits)
    {
        if (traits.enable_hnsw_index) {
            _cfg.set_distance_metric(DistanceMetric::Euclidean);
            _cfg.set_hnsw_index_params(HnswIndexParams(4, 20, DistanceMetric::Euclidean));
        }
        setup();
    }

    ~Fixture() {}

    void setup() {
        _cfg.setTensorType(ValueType::from_spec(_typeSpec));
        if (_cfg.tensorType().is_dense()) {
            _denseTensors = true;
        }
        if (_traits.use_mmap_file_allocator) {
            _cfg.setPaged(true);
        }
        if (_traits.use_mock_index) {
            _index_factory = std::make_unique<MockNearestNeighborIndexFactory>();
        } else {
            _index_factory = std::make_unique<DefaultNearestNeighborIndexFactory>();
        }
        _tensorAttr = makeAttr();
        _attr = _tensorAttr;
        _attr->addReservedDoc();
    }

    void set_hnsw_index_params(const HnswIndexParams &params) {
        _cfg.set_hnsw_index_params(params);
        setup();
    }

    void disable_hnsw_index() {
        _cfg.clear_hnsw_index_params();
        setup();
    }

    std::shared_ptr<TensorAttribute> makeAttr() {
        if (_traits.use_dense_tensor_attribute) {
            assert(_denseTensors);
            return std::make_shared<DenseTensorAttribute>(_name, _cfg, *_index_factory);
        } else if (_traits.use_direct_tensor_attribute) {
            return std::make_shared<DirectTensorAttribute>(_name, _cfg);
        } else {
            return std::make_shared<SerializedFastValueAttribute>(_name, _cfg);
        }
    }

    const DenseTensorAttribute& as_dense_tensor() const {
        auto result = dynamic_cast<const DenseTensorAttribute*>(_tensorAttr.get());
        assert(result != nullptr);
        return *result;
    }

    template <typename IndexType>
    IndexType& get_nearest_neighbor_index() {
        assert(as_dense_tensor().nearest_neighbor_index() != nullptr);
        auto index = dynamic_cast<const IndexType*>(as_dense_tensor().nearest_neighbor_index());
        assert(index != nullptr);
        return *const_cast<IndexType*>(index);
    }

    HnswIndex& hnsw_index() {
        return get_nearest_neighbor_index<HnswIndex>();
    }

    MockNearestNeighborIndex& mock_index() {
        return get_nearest_neighbor_index<MockNearestNeighborIndex>();
    }

    void ensureSpace(uint32_t docId) {
        while (_attr->getNumDocs() <= docId) {
            uint32_t newDocId = 0u;
            _attr->addDoc(newDocId);
        }
    }

    void clearTensor(uint32_t docId) {
        ensureSpace(docId);
        _tensorAttr->clearDoc(docId);
        _attr->commit();
    }

    void set_tensor(uint32_t docid, const TensorSpec &spec) {
        set_tensor_internal(docid, *createTensor(spec));
    }

    std::unique_ptr<PrepareResult> prepare_set_tensor(uint32_t docid, const TensorSpec& spec) const {
        return _tensorAttr->prepare_set_tensor(docid, *createTensor(spec));
    }

    void complete_set_tensor(uint32_t docid, const TensorSpec& spec, std::unique_ptr<PrepareResult> prepare_result) {
        ensureSpace(docid);
        _tensorAttr->complete_set_tensor(docid, *createTensor(spec), std::move(prepare_result));
        _attr->commit();
    }

    void set_empty_tensor(uint32_t docid) {
        set_tensor_internal(docid, *_tensorAttr->getEmptyTensor());
    }

    void set_tensor_internal(uint32_t docId, const Value &tensor) {
        ensureSpace(docId);
        _tensorAttr->setTensor(docId, tensor);
        _attr->commit();
    }

    generation_t get_current_gen() const {
        return _attr->getCurrentGeneration();
    }

    search::attribute::Status getStatus() {
        _attr->commit(true);
        return _attr->getStatus();
    }

    void assertGetNoTensor(uint32_t docId) {
        AttributeGuard guard(_attr);
        Value::UP actTensor = _tensorAttr->getTensor(docId);
        EXPECT_FALSE(actTensor);
    }

    void assertGetTensor(const TensorSpec &expSpec, uint32_t docId) {
        Value::UP expTensor = createTensor(expSpec);
        AttributeGuard guard(_attr);
        Value::UP actTensor = _tensorAttr->getTensor(docId);
        EXPECT_TRUE(static_cast<bool>(actTensor));
        EXPECT_EQUAL(*expTensor, *actTensor);
    }

    void save() {
        bool saveok = _attr->save();
        EXPECT_TRUE(saveok);
    }

    void load() {
        _tensorAttr = makeAttr();
        _attr = _tensorAttr;
        bool loadok = _attr->load();
        EXPECT_TRUE(loadok);
    }

    void loadWithExecutor() {
        _tensorAttr = makeAttr();
        _attr = _tensorAttr;
        bool loadok = _attr->load(&_executor);
        EXPECT_TRUE(loadok);
    }

    TensorSpec expDenseTensor3() const {
        return TensorSpec(denseSpec)
                .add({{"x", 0}, {"y", 1}}, 11)
                .add({{"x", 1}, {"y", 2}}, 0);
    }

    TensorSpec expDenseFillTensor() const {
        return TensorSpec(denseSpec)
                .add({{"x", 0}, {"y", 0}}, 5)
                .add({{"x", 1}, {"y", 2}}, 0);
    }

    TensorSpec expEmptyDenseTensor() const {
        return TensorSpec(denseSpec);
    }

    vespalib::string expEmptyDenseTensorSpec() const {
        return denseSpec;
    }

    vespalib::FileHeader get_file_header();
    void set_example_tensors();
    void assert_example_tensors();
    void save_example_tensors_with_mock_index();
    void testEmptyAttribute();
    void testSetTensorValue();
    void testSaveLoad();
    void testCompaction();
    void testTensorTypeFileHeaderTag();
    void testEmptyTensor();
    void testOnHoldAccounting();
    void test_populate_address_space_usage();
};


void
Fixture::set_example_tensors()
{
    set_tensor(1, vec_2d(3, 5));
    set_tensor(2, vec_2d(7, 9));
}

void
Fixture::assert_example_tensors()
{
    assertGetTensor(vec_2d(3, 5), 1);
    assertGetTensor(vec_2d(7, 9), 2);
}

void
Fixture::save_example_tensors_with_mock_index()
{
    set_example_tensors();
    mock_index().save_index_with_value(123);
    save();
    EXPECT_TRUE(vespalib::fileExists(_name + ".nnidx"));
}

void
Fixture::testEmptyAttribute()
{
    EXPECT_EQUAL(1u, _attr->getNumDocs());
    EXPECT_EQUAL(1u, _attr->getCommittedDocIdLimit());
}

void
Fixture::testSetTensorValue()
{
    ensureSpace(4);
    EXPECT_EQUAL(5u, _attr->getNumDocs());
    TEST_DO(assertGetNoTensor(4));
    EXPECT_EXCEPTION(set_tensor(4, TensorSpec("double")),
                     WrongTensorTypeException,
                     "but other tensor type is 'double'");
    TEST_DO(assertGetNoTensor(4));
    set_empty_tensor(4);
    if (_denseTensors) {
        TEST_DO(assertGetTensor(expEmptyDenseTensor(), 4));
        set_tensor(3, expDenseTensor3());
        TEST_DO(assertGetTensor(expDenseTensor3(), 3));
    } else {
        TEST_DO(assertGetTensor(TensorSpec(sparseSpec), 4));
        set_tensor(3, TensorSpec(sparseSpec)
                .add({{"x", ""}, {"y", ""}}, 11));
        TEST_DO(assertGetTensor(TensorSpec(sparseSpec)
                                        .add({{"x", ""}, {"y", ""}}, 11), 3));
    }
    TEST_DO(assertGetNoTensor(2));
    TEST_DO(clearTensor(3));
    TEST_DO(assertGetNoTensor(3));
}

void
Fixture::testSaveLoad()
{
    ensureSpace(4);
    set_empty_tensor(4);
    if (_denseTensors) {
        set_tensor(3, expDenseTensor3());
    } else {
        set_tensor(3, TensorSpec(sparseSpec)
                .add({{"x", ""}, {"y", "1"}}, 11));
    }
    TEST_DO(save());
    TEST_DO(load());
    EXPECT_EQUAL(5u, _attr->getNumDocs());
    EXPECT_EQUAL(5u, _attr->getCommittedDocIdLimit());
    if (_denseTensors) {
        TEST_DO(assertGetTensor(expDenseTensor3(), 3));
        TEST_DO(assertGetTensor(expEmptyDenseTensor(), 4));
    } else {
        TEST_DO(assertGetTensor(TensorSpec(sparseSpec)
                                        .add({{"x", ""}, {"y", "1"}}, 11), 3));
        TEST_DO(assertGetTensor(TensorSpec(sparseSpec), 4));
    }
    TEST_DO(assertGetNoTensor(2));
}

void
Fixture::testCompaction()
{
    ensureSpace(4);
    TensorSpec empty_xy_tensor(sparseSpec);
    TensorSpec simple_tensor = TensorSpec(sparseSpec)
            .add({{"x", ""}, {"y", "1"}}, 11);
    TensorSpec fill_tensor = TensorSpec(sparseSpec)
            .add({{"x", ""}, {"y", ""}}, 5);
    if (_denseTensors) {
        empty_xy_tensor = expEmptyDenseTensor();
        simple_tensor = expDenseTensor3();
        fill_tensor = expDenseFillTensor();
    }
    set_empty_tensor(4);
    set_tensor(3, simple_tensor);
    set_tensor(2, fill_tensor);
    clearTensor(2);
    set_tensor(2, fill_tensor);
    search::attribute::Status oldStatus = getStatus();
    search::attribute::Status newStatus = oldStatus;
    auto guard = _attr->makeReadGuard(false);
    uint64_t iter = 2049;
    uint64_t iterLimit = 100000;
    for (; iter < iterLimit; ++iter) {
        clearTensor(2);
        set_tensor(2, fill_tensor);
        if ((iter & (iter - 1)) == 0) {
            // Temporarily drop read guard when iter crosses a power of 2.
            guard.reset();
            _attr->commit(true);
            _attr->commit(true);
            guard = _attr->makeReadGuard(false);
        }
        newStatus = getStatus();
        if (newStatus.getUsed() < oldStatus.getUsed()) {
            break;
        }
        oldStatus = newStatus;
    }
    EXPECT_GREATER(iterLimit, iter);
    LOG(info,
        "iter = %" PRIu64 ", memory usage %" PRIu64 " -> %" PRIu64,
        iter, oldStatus.getUsed(), newStatus.getUsed());
    TEST_DO(assertGetNoTensor(1));
    TEST_DO(assertGetTensor(fill_tensor, 2));
    TEST_DO(assertGetTensor(simple_tensor, 3));
    TEST_DO(assertGetTensor(empty_xy_tensor, 4));
}

vespalib::FileHeader
Fixture::get_file_header()
{
    vespalib::FileHeader header;
    FastOS_File file;
    vespalib::string file_name = attr_name + ".dat";
    EXPECT_TRUE(file.OpenReadOnly(file_name.c_str()));
    (void) header.readFile(file);
    return header;
}

void
Fixture::testTensorTypeFileHeaderTag()
{
    ensureSpace(4);
    TEST_DO(save());

    auto header = get_file_header();
    EXPECT_TRUE(header.hasTag("tensortype"));
    EXPECT_EQUAL(_typeSpec, header.getTag("tensortype").asString());
    if (_traits.use_dense_tensor_attribute) {
        EXPECT_EQUAL(1u, header.getTag("version").asInteger());
    } else {
        EXPECT_EQUAL(0u, header.getTag("version").asInteger());
    }
}

void
Fixture::testEmptyTensor()
{
    const TensorAttribute &tensorAttr = *_tensorAttr;
    Value::UP emptyTensor = tensorAttr.getEmptyTensor();
    if (_denseTensors) {
        vespalib::string expSpec = expEmptyDenseTensorSpec();
        EXPECT_EQUAL(emptyTensor->type(), ValueType::from_spec(expSpec));
    } else {
        EXPECT_EQUAL(emptyTensor->type(), tensorAttr.getConfig().tensorType());
        EXPECT_EQUAL(emptyTensor->type(), ValueType::from_spec(_typeSpec));
    }
}

void
Fixture::testOnHoldAccounting()
{
    {
        AttributeGuard guard(_attr);
        EXPECT_EQUAL(0u, getStatus().getOnHold());
        set_empty_tensor(1);
        clearTensor(1);
        EXPECT_NOT_EQUAL(0u, getStatus().getOnHold());
    }
    EXPECT_EQUAL(0u, getStatus().getOnHold());
}

void
Fixture::test_populate_address_space_usage()
{
    search::AddressSpaceUsage usage = _attr->getAddressSpaceUsage();
    const auto& all = usage.get_all();
    if (_denseTensors) {
        EXPECT_EQUAL(1u, all.size());
        EXPECT_EQUAL(1u, all.count("tensor-store"));
    } else {
        EXPECT_EQUAL(2u, all.size());
        EXPECT_EQUAL(1u, all.count("tensor-store"));
        EXPECT_EQUAL(1u, all.count("shared-string-repo"));
    }
}

template <class MakeFixture>
void testAll(MakeFixture &&f)
{
    TEST_DO(f()->testEmptyAttribute());
    TEST_DO(f()->testSetTensorValue());
    TEST_DO(f()->testSaveLoad());
    TEST_DO(f()->testCompaction());
    TEST_DO(f()->testTensorTypeFileHeaderTag());
    TEST_DO(f()->testEmptyTensor());
    TEST_DO(f()->testOnHoldAccounting());
    TEST_DO(f()->test_populate_address_space_usage());
}

TEST("Test sparse tensors with generic tensor attribute")
{
    testAll([]() { return std::make_shared<Fixture>(sparseSpec); });
}

TEST("Test sparse tensors with direct tensor attribute")
{
    testAll([]() { return std::make_shared<Fixture>(sparseSpec, FixtureTraits().direct()); });
}

TEST("Test dense tensors with generic tensor attribute")
{
    testAll([]() { return std::make_shared<Fixture>(denseSpec); });
}

TEST("Test dense tensors with dense tensor attribute")
{
    testAll([]() { return std::make_shared<Fixture>(denseSpec, FixtureTraits().dense()); });
}

TEST_F("Hnsw index is NOT instantiated in dense tensor attribute by default",
       Fixture(vec_2d_spec, FixtureTraits().dense()))
{
    const auto& tensor = f.as_dense_tensor();
    EXPECT_TRUE(tensor.nearest_neighbor_index() == nullptr);
}

class DenseTensorAttributeHnswIndex : public Fixture {
public:
    DenseTensorAttributeHnswIndex() : Fixture(vec_2d_spec, FixtureTraits().hnsw()) {}
};

TEST_F("Hnsw index is instantiated in dense tensor attribute when specified in config", DenseTensorAttributeHnswIndex)
{
    auto& index = f.hnsw_index();

    const auto& cfg = index.config();
    EXPECT_EQUAL(8u, cfg.max_links_at_level_0());
    EXPECT_EQUAL(4u, cfg.max_links_on_inserts());
    EXPECT_EQUAL(20u, cfg.neighbors_to_explore_at_construction());
    EXPECT_TRUE(cfg.heuristic_select_neighbors());
}

void
expect_level_0(uint32_t exp_docid, const HnswNode& node)
{
    ASSERT_GREATER_EQUAL(node.size(), 1u);
    ASSERT_EQUAL(1u, node.level(0).size());
    EXPECT_EQUAL(exp_docid, node.level(0)[0]);
}

TEST_F("Hnsw index is integrated in dense tensor attribute and can be saved and loaded", DenseTensorAttributeHnswIndex)
{
    // Set two points that will be linked together in level 0 of the hnsw graph.
    f.set_tensor(1, vec_2d(3, 5));
    f.set_tensor(2, vec_2d(7, 9));

    auto &index_a = f.hnsw_index();
    expect_level_0(2, index_a.get_node(1));
    expect_level_0(1, index_a.get_node(2));
    f.save();
    EXPECT_TRUE(vespalib::fileExists(attr_name + ".nnidx"));

    f.load();
    auto &index_b = f.hnsw_index();
    EXPECT_NOT_EQUAL(&index_a, &index_b);
    expect_level_0(2, index_b.get_node(1));
    expect_level_0(1, index_b.get_node(2));
}

TEST_F("Populates address space usage", DenseTensorAttributeHnswIndex)
{
    search::AddressSpaceUsage usage = f._attr->getAddressSpaceUsage();
    const auto& all = usage.get_all();
    EXPECT_EQUAL(3u, all.size());
    EXPECT_EQUAL(1u, all.count("tensor-store"));
    EXPECT_EQUAL(1u, all.count("hnsw-node-store"));
    EXPECT_EQUAL(1u, all.count("hnsw-link-store"));
}


class DenseTensorAttributeMockIndex : public Fixture {
public:
    DenseTensorAttributeMockIndex() : Fixture(vec_2d_spec, FixtureTraits().mock_hnsw()) {}
};

TEST_F("setTensor() updates nearest neighbor index", DenseTensorAttributeMockIndex)
{
    auto& index = f.mock_index();

    f.set_tensor(1, vec_2d(3, 5));
    index.expect_add(1, {3, 5});
    index.expect_empty_remove();
    index.clear();

    // Replaces previous value.
    f.set_tensor(1, vec_2d(7, 9));
    index.expect_remove(1, {3, 5});
    index.expect_add(1, {7, 9});
}

TEST_F("nearest neighbor index can be updated in two phases", DenseTensorAttributeMockIndex)
{
    auto& index = f.mock_index();
    {
        auto vec_a = vec_2d(3, 5);
        auto prepare_result = f.prepare_set_tensor(1, vec_a);
        index.expect_prepare_add(1, {3, 5});
        f.complete_set_tensor(1, vec_a, std::move(prepare_result));
        f.assertGetTensor(vec_a, 1);
        index.expect_complete_add(1, {3, 5});
    }
    index.clear();
    {
        // Replaces previous value.
        auto vec_b = vec_2d(7, 9);
        auto prepare_result = f.prepare_set_tensor(1, vec_b);
        index.expect_prepare_add(1, {7, 9});
        f.complete_set_tensor(1, vec_b, std::move(prepare_result));
        index.expect_remove(1, {3, 5});
        f.assertGetTensor(vec_b, 1);
        index.expect_complete_add(1, {7, 9});
    }
}

TEST_F("clearDoc() updates nearest neighbor index", DenseTensorAttributeMockIndex)
{
    auto& index = f.mock_index();

    // Nothing to clear.
    f.clearTensor(1);
    index.expect_empty_remove();
    index.expect_empty_add();

    // Clears previous value.
    f.set_tensor(1, vec_2d(3, 5));
    index.clear();
    f.clearTensor(1);
    index.expect_remove(1, {3, 5});
    index.expect_empty_add();
}

TEST_F("commit() ensures transfer and trim hold lists on nearest neighbor index", DenseTensorAttributeMockIndex)
{
    auto& index = f.mock_index();
    TensorSpec spec = vec_2d(3, 5);

    f.set_tensor(1, spec);
    generation_t gen_1 = f.get_current_gen();
    EXPECT_EQUAL(gen_1 - 1, index.get_transfer_gen());
    EXPECT_EQUAL(gen_1, index.get_trim_gen());

    generation_t gen_2 = 0;
    {
        // Takes guard on gen_1
        auto guard = f._attr->makeReadGuard(false);
        f.set_tensor(2, spec);
        gen_2 = f.get_current_gen();
        EXPECT_GREATER(gen_2, gen_1);
        EXPECT_EQUAL(gen_2 - 1, index.get_transfer_gen());
        EXPECT_EQUAL(gen_1, index.get_trim_gen());
    }

    f.set_tensor(3, spec);
    generation_t gen_3 = f.get_current_gen();
    EXPECT_GREATER(gen_3, gen_2);
    EXPECT_EQUAL(gen_3 - 1, index.get_transfer_gen());
    EXPECT_EQUAL(gen_3, index.get_trim_gen());
}

TEST_F("Memory usage is extracted from index when updating stats on attribute", DenseTensorAttributeMockIndex)
{
    size_t before = f.mock_index().memory_usage_cnt();
    f.getStatus();
    size_t after = f.mock_index().memory_usage_cnt();
    EXPECT_EQUAL(before + 1, after);
}

TEST_F("Nearest neighbor index can be saved to disk and then loaded from file", DenseTensorAttributeMockIndex)
{
    f.save_example_tensors_with_mock_index();

    f.load(); // index is loaded from saved file
    auto& index = f.mock_index();
    EXPECT_EQUAL(123, index.get_index_value());
    index.expect_adds({});
}

TEST_F("onLoad() reconstructs nearest neighbor index if save file does not exists", DenseTensorAttributeMockIndex)
{
    f.set_example_tensors();
    f.save();
    EXPECT_FALSE(vespalib::fileExists(attr_name + ".nnidx"));

    f.load(); // index is reconstructed by adding all loaded tensors
    auto& index = f.mock_index();
    EXPECT_EQUAL(0, index.get_index_value());
    index.expect_adds({{1, {3, 5}}, {2, {7, 9}}});
}

TEST_F("onLoads() ignores saved nearest neighbor index if not enabled in config", DenseTensorAttributeMockIndex)
{
    f.save_example_tensors_with_mock_index();
    f.disable_hnsw_index();
    f.load();
    f.assert_example_tensors();
    EXPECT_EQUAL(f.as_dense_tensor().nearest_neighbor_index(), nullptr);
}

TEST_F("onLoad() uses executor if major index parameters are changed", DenseTensorAttributeMockIndex)
{
    f.save_example_tensors_with_mock_index();
    f.set_hnsw_index_params(HnswIndexParams(5, 20, DistanceMetric::Euclidean));
    EXPECT_EQUAL(0ul, f._executor.getStats().acceptedTasks);
    f.loadWithExecutor();
    EXPECT_EQUAL(2ul, f._executor.getStats().acceptedTasks);
    f.assert_example_tensors();
    auto& index = f.mock_index();
    EXPECT_EQUAL(0, index.get_index_value());
    index.expect_adds({});
    index.expect_prepare_adds({{1, {3, 5}}, {2, {7, 9}}});
    index.expect_complete_adds({{1, {3, 5}}, {2, {7, 9}}});
}

TEST_F("onLoad() ignores saved nearest neighbor index if major index parameters are changed", DenseTensorAttributeMockIndex)
{
    f.save_example_tensors_with_mock_index();
    f.set_hnsw_index_params(HnswIndexParams(5, 20, DistanceMetric::Euclidean));
    EXPECT_EQUAL(0ul, f._executor.getStats().acceptedTasks);
    f.load();
    EXPECT_EQUAL(0ul, f._executor.getStats().acceptedTasks);
    f.assert_example_tensors();
    auto& index = f.mock_index();
    EXPECT_EQUAL(0, index.get_index_value());
    index.expect_adds({{1, {3, 5}}, {2, {7, 9}}});
}

TEST_F("onLoad() uses saved nearest neighbor index if only minor index parameters are changed", DenseTensorAttributeMockIndex)
{
    f.save_example_tensors_with_mock_index();
    f.set_hnsw_index_params(HnswIndexParams(4, 21, DistanceMetric::Euclidean));
    f.load();
    f.assert_example_tensors();
    auto& index = f.mock_index();
    EXPECT_EQUAL(123, index.get_index_value());
    index.expect_adds({});
}

TEST_F("Nearest neighbor index type is added to attribute file header", DenseTensorAttributeMockIndex)
{
    f.save_example_tensors_with_mock_index();
    auto header = f.get_file_header();
    EXPECT_TRUE(header.hasTag("nearest_neighbor_index"));
    EXPECT_EQUAL("hnsw", header.getTag("nearest_neighbor_index").asString());
}

template <typename ParentT>
class NearestNeighborBlueprintFixtureBase : public ParentT {
public:
    NearestNeighborBlueprintFixtureBase() {
        this->set_tensor(1, vec_2d(1, 1));
        this->set_tensor(2, vec_2d(2, 2));
        this->set_tensor(3, vec_2d(3, 3));
        this->set_tensor(4, vec_2d(4, 4));
        this->set_tensor(5, vec_2d(5, 5));
        this->set_tensor(6, vec_2d(6, 6));
        this->set_tensor(7, vec_2d(7, 7));
        this->set_tensor(8, vec_2d(8, 8));
        this->set_tensor(9, vec_2d(9, 9));
        this->set_tensor(10, vec_2d(0, 0));
    }

    std::unique_ptr<Value> createDenseTensor(const TensorSpec &spec) {
        return SimpleValue::from_spec(spec);
    }

    std::unique_ptr<NearestNeighborBlueprint> make_blueprint(bool approximate = true, double brute_force_limit = 0.05) {
        search::queryeval::FieldSpec field("foo", 0, 0);
        auto bp = std::make_unique<NearestNeighborBlueprint>(
            field,
            this->as_dense_tensor(),
            createDenseTensor(vec_2d(17, 42)),
            3, approximate, 5,
            100100.25,
            brute_force_limit);
        EXPECT_EQUAL(11u, bp->getState().estimate().estHits);
        EXPECT_EQUAL(approximate, bp->may_approximate());
        EXPECT_EQUAL(100100.25 * 100100.25, bp->get_distance_threshold());
        return bp;
    }
};

class DenseTensorAttributeWithoutIndex : public Fixture {
public:
    DenseTensorAttributeWithoutIndex() : Fixture(vec_2d_spec, FixtureTraits().dense()) {}
};

using NearestNeighborBlueprintFixture = NearestNeighborBlueprintFixtureBase<DenseTensorAttributeMockIndex>;
using NearestNeighborBlueprintWithoutIndexFixture = NearestNeighborBlueprintFixtureBase<DenseTensorAttributeWithoutIndex>;

TEST_F("NN blueprint handles empty filter", NearestNeighborBlueprintFixture)
{
    auto bp = f.make_blueprint();
    auto empty_filter = GlobalFilter::create();
    bp->set_global_filter(*empty_filter);
    EXPECT_EQUAL(3u, bp->getState().estimate().estHits);
    EXPECT_TRUE(bp->may_approximate());
}

TEST_F("NN blueprint handles strong filter", NearestNeighborBlueprintFixture)
{
    auto bp = f.make_blueprint();
    auto filter = search::BitVector::create(11);
    filter->setBit(3);
    filter->invalidateCachedCount();
    auto strong_filter = GlobalFilter::create(std::move(filter));
    bp->set_global_filter(*strong_filter);
    EXPECT_EQUAL(1u, bp->getState().estimate().estHits);
    EXPECT_TRUE(bp->may_approximate());
}

TEST_F("NN blueprint handles weak filter", NearestNeighborBlueprintFixture)
{
    auto bp = f.make_blueprint();
    auto filter = search::BitVector::create(11);
    filter->setBit(1);
    filter->setBit(3);
    filter->setBit(5);
    filter->setBit(7);
    filter->setBit(9);
    filter->setBit(11);
    filter->invalidateCachedCount();
    auto weak_filter = GlobalFilter::create(std::move(filter));
    bp->set_global_filter(*weak_filter);
    EXPECT_EQUAL(3u, bp->getState().estimate().estHits);
    EXPECT_TRUE(bp->may_approximate());
}

TEST_F("NN blueprint handles strong filter triggering brute force search", NearestNeighborBlueprintFixture)
{
    auto bp = f.make_blueprint(true, 0.2);
    auto filter = search::BitVector::create(11);
    filter->setBit(3);
    filter->invalidateCachedCount();
    auto strong_filter = GlobalFilter::create(std::move(filter));
    bp->set_global_filter(*strong_filter);
    EXPECT_EQUAL(11u, bp->getState().estimate().estHits);
    EXPECT_FALSE(bp->may_approximate());
}

TEST_F("NN blueprint wants global filter when having index", NearestNeighborBlueprintFixture)
{
    auto bp = f.make_blueprint();
    EXPECT_TRUE(bp->getState().want_global_filter());
}

TEST_F("NN blueprint do NOT want global filter when explicitly using brute force", NearestNeighborBlueprintFixture)
{
    auto bp = f.make_blueprint(false);
    EXPECT_FALSE(bp->getState().want_global_filter());
}

TEST_F("NN blueprint do NOT want global filter when NOT having index (implicit brute force)", NearestNeighborBlueprintWithoutIndexFixture)
{
    auto bp = f.make_blueprint();
    EXPECT_FALSE(bp->getState().want_global_filter());
}

TEST("Dense tensor attribute with paged flag uses mmap file allocator")
{
    vespalib::string basedir("mmap-file-allocator-factory-dir");
    vespalib::alloc::MmapFileAllocatorFactory::instance().setup(basedir);
    {
        Fixture f(vec_2d_spec, FixtureTraits().dense().mmap_file_allocator());
        vespalib::string allocator_dir(basedir + "/0.my_attr");
        EXPECT_TRUE(vespalib::isDirectory(allocator_dir));
    }
    vespalib::alloc::MmapFileAllocatorFactory::instance().setup("");
    vespalib::rmdir(basedir, true);
}

TEST_MAIN() { TEST_RUN_ALL(); }
