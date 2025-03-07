// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "communicationmanager.h"
#include "config_logging.h"
#include "statemanager.h"
#include "statereporter.h"
#include "storagemetricsset.h"
#include "storagenode.h"
#include "storagenodecontext.h"

#include <vespa/metrics/metricmanager.h>
#include <vespa/storage/common/node_identity.h>
#include <vespa/storage/common/statusmetricconsumer.h>
#include <vespa/storage/common/storage_chain_builder.h>
#include <vespa/storage/frameworkimpl/status/statuswebserver.h>
#include <vespa/storage/frameworkimpl/thread/deadlockdetector.h>
#include <vespa/config/helper/configfetcher.hpp>
#include <vespa/vdslib/distribution/distribution.h>
#include <vespa/vespalib/io/fileutil.h>
#include <vespa/vespalib/util/exceptions.h>
#include <vespa/vespalib/util/time.h>
#include <fcntl.h>

#include <vespa/log/log.h>

LOG_SETUP(".node.server");

using vespa::config::content::StorDistributionConfigBuilder;
using vespa::config::content::core::StorServerConfigBuilder;
using std::make_shared;

namespace storage {

namespace {

    using vespalib::getLastErrorString;

    void writePidFile(const vespalib::string& pidfile)
    {
        int rv = -1;
        vespalib::string mypid = vespalib::make_string("%d\n", getpid());
        size_t lastSlash = pidfile.rfind('/');
        if (lastSlash != vespalib::string::npos) {
            vespalib::mkdir(pidfile.substr(0, lastSlash));
        }
        int fd = open(pidfile.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (fd != -1) {
            rv = write(fd, mypid.c_str(), mypid.size());
            close(fd);
        }
        if (rv < 1) {
            LOG(warning, "Failed to write pidfile '%s': %s",
                pidfile.c_str(), getLastErrorString().c_str());
        }
    }

    void removePidFile(const vespalib::string& pidfile)
    {
        if (unlink(pidfile.c_str()) != 0) {
            LOG(warning, "Failed to delete pidfile '%s': %s",
                pidfile.c_str(), getLastErrorString().c_str());
        }
    }

} // End of anonymous namespace

StorageNode::StorageNode(
        const config::ConfigUri & configUri,
        StorageNodeContext& context,
        ApplicationGenerationFetcher& generationFetcher,
        std::unique_ptr<HostInfo> hostInfo,
        RunMode mode)
    : _singleThreadedDebugMode(mode == SINGLE_THREADED_TEST_MODE),
      _configFetcher(),
      _hostInfo(std::move(hostInfo)),
      _context(context),
      _generationFetcher(generationFetcher),
      _rootFolder(),
      _attemptedStopped(false),
      _pidFile(),
      _statusWebServer(),
      _metrics(),
      _metricManager(),
      _deadLockDetector(),
      _statusMetrics(),
      _stateReporter(),
      _stateManager(),
      _chain(),
      _configLock(),
      _initial_config_mutex(),
      _serverConfig(),
      _clusterConfig(),
      _distributionConfig(),
      _doctypesConfig(),
      _bucketSpacesConfig(),
      _newServerConfig(),
      _newClusterConfig(),
      _newDistributionConfig(),
      _newDoctypesConfig(),
      _newBucketSpacesConfig(),
      _component(),
      _node_identity(),
      _configUri(configUri),
      _communicationManager(nullptr),
      _chain_builder(std::make_unique<StorageChainBuilder>())
{
}

void
StorageNode::subscribeToConfigs()
{
    _configFetcher = std::make_unique<config::ConfigFetcher>(_configUri.getContext());
    _configFetcher->subscribe<StorDistributionConfig>(_configUri.getConfigId(), this);
    _configFetcher->subscribe<UpgradingConfig>(_configUri.getConfigId(), this);
    _configFetcher->subscribe<StorServerConfig>(_configUri.getConfigId(), this);
    _configFetcher->subscribe<BucketspacesConfig>(_configUri.getConfigId(), this);

    _configFetcher->start();

    std::lock_guard configLockGuard(_configLock);
    _serverConfig = std::move(_newServerConfig);
    _clusterConfig = std::move(_newClusterConfig);
    _distributionConfig = std::move(_newDistributionConfig);
    _bucketSpacesConfig = std::move(_newBucketSpacesConfig);
}

void
StorageNode::initialize()
{
    // Avoid racing with concurrent reconfigurations before we've set up the entire
    // node component stack.
    std::lock_guard<std::mutex> concurrent_config_guard(_initial_config_mutex);

    _context.getComponentRegister().registerShutdownListener(*this);

    // Fetch configs needed first. These functions will just grab the config
    // and store them away, while having the config lock.
    subscribeToConfigs();

    updateUpgradeFlag(*_clusterConfig);

    // First update some basics that doesn't depend on anything else to be
    // available
    _rootFolder = _serverConfig->rootFolder;

    _context.getComponentRegister().setNodeInfo(_serverConfig->clusterName, getNodeType(), _serverConfig->nodeIndex);
    _context.getComponentRegister().setBucketIdFactory(document::BucketIdFactory());
    _context.getComponentRegister().setDistribution(make_shared<lib::Distribution>(*_distributionConfig));
    _context.getComponentRegister().setBucketSpacesConfig(*_bucketSpacesConfig);
    _node_identity = std::make_unique<NodeIdentity>(_serverConfig->clusterName, getNodeType(), _serverConfig->nodeIndex);

    _metrics = std::make_shared<StorageMetricSet>();
    _component = std::make_unique<StorageComponent>(_context.getComponentRegister(), "storagenode");
    _component->registerMetric(*_metrics);
    if (!_context.getComponentRegister().hasMetricManager()) {
        _metricManager = std::make_unique<metrics::MetricManager>();
        _context.getComponentRegister().setMetricManager(*_metricManager);
    }
    _component->registerMetricUpdateHook(*this, framework::SecondTime(300));

    // Initializing state manager early, as others use it init time to
    // update node state according min used bits etc.
    // Needs node type to be set right away. Needs thread pool, index and
    // dead lock detector too, but not before open()
    _stateManager = std::make_unique<StateManager>(
            _context.getComponentRegister(),
            _context.getComponentRegister().getMetricManager(),
            std::move(_hostInfo),
            _singleThreadedDebugMode);
    _context.getComponentRegister().setNodeStateUpdater(*_stateManager);

    // Create VDS root folder, in case it doesn't already exist.
    // Maybe better to rather fail if it doesn't exist, but tests
    // might break if we do that. Might alter later.
    vespalib::mkdir(_rootFolder);

    initializeNodeSpecific();

    _statusMetrics = std::make_unique<StatusMetricConsumer>(
            _context.getComponentRegister(), _context.getComponentRegister().getMetricManager());
    _stateReporter = std::make_unique<StateReporter>(
            _context.getComponentRegister(), _context.getComponentRegister().getMetricManager(),
            _generationFetcher);

    // Start deadlock detector
    _deadLockDetector = std::make_unique<DeadLockDetector>(_context.getComponentRegister());
    _deadLockDetector->enableWarning(_serverConfig->enableDeadLockDetectorWarnings);
    _deadLockDetector->enableShutdown(_serverConfig->enableDeadLockDetector);
    _deadLockDetector->setProcessSlack(vespalib::from_s(_serverConfig->deadLockDetectorTimeoutSlack));
    _deadLockDetector->setWaitSlack(vespalib::from_s(_serverConfig->deadLockDetectorTimeoutSlack));

    createChain(*_chain_builder);
    _chain = std::move(*_chain_builder).build();
    _chain_builder.reset();

    assert(_communicationManager != nullptr);
    _communicationManager->updateBucketSpacesConfig(*_bucketSpacesConfig);

    perform_post_chain_creation_init_steps();

    // Start the metric manager, such that it starts generating snapshots
    // and the like. Note that at this time, all metrics should hopefully
    // have been created, such that we don't need to pay the extra cost of
    // reinitializing metric manager often.
    if ( ! _context.getComponentRegister().getMetricManager().isInitialized() ) {
        _context.getComponentRegister().getMetricManager().init(_configUri, _context.getThreadPool());
    }

    if (_chain) {
        LOG(debug, "Storage chain configured. Calling open()");
        _chain->open();
    }

    initializeStatusWebServer();

        // Write pid file as the last thing we do. If we fail initialization
        // due to an exception we won't run shutdown. Thus we won't remove the
        // pid file if something throws after writing it in initialization.
        // Initialize _pidfile here, such that we can know that we didn't create
        // it in shutdown code for shutdown during init.
    _pidFile = _rootFolder + "/pidfile";
    writePidFile(_pidFile);
}

void
StorageNode::initializeStatusWebServer()
{
    if (_singleThreadedDebugMode) return;
    _statusWebServer = std::make_unique<StatusWebServer>(_context.getComponentRegister(),
                                                         _context.getComponentRegister(), _configUri);
}

#define DIFFER(a) (!(oldC.a == newC.a))
#define ASSIGN(a) { oldC.a = newC.a; updated = true; }
#define DIFFERWARN(a, b) \
    if (DIFFER(a)) { LOG(warning, "Live config failure: %s.", b); }

void
StorageNode::setNewDocumentRepo(const std::shared_ptr<const document::DocumentTypeRepo>& repo)
{
    std::lock_guard configLockGuard(_configLock);
    _context.getComponentRegister().setDocumentTypeRepo(repo);
    if (_communicationManager != nullptr) {
        _communicationManager->updateMessagebusProtocol(repo);
    }
}

void
StorageNode::updateUpgradeFlag(const UpgradingConfig& config)
{
    framework::UpgradeFlags flag(framework::NO_UPGRADE_SPECIAL_HANDLING_ACTIVE);
    if (config.upgradingMajorTo) {
        flag = framework::UPGRADING_TO_MAJOR_VERSION;
    } else if (config.upgradingMinorTo) {
        flag = framework::UPGRADING_TO_MINOR_VERSION;
    } else if (config.upgradingMajorFrom) {
        flag = framework::UPGRADING_FROM_MAJOR_VERSION;
    } else if (config.upgradingMinorFrom) {
        flag = framework::UPGRADING_FROM_MINOR_VERSION;
    }
    _context.getComponentRegister().setUpgradeFlag(flag);
}

void
StorageNode::handleLiveConfigUpdate(const InitialGuard & initGuard)
{
    // Make sure we don't conflict with initialize or shutdown threads.
    (void) initGuard;
    std::lock_guard configLockGuard(_configLock);

    assert(_chain);
    // If we get here, initialize is done running. We have to handle changes
    // we want to handle.

    if (_newServerConfig) {
        bool updated = false;
        StorServerConfigBuilder oldC(*_serverConfig);
        StorServerConfig& newC(*_newServerConfig);
        DIFFERWARN(rootFolder, "Cannot alter root folder of node live");
        DIFFERWARN(clusterName, "Cannot alter cluster name of node live");
        DIFFERWARN(nodeIndex, "Cannot alter node index of node live");
        DIFFERWARN(isDistributor, "Cannot alter role of node live");
        _serverConfig = std::make_unique<StorServerConfig>(oldC);
        _newServerConfig.reset();
        (void)updated;
    }
    if (_newDistributionConfig) {
        StorDistributionConfigBuilder oldC(*_distributionConfig);
        StorDistributionConfig& newC(*_newDistributionConfig);
        bool updated = false;
        if (DIFFER(redundancy)) {
            LOG(info, "Live config update: Altering redundancy from %u to %u.", oldC.redundancy, newC.redundancy);
            ASSIGN(redundancy);
        }
        if (DIFFER(initialRedundancy)) {
            LOG(info, "Live config update: Altering initial redundancy from %u to %u.",
                oldC.initialRedundancy, newC.initialRedundancy);
            ASSIGN(initialRedundancy);
        }
        if (DIFFER(ensurePrimaryPersisted)) {
            LOG(info, "Live config update: Now%s requiring primary copy to succeed for n of m operation to succeed.",
                newC.ensurePrimaryPersisted ? "" : " not");
            ASSIGN(ensurePrimaryPersisted);
        }
        if (DIFFER(activePerLeafGroup)) {
            LOG(info, "Live config update: Active per leaf group setting altered from %s to %s",
                oldC.activePerLeafGroup ? "true" : "false",
                newC.activePerLeafGroup ? "true" : "false");
            ASSIGN(activePerLeafGroup);
        }
        if (DIFFER(readyCopies)) {
            LOG(info, "Live config update: Altering number of searchable copies from %u to %u",
                oldC.readyCopies, newC.readyCopies);
            ASSIGN(readyCopies);
        }
        if (DIFFER(group)) {
            LOG(info, "Live config update: Group structure altered.");
            ASSIGN(group);
        }
        _distributionConfig = std::make_unique<StorDistributionConfig>(oldC);
        _newDistributionConfig.reset();
        if (updated) {
            _context.getComponentRegister().setDistribution(make_shared<lib::Distribution>(oldC));
            for (StorageLink* link = _chain.get(); link != nullptr; link = link->getNextLink()) {
                link->storageDistributionChanged();
            }
        }
    }
    if (_newClusterConfig) {
        updateUpgradeFlag(*_newClusterConfig);
        if (*_clusterConfig != *_newClusterConfig) {
            LOG(warning, "Live config failure: Cannot alter cluster config of node live.");
        }
        _newClusterConfig.reset();
    }

    if (_newBucketSpacesConfig) {
        _bucketSpacesConfig = std::move(_newBucketSpacesConfig);
        _context.getComponentRegister().setBucketSpacesConfig(*_bucketSpacesConfig);
        _communicationManager->updateBucketSpacesConfig(*_bucketSpacesConfig);
    }
}

void
StorageNode::notifyDoneInitializing()
{
    bool isDistributor = (getNodeType() == lib::NodeType::DISTRIBUTOR);
    LOG(info, "%s node ready. Done initializing. Giving out of sequence metric event. Config id is %s",
        isDistributor ? "Distributor" : "Storage", _configUri.getConfigId().c_str());
    _context.getComponentRegister().getMetricManager().forceEventLogging();
    if (!_singleThreadedDebugMode) {
        EV_STARTED(isDistributor ? "distributor" : "storagenode");
    }

    NodeStateUpdater::Lock::SP lock(_component->getStateUpdater().grabStateChangeLock());
    lib::NodeState ns(*_component->getStateUpdater().getReportedNodeState());
    ns.setState(lib::State::UP);
    _component->getStateUpdater().setReportedNodeState(ns);
    _chain->doneInit();
}

StorageNode::~StorageNode() = default;

void
StorageNode::removeConfigSubscriptions()
{
    LOG(debug, "Removing config subscribers");
    _configFetcher.reset();
}

void
StorageNode::shutdown()
{
    // Try to shut down in opposite order of initialize. Bear in mind that
    // we might be shutting down after init exception causing only parts
    // of the server to have initialize
    LOG(debug, "Shutting down storage node of type %s", getNodeType().toString().c_str());
    if (!_attemptedStopped) {
        LOG(debug, "Storage killed before requestShutdown() was called. No "
                   "reason has been given for why we're stopping.");
    }
        // Remove the subscription to avoid more callbacks from config
    removeConfigSubscriptions();

    if (_chain) {
        LOG(debug, "Closing storage chain");
        _chain->close();
        LOG(debug, "Flushing storage chain");
        _chain->flush();
    }

    if (_pidFile != "") {
        LOG(debug, "Removing pid file");
        removePidFile(_pidFile);
    }

    if (!_singleThreadedDebugMode) {
        EV_STOPPING(getNodeType() == lib::NodeType::DISTRIBUTOR ? "distributor" : "storagenode", "Stopped");
    }

    if (_context.getComponentRegister().hasMetricManager()) {
        LOG(debug, "Stopping metric manager. (Deleting chain may remove metrics)");
        _context.getComponentRegister().getMetricManager().stop();
    }

    // Delete the status web server before the actual status providers, to
    // ensure that web server does not query providers during shutdown
    _statusWebServer.reset();

    // For this to be safe, no-one can touch the state updater after we start
    // deleting the storage chain
    LOG(debug, "Removing state updater pointer as we're about to delete it.");
    if (_chain) {
        LOG(debug, "Deleting storage chain");
        _chain.reset();
    }
    if (_statusMetrics) {
        LOG(debug, "Deleting status metrics consumer");
        _statusMetrics.reset();
    }
    if (_stateReporter) {
        LOG(debug, "Deleting state reporter");
        _stateReporter.reset();
    }
    if (_stateManager) {
        LOG(debug, "Deleting state manager");
        _stateManager.reset();
    }
    if (_deadLockDetector) {
        LOG(debug, "Deleting dead lock detector");
        _deadLockDetector.reset();
    }
    if (_metricManager) {
        LOG(debug, "Deleting metric manager");
        _metricManager.reset();
    }
    if (_metrics) {
        LOG(debug, "Deleting metric set");
        _metrics.reset();
    }
    if (_component) {
        LOG(debug, "Deleting component");
        _component.reset();
    }

    LOG(debug, "Done shutting down node");
}

void StorageNode::configure(std::unique_ptr<StorServerConfig> config) {
    log_config_received(*config);
    // When we get config, we try to grab the config lock to ensure noone
    // else is doing configuration work, and then we write the new config
    // to a variable where we can find it later when processing config
    // updates
    {
        std::lock_guard configLockGuard(_configLock);
        _newServerConfig = std::move(config);
    }
    if (_serverConfig) {
        InitialGuard concurrent_config_guard(_initial_config_mutex);
        handleLiveConfigUpdate(concurrent_config_guard);
    }
}

void StorageNode::configure(std::unique_ptr<UpgradingConfig> config) {
    log_config_received(*config);
    {
        std::lock_guard configLockGuard(_configLock);
        _newClusterConfig = std::move(config);
    }
    if (_clusterConfig) {
        InitialGuard concurrent_config_guard(_initial_config_mutex);
        handleLiveConfigUpdate(concurrent_config_guard);
    }
}

void StorageNode::configure(std::unique_ptr<StorDistributionConfig> config) {
    log_config_received(*config);
    {
        std::lock_guard configLockGuard(_configLock);
        _newDistributionConfig = std::move(config);
    }
    if (_distributionConfig) {
        InitialGuard concurrent_config_guard(_initial_config_mutex);
        handleLiveConfigUpdate(concurrent_config_guard);
    }
}
void
StorageNode::configure(std::unique_ptr<document::config::DocumenttypesConfig> config,
                       bool hasChanged, int64_t generation)
{
    log_config_received(*config);
    (void) generation;
    if (!hasChanged)
        return;
    {
        std::lock_guard configLockGuard(_configLock);
        _newDoctypesConfig = std::move(config);
    }
    if (_doctypesConfig) {
        InitialGuard concurrent_config_guard(_initial_config_mutex);
        handleLiveConfigUpdate(concurrent_config_guard);
    }
}

void StorageNode::configure(std::unique_ptr<BucketspacesConfig> config) {
    log_config_received(*config);
    {
        std::lock_guard configLockGuard(_configLock);
        _newBucketSpacesConfig = std::move(config);
    }
    if (_bucketSpacesConfig) {
        InitialGuard concurrent_config_guard(_initial_config_mutex);
        handleLiveConfigUpdate(concurrent_config_guard);
    }
}

bool
StorageNode::attemptedStopped() const
{
    return _attemptedStopped;
}

void
StorageNode::updateMetrics(const MetricLockGuard &) {
    _metrics->updateMetrics();
}

void
StorageNode::waitUntilInitialized(uint32_t timeout) {
    framework::defaultimplementation::RealClock clock;
    framework::MilliSecTime endTime(
            clock.getTimeInMillis() + framework::MilliSecTime(1000 * timeout));
    while (true) {
        {
            NodeStateUpdater::Lock::SP lock(_component->getStateUpdater().grabStateChangeLock());
            lib::NodeState nodeState(*_component->getStateUpdater().getReportedNodeState());
            if (nodeState.getState() == lib::State::UP) break;
        }
        std::this_thread::sleep_for(10ms);
        if (clock.getTimeInMillis() >= endTime) {
            std::ostringstream ost;
            ost << "Storage server not initialized after waiting timeout of "
                << timeout << " seconds.";
            throw vespalib::IllegalStateException(ost.str(), VESPA_STRLOC);
        }
    }
}

void
StorageNode::requestShutdown(vespalib::stringref reason)
{
    if (_attemptedStopped) return;
    if (_component) {
        NodeStateUpdater::Lock::SP lock(_component->getStateUpdater().grabStateChangeLock());
        lib::NodeState nodeState(*_component->getStateUpdater().getReportedNodeState());
        if (nodeState.getState() != lib::State::STOPPING) {
            nodeState.setState(lib::State::STOPPING);
            nodeState.setDescription(reason);
            _component->getStateUpdater().setReportedNodeState(nodeState);
        }
    }
    _attemptedStopped = true;
}

std::unique_ptr<StateManager>
StorageNode::releaseStateManager() {
    return std::move(_stateManager);
}

void
StorageNode::set_storage_chain_builder(std::unique_ptr<IStorageChainBuilder> builder)
{
    _chain_builder = std::move(builder);
}

} // storage
