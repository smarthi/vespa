// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/fnet/frt/supervisor.h>
#include <vespa/fnet/frt/target.h>
#include <vespa/config/frt/frtconfigrequestfactory.h>
#include <vespa/config/frt/frtconnection.h>
#include <vespa/config/common/payload_converter.h>
#include <vespa/config/common/configvalue.h>
#include <vespa/fastos/app.h>

#include <string>
#include <sstream>
#include <fstream>

#include <vespa/log/log.h>
LOG_SETUP("vespa-get-config");

using namespace config;

class GetConfig : public FastOS_Application
{
private:
    std::unique_ptr<fnet::frt::StandaloneFRT> _server;
    FRT_Target     *_target;

    GetConfig(const GetConfig &);
    GetConfig &operator=(const GetConfig &);

public:
    GetConfig() : _server(), _target(nullptr) {}
    ~GetConfig() override;
    int usage();
    void initRPC(const char *spec);
    void finiRPC();
    int Main() override;
};


GetConfig::~GetConfig()
{
    LOG_ASSERT( ! _server);
    LOG_ASSERT(_target == nullptr);
}


int
GetConfig::usage()
{
    fprintf(stderr, "usage: %s -n name -i configId\n", _argv[0]);
    fprintf(stderr, "-n name           (config name, including namespace, on the form <namespace>.<name>)\n");
    fprintf(stderr, "-i configId       (config id, optional)\n");
    fprintf(stderr, "-j                (output config as json, optional)\n");
    fprintf(stderr, "-l                (output config in legacy cfg format, optional)\n");
    fprintf(stderr, "-g generation     (config generation, optional)\n");
    fprintf(stderr, "-a schema         (config def schema file, optional)\n");
    fprintf(stderr, "-v defVersion     (config definition version, optional, deprecated)\n");
    fprintf(stderr, "-m defMd5         (definition md5sum, optional)\n");
    fprintf(stderr, "-t serverTimeout  (server timeout in seconds, default 3)\n");
    fprintf(stderr, "-w timeout        (timeout in seconds, default 10)\n");
    fprintf(stderr, "-s server         (server hostname, default localhost)\n");
    fprintf(stderr, "-p port           (proxy/server port number, default 19090)\n");
    fprintf(stderr, "-r traceLevel     (tracelevel to use in request, default 0\n");
    fprintf(stderr, "-V vespaVersion   (vespa version to use in request, optional\n");
    fprintf(stderr, "-d                (debug mode)\n");
    fprintf(stderr, "-h                (This help text)\n");
    return 1;
}


void
GetConfig::initRPC(const char *spec)
{
    _server = std::make_unique<fnet::frt::StandaloneFRT>();
    _target     = _server->supervisor().GetTarget(spec);
}


void
GetConfig::finiRPC()
{
    if (_target != nullptr) {
        _target->SubRef();
        _target = nullptr;
    }
    _server.reset();
}


