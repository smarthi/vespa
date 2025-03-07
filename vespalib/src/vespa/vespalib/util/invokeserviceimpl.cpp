// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "invokeserviceimpl.h"
#include <cassert>

namespace vespalib {

InvokeServiceImpl::InvokeServiceImpl(duration napTime)
    : _naptime(napTime),
      _lock(),
      _currId(0),
      _closed(false),
      _toInvoke(),
      _thread()
{
}

InvokeServiceImpl::~InvokeServiceImpl()
{
    {
        std::lock_guard guard(_lock);
        assert(_toInvoke.empty());
        _closed = true;
    }
    if (_thread) {
        _thread->join();
    }
}

class InvokeServiceImpl::Registration : public IDestructorCallback {
public:
    Registration(InvokeServiceImpl * service, uint64_t id) noexcept
        : _service(service),
          _id(id)
    { }
    Registration(const Registration &) = delete;
    Registration & operator=(const Registration &) = delete;
    ~Registration() override{
        _service->unregister(_id);
    }
private:
    InvokeServiceImpl * _service;
    uint64_t        _id;
};

std::unique_ptr<IDestructorCallback>
InvokeServiceImpl::registerInvoke(VoidFunc func) {
    std::lock_guard guard(_lock);
    uint64_t id = _currId++;
    _toInvoke.emplace_back(id, std::move(func));
    if ( ! _thread) {
        _thread = std::make_unique<std::thread>([this]() { runLoop(); });
    }
    return std::make_unique<Registration>(this, id);
}

void
InvokeServiceImpl::unregister(uint64_t id) {
    std::lock_guard guard(_lock);
    auto found = std::find_if(_toInvoke.begin(), _toInvoke.end(), [id](const std::pair<uint64_t, VoidFunc> & a) {
        return id == a.first;
    });
    assert (found != _toInvoke.end());
    _toInvoke.erase(found);
}

void
InvokeServiceImpl::runLoop() {
    bool done = false;
    while ( ! done ) {
        {
            std::lock_guard guard(_lock);
            for (auto & func: _toInvoke) {
                func.second();
            }
            done = _closed;
        }
        if ( ! done) {
            std::this_thread::sleep_for(_naptime);
        }
    }

}

}

