// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
//
#pragma once

#include "subscriptionid.h"
#include <atomic>
#include <chrono>
#include <memory>
#include <vector>

namespace config {

class IConfigContext;
class IConfigManager;
class ConfigSubscription;
class ConfigKey;

/**
 * A ConfigSubscriptionSet is a set of configs that can be subscribed to.
 */
class ConfigSubscriptionSet
{
public:
    using milliseconds = std::chrono::milliseconds;
    /**
     * Constructs a new ConfigSubscriptionSet object which can be used to subscribe for 1
     * or more configs from a specific source.
     *
     * @param context A ConfigContext shared between all subscriptions.
     */
    explicit ConfigSubscriptionSet(std::shared_ptr<IConfigContext> context);

    ConfigSubscriptionSet(const ConfigSubscriptionSet &) = delete;
    ConfigSubscriptionSet & operator= (const ConfigSubscriptionSet &) = delete;
    ~ConfigSubscriptionSet();

    /**
     * Return the current generation number for configs.
     *
     * @return generation number
     */
    int64_t getGeneration() const;

    /**
     * Closes the set, which will interrupt acquireSnapshot and unsubscribe all
     * configs currently subscribed for.
     */
    void close();

    /**
     * Checks if this subscription set is closed.
     */
    bool isClosed() const;

    // Helpers for doing the subscription
    std::shared_ptr<ConfigSubscription> subscribe(const ConfigKey & key, milliseconds timeoutInMillis);

    // Tries to acquire a new snapshot of config within the timeout
    bool acquireSnapshot(milliseconds timeoutInMillis, bool requireDifference);

private:
    // Describes the state of the subscriber.
    enum SubscriberState { OPEN, FROZEN, CONFIGURED, CLOSED };
    using SubscriptionList = std::vector<std::shared_ptr<ConfigSubscription>>;

    std::shared_ptr<IConfigContext> _context;             // Context to keep alive managers.
    IConfigManager &                _mgr;                 // The config manager that we use.
    int64_t                         _currentGeneration;   // Holds the current config generation.
    SubscriptionList                _subscriptionList;    // List of current subscriptions.
    std::atomic<SubscriberState>    _state;               // Current state of this subscriber.
};

} // namespace config

