// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "shared_threading_service.h"
#include <vespa/vespalib/util/blockingthreadstackexecutor.h>
#include <vespa/vespalib/util/cpu_usage.h>
#include <vespa/vespalib/util/isequencedtaskexecutor.h>
#include <vespa/vespalib/util/sequencedtaskexecutor.h>
#include <vespa/vespalib/util/size_literals.h>

using vespalib::CpuUsage;

VESPA_THREAD_STACK_TAG(proton_field_writer_executor)
VESPA_THREAD_STACK_TAG(proton_shared_executor)
VESPA_THREAD_STACK_TAG(proton_warmup_executor)

namespace proton {

using SharedFieldWriterExecutor = ThreadingServiceConfig::ProtonConfig::Feeding::SharedFieldWriterExecutor;

SharedThreadingService::SharedThreadingService(const SharedThreadingServiceConfig& cfg)
    : _warmup(cfg.warmup_threads(), 128_Ki, CpuUsage::wrap(proton_warmup_executor, CpuUsage::Category::COMPACT)),
      _shared(std::make_shared<vespalib::BlockingThreadStackExecutor>(cfg.shared_threads(), 128_Ki,
                                                                      cfg.shared_task_limit(), proton_shared_executor)),
      _field_writer(),
      _invokeService(cfg.field_writer_config().reactionTime()),
      _invokeRegistrations()
{
    const auto& fw_cfg = cfg.field_writer_config();
    if (fw_cfg.shared_field_writer() == SharedFieldWriterExecutor::DOCUMENT_DB) {
        _field_writer = vespalib::SequencedTaskExecutor::create(CpuUsage::wrap(proton_field_writer_executor, CpuUsage::Category::WRITE),
                                                                fw_cfg.indexingThreads() * 3,
                                                                fw_cfg.defaultTaskLimit(),
                                                                fw_cfg.is_task_limit_hard(),
                                                                fw_cfg.optimize(),
                                                                fw_cfg.kindOfwatermark());
        if (fw_cfg.optimize() == vespalib::Executor::OptimizeFor::THROUGHPUT) {
            _invokeRegistrations.push_back(_invokeService.registerInvoke([executor = _field_writer.get()]() {
                executor->wakeup();
            }));
        }
    }
}

SharedThreadingService::~SharedThreadingService() = default;

void
SharedThreadingService::sync_all_executors() {
    _warmup.sync();
    _shared->sync();
    if (_field_writer) {
        _field_writer->sync_all();
    }
}

}
