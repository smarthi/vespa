// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "singlenumericpostattribute.h"
#include "enumstore.h"
#include "enumcomparator.h"
#include "singlenumericenumattribute.hpp"

namespace search {

template <typename B>
SingleValueNumericPostingAttribute<B>::~SingleValueNumericPostingAttribute()
{
    this->disableFreeLists();
    this->disableElemHoldList();
    clearAllPostings();
}

template <typename B>
SingleValueNumericPostingAttribute<B>::SingleValueNumericPostingAttribute(const vespalib::string & name,
                                                                          const AttributeVector::Config & c) :
    SingleValueNumericEnumAttribute<B>(name, c),
    PostingParent(*this, this->getEnumStore())
{
}

template <typename B>
void
SingleValueNumericPostingAttribute<B>::freezeEnumDictionary()
{
    this->getEnumStore().freeze_dictionary();
}

template <typename B>
void
SingleValueNumericPostingAttribute<B>::mergeMemoryStats(vespalib::MemoryUsage & total)
{
    auto& compaction_strategy = this->getConfig().getCompactionStrategy();
    total.merge(this->_postingList.update_stat(compaction_strategy));
}

template <typename B>
void
SingleValueNumericPostingAttribute<B>::applyUpdateValueChange(const Change & c,
                                                              EnumStore & enumStore,
                                                              std::map<DocId, EnumIndex> & currEnumIndices)
{
    EnumIndex newIdx;
    if (c.has_entry_ref()) {
        newIdx = EnumIndex(vespalib::datastore::EntryRef(c.get_entry_ref()));
    } else {
        enumStore.find_index(c._data.raw(), newIdx);
    }
    currEnumIndices[c._doc] = newIdx;
}

template <typename B>
void
SingleValueNumericPostingAttribute<B>::
makePostingChange(const vespalib::datastore::EntryComparator &cmpa,
                  const std::map<DocId, EnumIndex> &currEnumIndices,
                  PostingMap &changePost)
{
    for (const auto& elem : currEnumIndices) {
        uint32_t docId = elem.first;
        EnumIndex oldIdx = this->_enumIndices[docId];
        EnumIndex newIdx = elem.second;

        // add new posting
        changePost[EnumPostingPair(newIdx, &cmpa)].add(docId, 1);

        // remove old posting
        if ( oldIdx.valid()) {
            changePost[EnumPostingPair(oldIdx, &cmpa)].remove(docId);
        }
    }
}


template <typename B>
void
SingleValueNumericPostingAttribute<B>::applyValueChanges(EnumStoreBatchUpdater& updater)
{
    EnumStore & enumStore = this->getEnumStore();
    IEnumStoreDictionary& dictionary = enumStore.get_dictionary();
    PostingMap changePost;

    // used to make sure several arithmetic operations on the same document in a single commit works
    std::map<DocId, EnumIndex> currEnumIndices;

    // This avoids searching for the defaultValue in the enum store for each CLEARDOC in the change vector.
    this->cache_change_data_entry_ref(this->_defaultValue);
    for (const auto& change : this->_changes.getInsertOrder()) {
        auto enumIter = currEnumIndices.find(change._doc);
        EnumIndex oldIdx;
        if (enumIter != currEnumIndices.end()) {
            oldIdx = enumIter->second;
        } else {
            oldIdx = this->_enumIndices[change._doc];
        }

        if (change._type == ChangeBase::UPDATE) {
            applyUpdateValueChange(change, enumStore, currEnumIndices);
        } else if (change._type >= ChangeBase::ADD && change._type <= ChangeBase::DIV) {
            if (oldIdx.valid()) {
                T oldValue = enumStore.get_value(oldIdx);
                T newValue = this->template applyArithmetic<T, typename Change::DataType>(oldValue, change._data.getArithOperand(), change._type);
                EnumIndex newIdx;
                (void) dictionary.find_index(enumStore.make_comparator(newValue), newIdx);
                currEnumIndices[change._doc] = newIdx;
            }
        } else if(change._type == ChangeBase::CLEARDOC) {
            Change clearDoc(this->_defaultValue);
            clearDoc._doc = change._doc;
            applyUpdateValueChange(clearDoc, enumStore, currEnumIndices);
        }
    }
    // We must clear the cached entry ref as the defaultValue might be located in another data buffer on later invocations.
    this->_defaultValue.clear_entry_ref();

    makePostingChange(enumStore.get_comparator(), currEnumIndices, changePost);

    this->updatePostings(changePost);
    SingleValueNumericEnumAttribute<B>::applyValueChanges(updater);
}

template <typename B>
void
SingleValueNumericPostingAttribute<B>::removeOldGenerations(generation_t firstUsed)
{
    SingleValueNumericEnumAttribute<B>::removeOldGenerations(firstUsed);
    _postingList.trimHoldLists(firstUsed);
}

template <typename B>
void
SingleValueNumericPostingAttribute<B>::onGenerationChange(generation_t generation)
{
    _postingList.freeze();
    SingleValueNumericEnumAttribute<B>::onGenerationChange(generation);
    _postingList.transferHoldLists(generation - 1);
}

template <typename B>
AttributeVector::SearchContext::UP
SingleValueNumericPostingAttribute<B>::getSearch(QueryTermSimple::UP qTerm,
                                                 const attribute::SearchContextParams & params) const
{
    return std::make_unique<SinglePostingSearchContext>(std::move(qTerm), params, *this);
}

} // namespace search

