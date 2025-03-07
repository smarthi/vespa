// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include "frtsourcefactory.h"
#include "frtsource.h"

namespace config {

FRTSourceFactory::FRTSourceFactory(ConnectionFactory::UP connectionFactory, const TimingValues & timingValues, int traceLevel, const VespaVersion & vespaVersion, const CompressionType & compressionType)
    : _connectionFactory(std::move(connectionFactory)),
      _requestFactory(traceLevel, vespaVersion, compressionType),
      _timingValues(timingValues)
{
}

std::unique_ptr<Source>
FRTSourceFactory::createSource(std::shared_ptr<IConfigHolder> holder, const ConfigKey & key) const
{
    return std::make_unique<FRTSource>(_connectionFactory, _requestFactory,
                                       std::make_unique<FRTConfigAgent>(std::move(holder), _timingValues), key);
}

} // namespace config
