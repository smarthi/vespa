// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/vespalib/testkit/test_kit.h>
#include <vespa/config/common/misc.h>
#include <vespa/config/common/configrequest.h>
#include <vespa/config/common/timingvalues.h>
#include <vespa/config/common/trace.h>
#include <vespa/config/common/configkey.h>
#include <vespa/config/common/configholder.h>
#include <vespa/config/frt/frtconfigagent.h>
#include <config-my.h>

using namespace config;
using namespace std::chrono_literals;

class MyConfigRequest : public ConfigRequest
{
public:
    MyConfigRequest(const ConfigKey & key)
        : _key(key)
    { }

    const ConfigKey & getKey() const override { return _key; }
    bool abort() override { return false; }
    bool isAborted() const override { return false; }
    void setError(int errorCode) override { (void) errorCode; }
    bool verifyState(const ConfigState &) const override { return false; }
    const ConfigKey _key;
};

class MyConfigResponse : public ConfigResponse
{
public:
    MyConfigResponse(const ConfigKey & key, ConfigValue value, bool valid, int64_t timestamp,
                     const vespalib::string & xxhash64, const std::string & errorMsg, int errorC0de, bool iserror)
        : _key(key),
          _value(std::move(value)),
          _fillCalled(false),
          _valid(valid),
          _state(xxhash64, timestamp, false),
          _errorMessage(errorMsg),
          _errorCode(errorC0de),
          _isError(iserror)
    { }

    const ConfigKey& getKey() const override { return _key; }
    const ConfigValue & getValue() const override { return _value; }
    const ConfigState & getConfigState() const override { return _state; }
    bool hasValidResponse() const override { return _valid; }
    bool validateResponse() override { return _valid; }
    void fill() override { _fillCalled = true; }
    vespalib::string errorMessage() const override { return _errorMessage; }
    int errorCode() const override { return _errorCode; }
    bool isError() const override { return  _isError; }
    const Trace & getTrace() const override { return _trace; }

    const ConfigKey _key;
    const ConfigValue _value;
    bool _fillCalled;
    bool _valid;
    const ConfigState _state;
    vespalib::string _errorMessage;
    int _errorCode;
    bool _isError;
    Trace _trace;


    static ConfigResponse::UP createOKResponse(const ConfigKey & key, const ConfigValue & value, uint64_t timestamp = 10, const vespalib::string & xxhash64 = "a")
    {
        return std::make_unique<MyConfigResponse>(key, value, true, timestamp, xxhash64, "", 0, false);
    }

    static ConfigResponse::UP createServerErrorResponse(const ConfigKey & key, const ConfigValue & value)
    {
        return std::make_unique<MyConfigResponse>(key, value, true, 10, "a", "whinewhine", 2, true);
    }

    static ConfigResponse::UP createConfigErrorResponse(const ConfigKey & key, const ConfigValue & value)
    {
        return std::make_unique<MyConfigResponse>(key, value, false, 10, "a", "", 0, false);
    }
};

class MyHolder : public IConfigHolder
{
public:
    MyHolder() noexcept = default;
    ~MyHolder() = default;

    std::unique_ptr<ConfigUpdate> provide() override
    {
        return std::move(_update);
    }

    bool wait(milliseconds timeout) override
    {
        (void) timeout;
        return true;
    }

    void handle(std::unique_ptr<ConfigUpdate> update) override
    {
        if (_update) {
            update->merge(*_update);
        }
        _update = std::move(update);
    }

    bool poll() override { return true; }
    void interrupt() override { }
private:
    std::unique_ptr<ConfigUpdate> _update;
};


ConfigValue createValue(const std::string & myField, const std::string & xxhash64)
{
    StringVector lines;
    lines.push_back("myField \"" + myField + "\"");
    return ConfigValue(lines, xxhash64);
}

