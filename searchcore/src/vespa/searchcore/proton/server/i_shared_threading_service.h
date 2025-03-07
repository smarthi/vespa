// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

namespace vespalib {
class ISequencedTaskExecutor;
class ThreadExecutor;
class InvokeService;
}

namespace proton {

/**
 * Interface containing the thread executors that are shared across all document dbs.
 */
class ISharedThreadingService {
public:
    virtual ~ISharedThreadingService() {}

    /**
     * Returns the executor used for warmup (e.g. index warmup).
     */
    virtual vespalib::ThreadExecutor& warmup() = 0;

    /**
     * Returns the shared executor used for various assisting tasks in a document db.
     *
     * Example usages include:
     *   - Disk index fusion.
     *   - Updating nearest neighbor index (in DenseTensorAttribute).
     *   - Loading nearest neighbor index (in DenseTensorAttribute).
     *   - Writing of data in the document store.
     */
    virtual vespalib::ThreadExecutor& shared() = 0;

    /**
     * Returns the sequenced executor used to write index and attribute fields in a document db.
     *
     * This is a nullptr if the field writer is not shared across all document dbs.
     * TODO: Make this a reference when it is always shared.
     */
    virtual vespalib::ISequencedTaskExecutor* field_writer() = 0;

    /**
     * Returns an InvokeService intended for regular wakeup calls.
     */
    virtual vespalib::InvokeService & invokeService() = 0;
};

}

