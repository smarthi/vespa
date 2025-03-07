// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "multinumericpostattribute.h"
#include <charconv>

namespace search {

template <typename B, typename M>
void
MultiValueNumericPostingAttribute<B, M>::freezeEnumDictionary()
{
    this->getEnumStore().freeze_dictionary();
}

template <typename B, typename M>
void
MultiValueNumericPostingAttribute<B, M>::mergeMemoryStats(vespalib::MemoryUsage & total)
{
    auto& compaction_strategy = this->getConfig().getCompactionStrategy();
    total.merge(this->getPostingList().update_stat(compaction_strategy));
}

template <typename B, typename M>
void
MultiValueNumericPostingAttribute<B, M>::applyValueChanges(const DocIndices& docIndices,
                                                           EnumStoreBatchUpdater& updater)
{
    using PostingChangeComputer = PostingChangeComputerT<WeightedIndex, PostingMap>;

    EnumIndexMapper mapper;
    PostingMap changePost(PostingChangeComputer::compute(this->getMultiValueMapping(), docIndices,
                                                         this->getEnumStore().get_comparator(), mapper));
    this->updatePostings(changePost);
    MultiValueNumericEnumAttribute<B, M>::applyValueChanges(docIndices, updater);
}

template <typename B, typename M>
MultiValueNumericPostingAttribute<B, M>::MultiValueNumericPostingAttribute(const vespalib::string & name,
                                                                           const AttributeVector::Config & cfg)
    : MultiValueNumericEnumAttribute<B, M>(name, cfg),
      PostingParent(*this, this->getEnumStore()),
      _document_weight_attribute_adapter(*this)
{
}

template <typename B, typename M>
MultiValueNumericPostingAttribute<B, M>::~MultiValueNumericPostingAttribute()
{
    this->disableFreeLists();
    this->disableElemHoldList();
    clearAllPostings();
}

template <typename B, typename M>
void
MultiValueNumericPostingAttribute<B, M>::removeOldGenerations(generation_t firstUsed)
{
    MultiValueNumericEnumAttribute<B, M>::removeOldGenerations(firstUsed);
    _postingList.trimHoldLists(firstUsed);
}

template <typename B, typename M>
void
MultiValueNumericPostingAttribute<B, M>::onGenerationChange(generation_t generation)
{
    _postingList.freeze();
    MultiValueNumericEnumAttribute<B, M>::onGenerationChange(generation);
    _postingList.transferHoldLists(generation - 1);
}

template <typename B, typename M>
AttributeVector::SearchContext::UP
MultiValueNumericPostingAttribute<B, M>::getSearch(QueryTermSimpleUP qTerm,
                                                   const attribute::SearchContextParams & params) const
{
    std::unique_ptr<search::AttributeVector::SearchContext> sc;
    sc.reset(new typename std::conditional<M::_hasWeight,
                                           SetPostingSearchContext,
                                           ArrayPostingSearchContext>::
             type(std::move(qTerm), params, *this));
    return sc;
}

template <typename B, typename M>
vespalib::datastore::EntryRef
MultiValueNumericPostingAttribute<B, M>::DocumentWeightAttributeAdapter::get_dictionary_snapshot() const
{
    const IEnumStoreDictionary& dictionary = self._enumStore.get_dictionary();
    return dictionary.get_frozen_root();
}

template <typename B, typename M>
IDocumentWeightAttribute::LookupResult
MultiValueNumericPostingAttribute<B, M>::DocumentWeightAttributeAdapter::lookup(const LookupKey & key, vespalib::datastore::EntryRef dictionary_snapshot) const
{
    const IEnumStoreDictionary& dictionary = self._enumStore.get_dictionary();
    int64_t int_term;
    if ( !key.asInteger(int_term)) {
        return LookupResult();
    }
    auto comp = self._enumStore.make_comparator(int_term);
    auto find_result = dictionary.find_posting_list(comp, dictionary_snapshot);
    if (find_result.first.valid()) {
        auto pidx = find_result.second;
        if (pidx.valid()) {
            const PostingList &plist = self.getPostingList();
            auto minmax = plist.getAggregated(pidx);
            return LookupResult(pidx, plist.frozenSize(pidx), minmax.getMin(), minmax.getMax(), find_result.first);
        }
    }
    return LookupResult();
}

template <typename B, typename M>
void
MultiValueNumericPostingAttribute<B, M>::DocumentWeightAttributeAdapter::collect_folded(vespalib::datastore::EntryRef enum_idx, vespalib::datastore::EntryRef dictionary_snapshot, const std::function<void(vespalib::datastore::EntryRef)>& callback)const
{
    (void) dictionary_snapshot;
    callback(enum_idx);
}

template <typename B, typename M>
void
MultiValueNumericPostingAttribute<B, M>::DocumentWeightAttributeAdapter::create(vespalib::datastore::EntryRef idx, std::vector<DocumentWeightIterator> &dst) const
{
    assert(idx.valid());
    self.getPostingList().beginFrozen(idx, dst);
}

template <typename B, typename M>
DocumentWeightIterator
MultiValueNumericPostingAttribute<B, M>::DocumentWeightAttributeAdapter::create(vespalib::datastore::EntryRef idx) const
{
    assert(idx.valid());
    return self.getPostingList().beginFrozen(idx);
}

template <typename B, typename M>
const IDocumentWeightAttribute *
MultiValueNumericPostingAttribute<B, M>::asDocumentWeightAttribute() const
{
    if (this->hasWeightedSetType() &&
        this->getBasicType() == AttributeVector::BasicType::INT64 &&
        !this->getConfig().getIsFilter()) {
        return &_document_weight_attribute_adapter;
    }
    return nullptr;
}

} // namespace search