static TimingValues testTimingValues(
        2000,  // successTimeout
        500,  // errorTimeout
        500,   // initialTimeout
        4000ms,  // subscribeTimeout
        0,     // fixedDelay
        250,   // successDelay
        250,   // unconfiguredDelay
        500,   // configuredErrorDelay
        5,
        1000,
        2000);    // maxDelayMultiplier

TEST("require that agent returns correct values") {
    FRTConfigAgent handler(std::make_shared<MyHolder>(), testTimingValues);
    ASSERT_EQUAL(500u, handler.getTimeout());
    ASSERT_EQUAL(0u, handler.getWaitTime());
    ConfigState cs;
    ASSERT_EQUAL(cs.xxhash64, handler.getConfigState().xxhash64);
    ASSERT_EQUAL(cs.generation, handler.getConfigState().generation);
    ASSERT_EQUAL(cs.applyOnRestart, handler.getConfigState().applyOnRestart);
}

TEST("require that successful request is delivered to holder") {
    const ConfigKey testKey(ConfigKey::create<MyConfig>("mykey"));
    const ConfigValue testValue(createValue("l33t", "a"));
    auto latch = std::make_shared<MyHolder>();

    FRTConfigAgent handler(latch, testTimingValues);
    handler.handleResponse(MyConfigRequest(testKey), MyConfigResponse::createOKResponse(testKey, testValue));
    ASSERT_TRUE(latch->poll());
    std::unique_ptr<ConfigUpdate> update(latch->provide());
    ASSERT_TRUE(update);
    ASSERT_TRUE(update->hasChanged());
    MyConfig cfg(update->getValue());
    ASSERT_EQUAL("l33t", cfg.myField);
}

TEST("require that important(the change) request is delivered to holder even if it was not the last") {
    const ConfigKey testKey(ConfigKey::create<MyConfig>("mykey"));
    const ConfigValue testValue1(createValue("l33t", "a"));
    const ConfigValue testValue2(createValue("l34t", "b"));
    auto latch = std::make_shared<MyHolder>();

    FRTConfigAgent handler(latch, testTimingValues);
    handler.handleResponse(MyConfigRequest(testKey),
                           MyConfigResponse::createOKResponse(testKey, testValue1, 1, testValue1.getXxhash64()));
    ASSERT_TRUE(latch->poll());
    std::unique_ptr<ConfigUpdate> update(latch->provide());
    ASSERT_TRUE(update);
    ASSERT_TRUE(update->hasChanged());
    MyConfig cfg(update->getValue());
    ASSERT_EQUAL("l33t", cfg.myField);

    handler.handleResponse(MyConfigRequest(testKey),
                           MyConfigResponse::createOKResponse(testKey, testValue2, 2, testValue2.getXxhash64()));
    handler.handleResponse(MyConfigRequest(testKey),
                           MyConfigResponse::createOKResponse(testKey, testValue2, 3, testValue2.getXxhash64()));
    ASSERT_TRUE(latch->poll());
    update = latch->provide();
    ASSERT_TRUE(update);
    ASSERT_TRUE(update->hasChanged());
    MyConfig cfg2(update->getValue());
    ASSERT_EQUAL("l34t", cfg2.myField);
}

TEST("require that successful request sets correct wait time") {
    const ConfigKey testKey(ConfigKey::create<MyConfig>("mykey"));
    const ConfigValue testValue(createValue("l33t", "a"));
    auto latch = std::make_shared<MyHolder>();
    FRTConfigAgent handler(latch, testTimingValues);

    handler.handleResponse(MyConfigRequest(testKey), MyConfigResponse::createOKResponse(testKey, testValue));
    ASSERT_EQUAL(250u, handler.getWaitTime());

    handler.handleResponse(MyConfigRequest(testKey), MyConfigResponse::createOKResponse(testKey, testValue));
    ASSERT_EQUAL(250u, handler.getWaitTime());
}