int
GetConfig::Main()
{
    bool debugging = false;
    int c = -1;

    StringVector defSchema;
    const char *schemaString = nullptr;
    const char *defName = nullptr;
    const char *defMD5 = "";
    std::string defNamespace("config");
    const char *serverHost = "localhost";
    const char *configId = getenv("VESPA_CONFIG_ID");
    bool printAsJson = false;
    int traceLevel = config::protocol::readTraceLevel();
    const char *vespaVersionString = nullptr;
    int64_t generation = 0;

    if (configId == nullptr) {
        configId = "";
    }
    const char *configXxhash64 = "";
    int serverTimeout = 3;
    int clientTimeout = 10;

    int serverPort = 19090;

    const char *optArg = nullptr;
    int optInd = 0;
    while ((c = GetOpt("a:n:v:g:i:jlm:c:t:V:w:r:s:p:dh", optArg, optInd)) != -1) {
        int retval = 1;
        switch (c) {
        case 'a':
            schemaString = optArg;
            break;
        case 'n':
            defName = optArg;
            break;
        case 'v':
            break;
        case 'g':
            generation = atoll(optArg);
            break;
        case 'i':
            configId = optArg;
            break;
        case 'j':
            printAsJson = true;
            break;
        case 'l':
            printAsJson = false;
            break;
        case 'm':
            defMD5 = optArg;
            break;
        case 't':
            serverTimeout = atoi(optArg);
            break;
        case 'w':
            clientTimeout = atoi(optArg);
            break;
        case 'r':
            traceLevel = atoi(optArg);
            break;
        case 'V':
            vespaVersionString = optArg;
            break;
        case 's':
            serverHost = optArg;
            break;
        case 'p':
            serverPort = atoi(optArg);
            break;
        case 'd':
            debugging = true;
            break;
        case 'h':
            retval = 0;
            [[fallthrough]];
        case '?':
        default:
            usage();
            return retval;
        }
    }

    if (defName == nullptr || serverPort == 0) {
        usage();
        return 1;
    }

    if (strchr(defName, '.') != nullptr) {
        const char *tmp = defName;
        defName = strrchr(defName, '.');
        defName++;
        defNamespace = std::string(tmp, defName - tmp - 1);
    }

    std::string schema;
    if (schemaString == nullptr) {
      std::ostringstream tmp;
      tmp << getenv("VESPA_HOME");
      tmp << "/share/vespa/configdefinitions/";
      tmp << defNamespace;
      tmp << ".";
      tmp << defName;
      tmp << ".def";
      schema = tmp.str();
    } else {
      schema = schemaString;
    }
    if (debugging) {
      printf("Using schema in %s\n", schema.c_str());
    }
    std::ifstream is;
    is.open(schema);
    std::string item;
    while (std::getline(is, item)) {
      if (item.find("namespace=") == std::string::npos) {
        defSchema.push_back(item);
      }
    }
    is.close();

    std::ostringstream tmp;
    tmp << "tcp/";
    tmp << serverHost;
    tmp << ":";
    tmp << serverPort;
    std::string sspec = tmp.str();
    const char *spec = sspec.c_str();
    if (debugging) {
        printf("connecting to '%s'\n", spec);
    }
    initRPC(spec);

    auto vespaVersion = VespaVersion::getCurrentVersion();
    if (vespaVersionString != nullptr) {
        vespaVersion = VespaVersion::fromString(vespaVersionString);
    }

    FRTConfigRequestFactory requestFactory(traceLevel, vespaVersion, config::protocol::readProtocolCompressionType());
    FRTConnection connection(spec, _server->supervisor(), TimingValues());
    ConfigKey key(configId, defName, defNamespace, defMD5, defSchema);
    ConfigState state(configXxhash64, generation, false);
    FRTConfigRequest::UP request = requestFactory.createConfigRequest(key, &connection, state, serverTimeout * 1000);

    _target->InvokeSync(request->getRequest(), clientTimeout); // seconds

    ConfigResponse::UP response = request->createResponse(request->getRequest());
    response->validateResponse();
    if (response->isError()) {
        fprintf(stderr, "error %d: %s\n",
                response->errorCode(), response->errorMessage().c_str());
    } else {
        response->fill();
        ConfigKey rKey(response->getKey());
        const ConfigState & rState = response->getConfigState();
        const ConfigValue & rValue = response->getValue();
        if (debugging) {
            printf("defName    %s\n", rKey.getDefName().c_str());
            printf("defMD5     %s\n", rKey.getDefMd5().c_str());
            printf("defNamespace %s\n", rKey.getDefNamespace().c_str());

            printf("configID   %s\n", rKey.getConfigId().c_str());
            printf("configXxhash64  %s\n", rState.xxhash64.c_str());

            printf("generation  %" PRId64 "\n", rState.generation);
            printf("trace       %s\n", response->getTrace().toString().c_str());
        } else if (traceLevel > 0) {
            printf("trace       %s\n", response->getTrace().toString().c_str());
        }
        // TODO: Make printAsJson default
        if (printAsJson) {
            printf("%s\n", rValue.asJson().c_str());
        } else {
            StringVector lines = rValue.getLegacyFormat();
            for (uint32_t j = 0; j < lines.size(); j++) {
                printf("%s\n",  lines[j].c_str());
            }
        }
    }
    finiRPC();
    return 0;
}

int main(int argc, char **argv)
{
    GetConfig app;
    return app.Entry(argc, argv);
}
