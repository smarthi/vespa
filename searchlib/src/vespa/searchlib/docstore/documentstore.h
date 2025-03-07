// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "idocumentstore.h"
#include <vespa/vespalib/util/compressionconfig.h>

namespace search::docstore {
    class VisitCache;
    class BackingStore;
    class Cache;
}

namespace search {

/**
 * Simple document store that contains serialized Document instances.
 * updates will be held in memory until flush() is called.
 * Uses a Local ID as key.
 **/
class DocumentStore : public IDocumentStore
{
public:
    class Config {
    public:
        enum UpdateStrategy {INVALIDATE, UPDATE };
        using CompressionConfig = vespalib::compression::CompressionConfig;
        Config() :
            _compression(CompressionConfig::LZ4, 9, 70),
            _maxCacheBytes(1000000000),
            _initialCacheEntries(0),
            _updateStrategy(INVALIDATE),
            _allowVisitCaching(false)
        { }
        Config(const CompressionConfig & compression, size_t maxCacheBytes, size_t initialCacheEntries) :
            _compression((maxCacheBytes != 0) ? compression : CompressionConfig::NONE),
            _maxCacheBytes(maxCacheBytes),
            _initialCacheEntries(initialCacheEntries),
            _updateStrategy(INVALIDATE),
            _allowVisitCaching(false)
        { }
        const CompressionConfig & getCompression() const { return _compression; }
        size_t getMaxCacheBytes()   const { return _maxCacheBytes; }
        size_t getInitialCacheEntries() const { return _initialCacheEntries; }
        bool allowVisitCaching() const { return _allowVisitCaching; }
        Config & allowVisitCaching(bool allow) { _allowVisitCaching = allow; return *this; }
        Config & updateStrategy(UpdateStrategy strategy) { _updateStrategy = strategy; return *this; }
        UpdateStrategy updateStrategy() const { return _updateStrategy; }
        bool operator == (const Config &) const;
    private:
        CompressionConfig _compression;
        size_t _maxCacheBytes;
        size_t _initialCacheEntries;
        UpdateStrategy _updateStrategy;
        bool   _allowVisitCaching;
    };

    /**
     * Construct a document store.
     * If the "simpledocstore.dat" data file exists, reads meta-data (offsets) into memory.
     *
     * @throws vespalib::IoException if the file is corrupt or other IO problems occur.
     * @param baseDir  The path to a directory where "simpledocstore.dat" will exist.
     **/
    DocumentStore(const Config & config, IDataStore & store);
    ~DocumentStore() override;

    DocumentUP read(DocumentIdT lid, const document::DocumentTypeRepo &repo) const override;
    void visit(const LidVector & lids, const document::DocumentTypeRepo &repo, IDocumentVisitor & visitor) const override;
    void write(uint64_t synkToken, DocumentIdT lid, const document::Document& doc) override;
    void write(uint64_t synkToken, DocumentIdT lid, const vespalib::nbostream & os) override;
    void remove(uint64_t syncToken, DocumentIdT lid) override;
    void flush(uint64_t syncToken) override;
    uint64_t initFlush(uint64_t synctoken) override;
    void compactBloat(uint64_t syncToken) override;
    void compactSpread(uint64_t syncToken) override;
    uint64_t lastSyncToken() const override;
    uint64_t tentativeLastSyncToken() const override;
    vespalib::system_time getLastFlushTime() const override;
    uint32_t getDocIdLimit() const override { return _backingStore.getDocIdLimit(); }
    size_t        memoryUsed() const override { return _backingStore.memoryUsed(); }
    size_t  getDiskFootprint() const override { return _backingStore.getDiskFootprint(); }
    size_t      getDiskBloat() const override { return _backingStore.getDiskBloat(); }
    size_t getMaxSpreadAsBloat() const override { return _backingStore.getMaxSpreadAsBloat(); }
    CacheStats getCacheStats() const override;
    size_t memoryMeta() const override { return _backingStore.memoryMeta(); }
    const vespalib::string & getBaseDir() const override { return _backingStore.getBaseDir(); }
    void accept(IDocumentStoreReadVisitor &visitor, IDocumentStoreVisitorProgress &visitorProgress,
                const document::DocumentTypeRepo &repo) override;
    void accept(IDocumentStoreRewriteVisitor &visitor, IDocumentStoreVisitorProgress &visitorProgress,
                const document::DocumentTypeRepo &repo) override;
    double getVisitCost() const override;
    DataStoreStorageStats getStorageStats() const override;
    vespalib::MemoryUsage getMemoryUsage() const override;
    std::vector<DataStoreFileChunkStats> getFileChunkStats() const override;

    /**
     * Implements common::ICompactableLidSpace
     */
    void compactLidSpace(uint32_t wantedDocLidLimit) override;
    bool canShrinkLidSpace() const override;
    size_t getEstimatedShrinkLidSpaceGain() const override;
    void shrinkLidSpace() override;
    void reconfigure(const Config & config);

private:
    bool useCache() const;

    template <class> class WrapVisitor;
    class WrapVisitorProgress;
    Config                                   _config;
    IDataStore &                             _backingStore;
    std::unique_ptr<docstore::BackingStore>  _store;
    std::unique_ptr<docstore::Cache>         _cache;
    std::unique_ptr<docstore::VisitCache>    _visitCache;
    mutable std::atomic<uint64_t>            _uncached_lookups;
};

} // namespace search
