// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "configsubscriber.h"
#include <vespa/config/common/configcontext.h>

namespace config {


ConfigSubscriber::ConfigSubscriber(std::shared_ptr<IConfigContext> context)
    : _set(std::move(context))

{ }

ConfigSubscriber::ConfigSubscriber(const SourceSpec & spec)
    : _set(std::make_shared<ConfigContext>(spec))
{ }

ConfigSubscriber::~ConfigSubscriber() = default;

bool
ConfigSubscriber::nextConfig(milliseconds timeoutInMillis)
{
    return _set.acquireSnapshot(timeoutInMillis, false);
}

bool
ConfigSubscriber::nextGeneration(milliseconds timeoutInMillis)
{
    return _set.acquireSnapshot(timeoutInMillis, true);
}

void
ConfigSubscriber::close()
{
    _set.close();
}

bool
ConfigSubscriber::isClosed() const
{
    return _set.isClosed();
}

int64_t
ConfigSubscriber::getGeneration() const
{
    return _set.getGeneration();
}

}
