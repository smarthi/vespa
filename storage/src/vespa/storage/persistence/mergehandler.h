// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
/**
 * @class storage::MergeHandler
 *
 * @brief Handles a merge of a single bucket.
 *
 * A merge is a complex operation in many stages covering multiple nodes. It
 * needs to track some state of ongoing merges, and it also needs quite a bit
 * of logic.
 *
 * This class implements tracks the state and implements the logic, such that
 * the rest of the provider layer does not need to concern itself with merges.
 */
#pragma once

#include "types.h"
#include "merge_bucket_info_syncer.h"
#include <vespa/persistence/spi/bucket.h>
#include <vespa/storageapi/message/bucket.h>
#include <vespa/storage/common/cluster_context.h>
#include <vespa/storage/common/messagesender.h>
#include <vespa/vespalib/util/monitored_refcount.h>
#include <atomic>

namespace vespalib {
class ISequencedTaskExecutor;
class SharedOperationThrottler;
}

namespace storage {

namespace spi {
    struct PersistenceProvider;
    class Context;
    class DocEntry;
}
class PersistenceUtil;
class ApplyBucketDiffState;
class MergeStatus;

class MergeHandler : public Types,
                     public MergeBucketInfoSyncer {

public:
    enum StateFlag {
        IN_USE                     = 0x01,
        DELETED                    = 0x02,
        DELETED_IN_PLACE           = 0x04
    };

    MergeHandler(PersistenceUtil& env, spi::PersistenceProvider& spi,
                 const ClusterContext& cluster_context, const framework::Clock & clock,
                 vespalib::ISequencedTaskExecutor& executor,
                 uint32_t maxChunkSize = 4190208,
                 uint32_t commonMergeChainOptimalizationMinimumSize = 64);

    ~MergeHandler() override;

    bool buildBucketInfoList(
            const spi::Bucket& bucket,
            Timestamp maxTimestamp,
            uint8_t myNodeIndex,
            std::vector<api::GetBucketDiffCommand::Entry>& output,
            spi::Context& context) const;
    void fetchLocalData(const spi::Bucket& bucket,
                        std::vector<api::ApplyBucketDiffCommand::Entry>& diff,
                        uint8_t nodeIndex,
                        spi::Context& context) const;
    void applyDiffLocally(const spi::Bucket& bucket,
                          std::vector<api::ApplyBucketDiffCommand::Entry>& diff,
                          uint8_t nodeIndex,
                          spi::Context& context,
                          std::shared_ptr<ApplyBucketDiffState> async_results) const;
    void sync_bucket_info(const spi::Bucket& bucket) const override;
    void schedule_delayed_delete(std::unique_ptr<ApplyBucketDiffState>) const override;

    MessageTrackerUP handleMergeBucket(api::MergeBucketCommand&, MessageTrackerUP) const;
    MessageTrackerUP handleGetBucketDiff(api::GetBucketDiffCommand&, MessageTrackerUP) const;
    void handleGetBucketDiffReply(api::GetBucketDiffReply&, MessageSender&) const;
    MessageTrackerUP handleApplyBucketDiff(api::ApplyBucketDiffCommand&, MessageTrackerUP) const;
    void handleApplyBucketDiffReply(api::ApplyBucketDiffReply&, MessageSender&, MessageTrackerUP) const;
    void drain_async_writes();

private:
    using DocEntryList = std::vector<std::unique_ptr<spi::DocEntry>>;
    const framework::Clock   &_clock;
    const ClusterContext &_cluster_context;
    PersistenceUtil          &_env;
    spi::PersistenceProvider &_spi;
    vespalib::SharedOperationThrottler& _operation_throttler;
    std::unique_ptr<vespalib::MonitoredRefCount> _monitored_ref_count;
    const uint32_t            _maxChunkSize;
    const uint32_t            _commonMergeChainOptimalizationMinimumSize;
    vespalib::ISequencedTaskExecutor& _executor;

    MessageTrackerUP handleGetBucketDiffStage2(api::GetBucketDiffCommand&, MessageTrackerUP) const;
    /** Returns a reply if merge is complete */
    api::StorageReply::SP processBucketMerge(const spi::Bucket& bucket,
                                             MergeStatus& status,
                                             MessageSender& sender,
                                             spi::Context& context,
                                             std::shared_ptr<ApplyBucketDiffState>& async_results) const;

    /**
     * Invoke either put, remove or unrevertable remove on the SPI
     * depending on the flags in the diff entry.
     */
    void applyDiffEntry(std::shared_ptr<ApplyBucketDiffState> async_results,
                        const spi::Bucket&,
                        const api::ApplyBucketDiffCommand::Entry&,
                        spi::Context& context,
                        const document::DocumentTypeRepo& repo) const;

    /**
     * Fill entries-vector with metadata for bucket up to maxTimestamp,
     * sorted ascendingly on entry timestamp.
     * Throws std::runtime_error upon iteration failure.
     */
    void populateMetaData(const spi::Bucket&,
                          Timestamp maxTimestamp,
                          DocEntryList & entries,
                          spi::Context& context) const;

    Document::UP deserializeDiffDocument(
            const api::ApplyBucketDiffCommand::Entry& e,
            const document::DocumentTypeRepo& repo) const;
};

} // storage