TEST("require that bad config response returns false") {
    const ConfigKey testKey(ConfigKey::create<MyConfig>("mykey"));
    const ConfigValue testValue(createValue("myval", "a"));
    auto latch = std::make_shared<MyHolder>();
    FRTConfigAgent handler(latch, testTimingValues);

    handler.handleResponse(MyConfigRequest(testKey), MyConfigResponse::createConfigErrorResponse(testKey, testValue));
    ASSERT_EQUAL(250u, handler.getWaitTime());
    ASSERT_EQUAL(500u, handler.getTimeout());

    handler.handleResponse(MyConfigRequest(testKey), MyConfigResponse::createConfigErrorResponse(testKey, testValue));
    ASSERT_EQUAL(500u, handler.getWaitTime());
    ASSERT_EQUAL(500u, handler.getTimeout());

    handler.handleResponse(MyConfigRequest(testKey), MyConfigResponse::createConfigErrorResponse(testKey, testValue));
    ASSERT_EQUAL(750u, handler.getWaitTime());
    ASSERT_EQUAL(500u, handler.getTimeout());

    handler.handleResponse(MyConfigRequest(testKey), MyConfigResponse::createConfigErrorResponse(testKey, testValue));
    ASSERT_EQUAL(1000u, handler.getWaitTime());
    ASSERT_EQUAL(500u, handler.getTimeout());

    handler.handleResponse(MyConfigRequest(testKey), MyConfigResponse::createConfigErrorResponse(testKey, testValue));
    ASSERT_EQUAL(1250u, handler.getWaitTime());
    ASSERT_EQUAL(500u, handler.getTimeout());

    handler.handleResponse(MyConfigRequest(testKey), MyConfigResponse::createConfigErrorResponse(testKey, testValue));
    ASSERT_EQUAL(1250u, handler.getWaitTime());
    ASSERT_EQUAL(500u, handler.getTimeout());

    handler.handleResponse(MyConfigRequest(testKey), MyConfigResponse::createOKResponse(testKey, testValue));
    ASSERT_EQUAL(250u, handler.getWaitTime());
    ASSERT_EQUAL(2000u, handler.getTimeout());

    handler.handleResponse(MyConfigRequest(testKey), MyConfigResponse::createConfigErrorResponse(testKey, testValue));
    ASSERT_EQUAL(500u, handler.getWaitTime());
    ASSERT_EQUAL(500u, handler.getTimeout());
}

TEST("require that bad response returns false") {
    const ConfigKey testKey(ConfigKey::create<MyConfig>("mykey"));
    const ConfigValue testValue(StringVector(), "a");

    auto latch = std::make_shared<MyHolder>();
    FRTConfigAgent handler(latch, testTimingValues);

    handler.handleResponse(MyConfigRequest(testKey), MyConfigResponse::createServerErrorResponse(testKey, testValue));
    ASSERT_EQUAL(250u, handler.getWaitTime());

    handler.handleResponse(MyConfigRequest(testKey), MyConfigResponse::createServerErrorResponse(testKey, testValue));
    ASSERT_EQUAL(500u, handler.getWaitTime());

    handler.handleResponse(MyConfigRequest(testKey), MyConfigResponse::createServerErrorResponse(testKey, testValue));
    ASSERT_EQUAL(750u, handler.getWaitTime());

    handler.handleResponse(MyConfigRequest(testKey), MyConfigResponse::createServerErrorResponse(testKey, testValue));
    ASSERT_EQUAL(1000u, handler.getWaitTime());

    handler.handleResponse(MyConfigRequest(testKey), MyConfigResponse::createServerErrorResponse(testKey, testValue));
    ASSERT_EQUAL(1250u, handler.getWaitTime());

    handler.handleResponse(MyConfigRequest(testKey), MyConfigResponse::createServerErrorResponse(testKey, testValue));
    ASSERT_EQUAL(1250u, handler.getWaitTime());
}

TEST_MAIN() { TEST_RUN_ALL(); }
