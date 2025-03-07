// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "communicationmanager.h"
#include "rpcrequestwrapper.h"
#include <vespa/documentapi/messagebus/messages/wrongdistributionreply.h>
#include <vespa/messagebus/emptyreply.h>
#include <vespa/messagebus/network/rpcnetworkparams.h>
#include <vespa/messagebus/rpcmessagebus.h>
#include <vespa/storage/common/bucket_resolver.h>
#include <vespa/storage/common/nodestateupdater.h>
#include <vespa/storage/config/config-stor-server.h>
#include <vespa/storage/storageserver/configurable_bucket_resolver.h>
#include <vespa/storage/storageserver/rpc/shared_rpc_resources.h>
#include <vespa/storage/storageserver/rpc/cluster_controller_api_rpc_service.h>
#include <vespa/storage/storageserver/rpc/message_codec_provider.h>
#include <vespa/storage/storageserver/rpc/storage_api_rpc_service.h>
#include <vespa/storageapi/message/state.h>
#include <vespa/storageframework/generic/clock/timer.h>
#include <vespa/vespalib/stllike/asciistream.h>
#include <vespa/vespalib/util/stringfmt.h>
#include <vespa/document/bucket/fixed_bucket_spaces.h>
#include <vespa/config/helper/configfetcher.hpp>
#include <string_view>

#include <vespa/log/bufferedlogger.h>
LOG_SETUP(".communication.manager");

using vespalib::make_string;
using document::FixedBucketSpaces;

namespace storage {

StorageTransportContext::StorageTransportContext(std::unique_ptr<documentapi::DocumentMessage> msg)
    : _docAPIMsg(std::move(msg))
{ }

StorageTransportContext::StorageTransportContext(std::unique_ptr<mbusprot::StorageCommand> msg)
    : _storageProtocolMsg(std::move(msg))
{ }

StorageTransportContext::StorageTransportContext(std::unique_ptr<RPCRequestWrapper> request)
    : _request(std::move(request))
{ }

StorageTransportContext::~StorageTransportContext() = default;

void
CommunicationManager::receiveStorageReply(const std::shared_ptr<api::StorageReply>& reply)
{
    assert(reply);
    enqueue_or_process(reply);
}

namespace {
    vespalib::string getNodeId(StorageComponent& sc) {
        vespalib::asciistream ost;
        ost << sc.cluster_context().cluster_name() << "/" << sc.getNodeType() << "/" << sc.getIndex();
        return ost.str();
    }

    framework::SecondTime TEN_MINUTES(600);

}

void
CommunicationManager::handleMessage(std::unique_ptr<mbus::Message> msg)
{
    MBUS_TRACE(msg->getTrace(), 4, getNodeId(_component)
               + " CommunicationManager: Received message from message bus");
    // Relaxed load since we're not doing any dependent reads that aren't
    // already covered by some other form of explicit synchronization.
    if (_closed.load(std::memory_order_relaxed)) {
        LOG(debug, "Not handling command of type %d as we have closed down", msg->getType());
        MBUS_TRACE(msg->getTrace(), 6, "Communication manager: Failing message as we are closed");
        auto reply = std::make_unique<mbus::EmptyReply>();
        reply->addError(mbus::Error(documentapi::DocumentProtocol::ERROR_ABORTED, "Node shutting down"));
        msg->swapState(*reply);
        _messageBusSession->reply(std::move(reply));
        return;
    }
    const vespalib::string & protocolName = msg->getProtocol();

    if (protocolName == documentapi::DocumentProtocol::NAME) {
        std::unique_ptr<documentapi::DocumentMessage> docMsgPtr(static_cast<documentapi::DocumentMessage*>(msg.release()));

        assert(docMsgPtr);

        std::unique_ptr<api::StorageCommand> cmd;
        try {
            cmd = _docApiConverter.toStorageAPI(static_cast<documentapi::DocumentMessage&>(*docMsgPtr));
        } catch (document::UnknownBucketSpaceException& e) {
            fail_with_unresolvable_bucket_space(std::move(docMsgPtr), e.getMessage());
            return;
        }

        if ( ! cmd) {
            LOGBM(warning, "Unsupported message: StorageApi could not convert message of type %d to a storageapi message",
                  docMsgPtr->getType());
            _metrics.convertToStorageAPIFailures.inc();
            return;
        }

        cmd->setTrace(docMsgPtr->steal_trace());
        cmd->setTransportContext(std::make_unique<StorageTransportContext>(std::move(docMsgPtr)));

        enqueue_or_process(std::move(cmd));
    } else if (protocolName == mbusprot::StorageProtocol::NAME) {
        std::unique_ptr<mbusprot::StorageCommand> storMsgPtr(static_cast<mbusprot::StorageCommand*>(msg.release()));

        assert(storMsgPtr);

        //TODO: Can it be moved ?
        std::shared_ptr<api::StorageCommand> cmd = storMsgPtr->getCommand();
        cmd->setTimeout(storMsgPtr->getTimeRemaining());
        cmd->setTrace(storMsgPtr->steal_trace());
        cmd->setTransportContext(std::make_unique<StorageTransportContext>(std::move(storMsgPtr)));

        enqueue_or_process(std::move(cmd));
    } else {
        LOGBM(warning, "Received unsupported message type %d for protocol '%s'",
              msg->getType(), msg->getProtocol().c_str());
    }
}

void
CommunicationManager::handleReply(std::unique_ptr<mbus::Reply> reply)
{
    MBUS_TRACE(reply->getTrace(), 4, getNodeId(_component) + "Communication manager: Received reply from message bus");
    // Relaxed load since we're not doing any dependent reads that aren't
    // already covered by some other form of explicit synchronization.
    if (_closed.load(std::memory_order_relaxed)) {
        LOG(debug, "Not handling reply of type %d as we have closed down", reply->getType());
        return;
    }
    LOG(spam, "Got reply of type %d, trace is %s",
        reply->getType(), reply->getTrace().toString().c_str());
    // EmptyReply must be converted to real replies before processing.
    if (reply->getType() == 0) {
        std::unique_ptr<mbus::Message> message(reply->getMessage());

        if (message) {
            std::unique_ptr<mbus::Reply> convertedReply;

            const vespalib::string& protocolName = message->getProtocol();
            if (protocolName == documentapi::DocumentProtocol::NAME) {
                convertedReply = static_cast<documentapi::DocumentMessage &>(*message).createReply();
            } else if (protocolName == mbusprot::StorageProtocol::NAME) {
                std::shared_ptr<api::StorageReply> repl(static_cast<mbusprot::StorageCommand &>(*message).getCommand()->makeReply());
                auto sreply = std::make_unique<mbusprot::StorageReply>(repl);

                if (reply->hasErrors()) {
                    // Convert only the first error since storageapi only
                    // supports one return code.
                    uint32_t mbuscode = reply->getError(0).getCode();
                    api::ReturnCode::Result code((api::ReturnCode::Result) mbuscode);
                    // Encode mbuscode into message not to lose it
                    sreply->getReply()->setResult(storage::api::ReturnCode(
                                code,
                                mbus::ErrorCode::getName(mbuscode)
                                + vespalib::string(": ")
                                + reply->getError(0).getMessage()
                                + vespalib::string(" (from ")
                                + reply->getError(0).getService()
                                + vespalib::string(")")));
                }
                convertedReply = std::move(sreply);
            } else {
                LOG(warning, "Received reply of unhandled protocol '%s'", protocolName.c_str());
                return;
            }

            convertedReply->swapState(*reply);
            convertedReply->setMessage(std::move(message));
            reply = std::move(convertedReply);
        }
        if (reply->getType() == 0) {
            LOG(warning, "Failed to convert empty reply by reflecting on local message copy.");
            return;
        }
    }

    if (reply->getContext().value.UINT64 != FORWARDED_MESSAGE) {
        const vespalib::string& protocolName = reply->getProtocol();

        if (protocolName == documentapi::DocumentProtocol::NAME) {
            std::shared_ptr<api::StorageCommand> originalCommand;
            {
                std::lock_guard lock(_messageBusSentLock);
                typedef std::map<api::StorageMessage::Id, api::StorageCommand::SP> MessageMap;
                MessageMap::iterator iter(_messageBusSent.find(reply->getContext().value.UINT64));
                if (iter != _messageBusSent.end()) {
                    originalCommand.swap(iter->second);
                    _messageBusSent.erase(iter);
                } else {
                    LOG(warning, "Failed to convert reply - original sent command doesn't exist");
                    return;
                }
            }

            std::shared_ptr<api::StorageReply> sar(
                    _docApiConverter.toStorageAPI(static_cast<documentapi::DocumentReply&>(*reply), *originalCommand));

            if (sar) {
                sar->setTrace(reply->steal_trace());
                receiveStorageReply(sar);
            }
        } else if (protocolName == mbusprot::StorageProtocol::NAME) {
            mbusprot::StorageReply* sr(static_cast<mbusprot::StorageReply*>(reply.get()));
            sr->getReply()->setTrace(reply->steal_trace());
            receiveStorageReply(sr->getReply());
        } else {
            LOGBM(warning, "Received unsupported reply type %d for protocol '%s'.",
                  reply->getType(), reply->getProtocol().c_str());
        }
    }
}

void CommunicationManager::fail_with_unresolvable_bucket_space(
        std::unique_ptr<documentapi::DocumentMessage> msg,
        const vespalib::string& error_message)
{
    LOG(debug, "Could not map DocumentAPI message to internal bucket: %s", error_message.c_str());
    MBUS_TRACE(msg->getTrace(), 6, "Communication manager: Failing message as its document type has no known bucket space mapping");
    std::unique_ptr<mbus::Reply> reply;
    reply = std::make_unique<mbus::EmptyReply>();
    reply->addError(mbus::Error(documentapi::DocumentProtocol::ERROR_REJECTED, error_message));
    msg->swapState(*reply);
    _metrics.bucketSpaceMappingFailures.inc();
    _messageBusSession->reply(std::move(reply));
}

namespace {

struct PlaceHolderBucketResolver : public BucketResolver {
    document::Bucket bucketFromId(const document::DocumentId &) const override {
        return document::Bucket(FixedBucketSpaces::default_space(), document::BucketId(0));
    }
    document::BucketSpace bucketSpaceFromName(const vespalib::string &) const override {
        return FixedBucketSpaces::default_space();
    }
    vespalib::string nameFromBucketSpace(const document::BucketSpace &bucketSpace) const override {
        assert(bucketSpace == FixedBucketSpaces::default_space());
        return FixedBucketSpaces::to_string(bucketSpace);
    }
};

vespalib::compression::CompressionConfig
convert_to_rpc_compression_config(const vespa::config::content::core::StorCommunicationmanagerConfig& mgr_config) {
    using vespalib::compression::CompressionConfig;
    using vespa::config::content::core::StorCommunicationmanagerConfig;
    auto compression_type = CompressionConfig::toType(
            StorCommunicationmanagerConfig::Rpc::Compress::getTypeName(mgr_config.rpc.compress.type).c_str());
    return CompressionConfig(compression_type, mgr_config.rpc.compress.level, 90, mgr_config.rpc.compress.limit);
}

}

CommunicationManager::CommunicationManager(StorageComponentRegister& compReg, const config::ConfigUri & configUri)
    : StorageLink("Communication manager"),
      _component(compReg, "communicationmanager"),
      _metrics(),
      _shared_rpc_resources(),    // Created upon initial configuration
      _storage_api_rpc_service(), // (ditto)
      _cc_rpc_service(),          // (ditto)
      _eventQueue(),
      _mbus(),
      _configUri(configUri),
      _closed(false),
      _docApiConverter(configUri, std::make_shared<PlaceHolderBucketResolver>()),
      _thread(),
      _skip_thread(false)
{
    _component.registerMetricUpdateHook(*this, framework::SecondTime(5));
    _component.registerMetric(_metrics);
}

void
CommunicationManager::onOpen()
{
    _configFetcher = std::make_unique<config::ConfigFetcher>(_configUri.getContext());
    _configFetcher->subscribe<vespa::config::content::core::StorCommunicationmanagerConfig>(_configUri.getConfigId(), this);
    _configFetcher->start();
    _thread = _component.startThread(*this, 60s);

    if (_shared_rpc_resources) {
        _shared_rpc_resources->start_server_and_register_slobrok(_component.getIdentity());
    }
}

CommunicationManager::~CommunicationManager()
{
    if (!_closed && StorageLink::getState() >= StorageLink::OPENED) {
        // We can reach this state if onOpen fails due to network problems or
        // other exceptions. The storage link will be in an opened state,
        // but it cannot in general call onClose on a link that failed onOpen,
        // as this would violate the assumption that close should always follow
        // open. We can allow ourselves to explicitly close in the constructor
        // because our onClose handles closing a partially initialized state.
        onClose();
    }

    _sourceSession.reset();
    _messageBusSession.reset();
    _mbus.reset();

    // Clear map of sent messages _before_ we delete any visitor threads to
    // avoid any issues where unloading shared libraries causes messages
    // created by dynamic visitors to point to unmapped memory
    _messageBusSent.clear();

    closeNextLink();
    LOG(debug, "Deleting link %s.", toString().c_str());
}

void CommunicationManager::onClose()
{
    // Avoid getting config during shutdown
    _configFetcher.reset();

    _closed = true;

    if (_mbus) {
        if (_messageBusSession) {
            _messageBusSession->close();
        }
    }

    // TODO remove? this no longer has any particularly useful semantics
    if (_cc_rpc_service) {
        _cc_rpc_service->close();
    }
    // TODO do this after we drain queues?
    if (_shared_rpc_resources) {
        _shared_rpc_resources->shutdown();
    }

    // Stopping pumper thread should stop all incoming messages from being
    // processed.
    if (_thread) {
        _thread->interrupt();
        _eventQueue.signal();
        _thread->join();
        _thread.reset();
    }

    // Emptying remaining queued messages
    // FIXME but RPC/mbus is already shut down at this point...! Make sure we handle this
    std::shared_ptr<api::StorageMessage> msg;
    api::ReturnCode code(api::ReturnCode::ABORTED, "Node shutting down");
    while (_eventQueue.size() > 0) {
        assert(_eventQueue.getNext(msg, 0ms));
        if (!msg->getType().isReply()) {
            std::shared_ptr<api::StorageReply> reply(static_cast<api::StorageCommand&>(*msg).makeReply());
            reply->setResult(code);
            sendReply(reply);
        }
    }
}

void
CommunicationManager::configureMessageBusLimits(const CommunicationManagerConfig& cfg)
{
    const bool isDist(_component.getNodeType() == lib::NodeType::DISTRIBUTOR);
    auto& mbus(_mbus->getMessageBus());
    mbus.setMaxPendingCount(isDist ? cfg.mbusDistributorNodeMaxPendingCount
                                   : cfg.mbusContentNodeMaxPendingCount);
    mbus.setMaxPendingSize(isDist ? cfg.mbusDistributorNodeMaxPendingSize
                                  : cfg.mbusContentNodeMaxPendingSize);
}

void CommunicationManager::configure(std::unique_ptr<CommunicationManagerConfig> config)
{
    // Only allow dynamic (live) reconfiguration of message bus limits.
    _skip_thread = config->skipThread;
    if (_mbus) {
        configureMessageBusLimits(*config);
        if (_mbus->getRPCNetwork().getPort() != config->mbusport) {
            auto m = make_string("mbus port changed from %d to %d. Will conduct a quick, but controlled restart.",
                                 _mbus->getRPCNetwork().getPort(), config->mbusport);
            LOG(warning, "%s", m.c_str());
            _component.requestShutdown(m);
        }
        if (_shared_rpc_resources->listen_port() != config->rpcport) {
            auto m = make_string("rpc port changed from %d to %d. Will conduct a quick, but controlled restart.",
                                 _shared_rpc_resources->listen_port(), config->rpcport);
            LOG(warning, "%s", m.c_str());
            _component.requestShutdown(m);
        }
        return;
    };

    if (!_configUri.empty()) {
        LOG(debug, "setting up slobrok config from id: '%s", _configUri.getConfigId().c_str());
        mbus::RPCNetworkParams params(_configUri);
        params.setConnectionExpireSecs(config->mbus.rpctargetcache.ttl);
        params.setNumThreads(std::max(1, config->mbus.numThreads));
        params.setNumNetworkThreads(std::max(1, config->mbus.numNetworkThreads));
        params.setNumRpcTargets(std::max(1, config->mbus.numRpcTargets));
        params.setDispatchOnDecode(config->mbus.dispatchOnDecode);
        params.setDispatchOnEncode(config->mbus.dispatchOnEncode);
        params.setTcpNoDelay(config->mbus.tcpNoDelay);

        params.setIdentity(mbus::Identity(_component.getIdentity()));
        if (config->mbusport != -1) {
            params.setListenPort(config->mbusport);
        }

        using CompressionConfig = vespalib::compression::CompressionConfig;
        CompressionConfig::Type compressionType = CompressionConfig::toType(
                CommunicationManagerConfig::Mbus::Compress::getTypeName(config->mbus.compress.type).c_str());
        params.setCompressionConfig(CompressionConfig(compressionType, config->mbus.compress.level,
                                                      90, config->mbus.compress.limit));
        params.setSkipRequestThread(config->mbus.skipRequestThread);
        params.setSkipReplyThread(config->mbus.skipReplyThread);

        // Configure messagebus here as we for legacy reasons have
        // config here.
        auto documentTypeRepo = _component.getTypeRepo()->documentTypeRepo;
        _mbus = std::make_unique<mbus::RPCMessageBus>(
                mbus::ProtocolSet()
                        .add(std::make_shared<documentapi::DocumentProtocol>(documentTypeRepo))
                        .add(std::make_shared<mbusprot::StorageProtocol>(documentTypeRepo)),
                params,
                _configUri);

        configureMessageBusLimits(*config);
    }

    _message_codec_provider = std::make_unique<rpc::MessageCodecProvider>(_component.getTypeRepo()->documentTypeRepo);
    _shared_rpc_resources = std::make_unique<rpc::SharedRpcResources>(_configUri, config->rpcport,
                                                                      config->rpc.numNetworkThreads, config->rpc.eventsBeforeWakeup);
    _cc_rpc_service = std::make_unique<rpc::ClusterControllerApiRpcService>(*this, *_shared_rpc_resources);
    rpc::StorageApiRpcService::Params rpc_params;
    rpc_params.compression_config = convert_to_rpc_compression_config(*config);
    rpc_params.num_rpc_targets_per_node = config->rpc.numTargetsPerNode;
    _storage_api_rpc_service = std::make_unique<rpc::StorageApiRpcService>(
            *this, *_shared_rpc_resources, *_message_codec_provider, rpc_params);

    if (_mbus) {
        mbus::DestinationSessionParams dstParams;
        dstParams.setName("default");
        dstParams.setBroadcastName(true);
        dstParams.setMessageHandler(*this);
        _messageBusSession = _mbus->getMessageBus().createDestinationSession(dstParams);

        mbus::SourceSessionParams srcParams;
        srcParams.setThrottlePolicy(mbus::IThrottlePolicy::SP());
        srcParams.setReplyHandler(*this);
        _sourceSession = _mbus->getMessageBus().createSourceSession(srcParams);
    }
}

void
CommunicationManager::process(const std::shared_ptr<api::StorageMessage>& msg)
{
    MBUS_TRACE(msg->getTrace(), 9, "Communication manager: Sending message down chain.");
    framework::MilliSecTimer startTime(_component.getClock());
    try {
        LOG(spam, "Process: %s", msg->toString().c_str());

        if (!onDown(msg)) {
            sendDown(msg);
        }

        LOG(spam, "Done processing: %s", msg->toString().c_str());
        _metrics.messageProcessTime.addValue(startTime.getElapsedTimeAsDouble());
    } catch (std::exception& e) {
        LOGBP(error, "When running command %s, caught exception %s. Discarding message",
              msg->toString().c_str(), e.what());
        _metrics.exceptionMessageProcessTime.addValue(startTime.getElapsedTimeAsDouble());
    }
}

void
CommunicationManager::enqueue_or_process(std::shared_ptr<api::StorageMessage> msg)
{
    assert(msg);
    if (_skip_thread.load(std::memory_order_relaxed)) {
        LOG(spam, "Process storage message %s, priority %d", msg->toString().c_str(), msg->getPriority());
        process(msg);
    } else {
        dispatch_async(std::move(msg));
    }
}

void CommunicationManager::dispatch_sync(std::shared_ptr<api::StorageMessage> msg) {
    LOG(spam, "Direct dispatch of storage message %s, priority %d", msg->toString().c_str(), msg->getPriority());
    process(std::move(msg));
}

void CommunicationManager::dispatch_async(std::shared_ptr<api::StorageMessage> msg) {
    LOG(spam, "Enqueued dispatch of storage message %s, priority %d", msg->toString().c_str(), msg->getPriority());
    _eventQueue.enqueue(std::move(msg));
}

bool
CommunicationManager::onUp(const std::shared_ptr<api::StorageMessage> & msg)
{
    MBUS_TRACE(msg->getTrace(), 6, "Communication manager: Sending " + msg->toString());
    if (msg->getType().isReply()) {
        const api::StorageReply & m = static_cast<const api::StorageReply&>(*msg);
        if (m.getResult().failed()) {
            LOG(debug, "Request %s failed: %s", msg->getType().toString().c_str(), m.getResult().toString().c_str());
        }
        return sendReply(std::static_pointer_cast<api::StorageReply>(msg));
    } else {
        return sendCommand(std::static_pointer_cast<api::StorageCommand>(msg));
    }
}

void
CommunicationManager::sendMessageBusMessage(const std::shared_ptr<api::StorageCommand>& msg,
                                            std::unique_ptr<mbus::Message> mbusMsg,
                                            const mbus::Route& route)
{
    // Relaxed load since we're not doing any dependent reads that aren't
    // already covered by some other form of explicit synchronization.
    if (_closed.load(std::memory_order_relaxed)) {
        return;
    }

    LOG(spam, "Sending message bus msg of type %d", mbusMsg->getType());

    MBUS_TRACE(mbusMsg->getTrace(), 6, "Communication manager: Passing message to source session");
    mbus::Result result = _sourceSession->send(std::move(mbusMsg), route);

    if (!result.isAccepted()) {
        std::shared_ptr<api::StorageReply> reply(msg->makeReply());
        if (reply) {
            if (result.getError().getCode() > mbus::ErrorCode::FATAL_ERROR) {
                reply->setResult(api::ReturnCode(api::ReturnCode::ABORTED, result.getError().getMessage()));
            } else {
                reply->setResult(api::ReturnCode(api::ReturnCode::BUSY, result.getError().getMessage()));
            }
        } else {
            LOG(spam, "Failed to synthesize reply");
        }

        sendDown(reply);
    }
}

bool
CommunicationManager::sendCommand(
        const std::shared_ptr<api::StorageCommand> & msg)
{
    if (!msg->getAddress()) {
        LOGBP(warning, "Got command without address of type %s in CommunicationManager::sendCommand",
              msg->getType().getName().c_str());
        return false;
    }
    if (!msg->sourceIndexSet()) {
        msg->setSourceIndex(_component.getIndex());
    }
    // Components can not specify what storage node to send to
    // without specifying protocol. This is a workaround, such that code
    // doesn't have to care whether message is in documentapi or storage
    // protocol.
    api::StorageMessageAddress address(*msg->getAddress());
    switch (msg->getType().getId()) {
        case api::MessageType::STATBUCKET_ID: {
            if (address.getProtocol() == api::StorageMessageAddress::Protocol::STORAGE) {
                address.setProtocol(api::StorageMessageAddress::Protocol::DOCUMENT);
            }
        }
        default:
            break;
    }

    framework::MilliSecTimer startTime(_component.getClock());
    switch (address.getProtocol()) {
        case api::StorageMessageAddress::Protocol::STORAGE:
    {
        LOG(debug, "Send to %s: %s", address.toString().c_str(), msg->toString().c_str());
        if (_storage_api_rpc_service->target_supports_direct_rpc(address)) {
            _storage_api_rpc_service->send_rpc_v1_request(msg);
        } else {
            auto cmd = std::make_unique<mbusprot::StorageCommand>(msg);

            cmd->setContext(mbus::Context(msg->getMsgId()));
            cmd->setRetryEnabled(false);
            cmd->setTimeRemaining(msg->getTimeout());
            cmd->setTrace(msg->steal_trace());
            sendMessageBusMessage(msg, std::move(cmd), address.to_mbus_route());
        }
        break;
    }
        case api::StorageMessageAddress::Protocol::DOCUMENT:
    {
        MBUS_TRACE(msg->getTrace(), 7, "Communication manager: Converting storageapi message to documentapi");

        std::unique_ptr<mbus::Message> mbusMsg(_docApiConverter.toDocumentAPI(*msg));

        if (mbusMsg) {
            MBUS_TRACE(msg->getTrace(), 7, "Communication manager: Converted OK");
            mbusMsg->setTrace(msg->steal_trace());
            mbusMsg->setRetryEnabled(false);

            {
                std::lock_guard lock(_messageBusSentLock);
                _messageBusSent[msg->getMsgId()] = msg;
            }
            sendMessageBusMessage(msg, std::move(mbusMsg), address.to_mbus_route());
            break;
        } else {
            LOGBM(warning, "This type of message can't be sent via messagebus");
            return false;
        }
    }
    default:
        return false;
    }
    _metrics.sendCommandLatency.addValue(startTime.getElapsedTimeAsDouble());
    return true;
}

void
CommunicationManager::serializeNodeState(const api::GetNodeStateReply& gns, std::ostream& os, bool includeDescription) const
{
    vespalib::asciistream tmp;
    if (gns.hasNodeState()) {
        gns.getNodeState().serialize(tmp, "", includeDescription);
    } else {
        _component.getStateUpdater().getReportedNodeState()->serialize(tmp, "", includeDescription);
    }
    os << tmp.str();
}

void
CommunicationManager::sendDirectRPCReply(
        RPCRequestWrapper& request,
        const std::shared_ptr<api::StorageReply>& reply)
{
    std::string_view requestName(request.getMethodName()); // TODO non-name based dispatch
    // TODO rework this entire dispatch mechanism :D
    if (requestName == rpc::StorageApiRpcService::rpc_v1_method_name()) {
        _storage_api_rpc_service->encode_rpc_v1_response(*request.raw_request(), *reply);
    } else if (requestName == "getnodestate3") {
        auto& gns(dynamic_cast<api::GetNodeStateReply&>(*reply));
        std::ostringstream ns;
        serializeNodeState(gns, ns, true);
        request.addReturnString(ns.str().c_str());
        request.addReturnString(gns.getNodeInfo().c_str());
        LOGBP(debug, "Sending getnodestate3 reply with host info '%s'.", gns.getNodeInfo().c_str());
    } else if (requestName == "getnodestate2") {
        auto& gns(dynamic_cast<api::GetNodeStateReply&>(*reply));
        std::ostringstream ns;
        serializeNodeState(gns, ns, true);
        request.addReturnString(ns.str().c_str());
        LOGBP(debug, "Sending getnodestate2 reply with no host info.");
    } else if (requestName == "setsystemstate2" || requestName == "setdistributionstates") {
        // No data to return
    } else if (requestName == "activate_cluster_state_version") {
        auto& activate_reply(dynamic_cast<api::ActivateClusterStateVersionReply&>(*reply));
        request.addReturnInt(activate_reply.actualVersion());
        LOGBP(debug, "sending activate_cluster_state_version reply for version %u with actual version %u ",
                     activate_reply.activateVersion(), activate_reply.actualVersion());
    } else {
        request.addReturnInt(reply->getResult().getResult());
        vespalib::stringref m = reply->getResult().getMessage();
        request.addReturnString(m.data(), m.size());

        if (reply->getType() == api::MessageType::GETNODESTATE_REPLY) {
            api::GetNodeStateReply& gns(static_cast<api::GetNodeStateReply&>(*reply));
            std::ostringstream ns;
            serializeNodeState(gns, ns, false);
            request.addReturnString(ns.str().c_str());
            request.addReturnInt(static_cast<int>(gns.getNodeState().getInitProgress().getValue() * 100));
        }
    }

    request.returnRequest();
}

void
CommunicationManager::sendMessageBusReply(
        StorageTransportContext& context,
        const std::shared_ptr<api::StorageReply>& reply)
{
    // Using messagebus for communication.
    mbus::Reply::UP replyUP;

    LOG(spam, "Sending message bus reply %s", reply->toString().c_str());

    // If this was originally documentapi, create a reply now and transfer the
    // state.
    if (context._docAPIMsg) {
        if (reply->getResult().getResult() == api::ReturnCode::WRONG_DISTRIBUTION) {
            replyUP = std::make_unique<documentapi::WrongDistributionReply>(reply->getResult().getMessage());
            replyUP->swapState(*context._docAPIMsg);
            replyUP->setTrace(reply->steal_trace());
            replyUP->addError(mbus::Error(documentapi::DocumentProtocol::ERROR_WRONG_DISTRIBUTION,
                                          reply->getResult().getMessage()));
        } else {
            replyUP = context._docAPIMsg->createReply();
            replyUP->swapState(*context._docAPIMsg);
            replyUP->setTrace(reply->steal_trace());
            replyUP->setMessage(std::move(context._docAPIMsg));
            _docApiConverter.transferReplyState(*reply, *replyUP);
        }
    } else if (context._storageProtocolMsg) {
        replyUP = std::make_unique<mbusprot::StorageReply>(reply);
        if (reply->getResult().getResult() != api::ReturnCode::OK) {
            replyUP->addError(mbus::Error(reply->getResult().getResult(), reply->getResult().getMessage()));
        }

        replyUP->swapState(*context._storageProtocolMsg);
        replyUP->setTrace(reply->steal_trace());
        replyUP->setMessage(std::move(context._storageProtocolMsg));
    }

    if (replyUP) {
        // Forward message only if it was successfully stored in storage.
        if (!replyUP->hasErrors()) {
            mbus::Message::UP messageUP = replyUP->getMessage();

            if (messageUP && messageUP->getRoute().hasHops()) {
                messageUP->setContext(mbus::Context(FORWARDED_MESSAGE));
                _sourceSession->send(std::move(messageUP));
            }
        }

        _messageBusSession->reply(std::move(replyUP));
    }
}

bool
CommunicationManager::sendReply(
        const std::shared_ptr<api::StorageReply>& reply)
{
    // Relaxed load since we're not doing any dependent reads that aren't
    // already covered by some other form of explicit synchronization.
    if (_closed.load(std::memory_order_relaxed)) {
        reply->setResult(api::ReturnCode(api::ReturnCode::ABORTED, "Node is shutting down"));
    }

    std::unique_ptr<StorageTransportContext> context(static_cast<StorageTransportContext*>(reply->getTransportContext().release()));

    if (!context) {
        LOG(spam, "No transport context in reply %s", reply->toString().c_str());
        // If it's an autogenerated reply for an internal message type, just throw it away
        // by returning that we've handled it. No one else will handle the reply, the
        // alternative is that it ends up as warning noise in the log.
        return (reply->getType().getId() == api::MessageType::INTERNAL_REPLY_ID);
    }

    framework::MilliSecTimer startTime(_component.getClock());
    if (context->_request) {
        sendDirectRPCReply(*(context->_request), reply);
    } else {
        sendMessageBusReply(*context, reply);
    }
    _metrics.sendReplyLatency.addValue(startTime.getElapsedTimeAsDouble());
    return true;
}


void
CommunicationManager::run(framework::ThreadHandle& thread)
{
    while (!thread.interrupted()) {
        thread.registerTick();
        std::shared_ptr<api::StorageMessage> msg;
        if (_eventQueue.getNext(msg, 100ms)) {
            process(msg);
        }
        std::lock_guard<std::mutex> guard(_earlierGenerationsLock);
        for (EarlierProtocols::iterator it(_earlierGenerations.begin());
             !_earlierGenerations.empty() &&
             ((it->first + TEN_MINUTES) < _component.getClock().getTimeInSeconds());
             it = _earlierGenerations.begin())
        {
            _earlierGenerations.erase(it);
        }
    }
}

void
CommunicationManager::updateMetrics(const MetricLockGuard &)
{
    _metrics.queueSize.addValue(_eventQueue.size());
}

void
CommunicationManager::print(std::ostream& out, bool verbose, const std::string& indent) const
{
    (void) verbose; (void) indent;
    out << "CommunicationManager";
}

void CommunicationManager::updateMessagebusProtocol(const std::shared_ptr<const document::DocumentTypeRepo>& repo) {
    if (_mbus) {
        framework::SecondTime now(_component.getClock().getTimeInSeconds());
        auto newDocumentProtocol = std::make_shared<documentapi::DocumentProtocol>(repo);
        std::lock_guard<std::mutex> guard(_earlierGenerationsLock);
        _earlierGenerations.push_back(std::make_pair(now, _mbus->getMessageBus().putProtocol(newDocumentProtocol)));
        auto newStorageProtocol = std::make_shared<mbusprot::StorageProtocol>(repo);
        _earlierGenerations.push_back(std::make_pair(now, _mbus->getMessageBus().putProtocol(newStorageProtocol)));
    }
    if (_message_codec_provider) {
        _message_codec_provider->update_atomically(repo);
    }
}

void CommunicationManager::updateBucketSpacesConfig(const BucketspacesConfig& config) {
    _docApiConverter.setBucketResolver(ConfigurableBucketResolver::from_config(config));
}

} // storage
