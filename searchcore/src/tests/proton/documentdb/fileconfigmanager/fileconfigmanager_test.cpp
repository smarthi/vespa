// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "config-mycfg.h"
#include <vespa/config-attributes.h>
#include <vespa/config-imported-fields.h>
#include <vespa/config-indexschema.h>
#include <vespa/config-rank-profiles.h>
#include <vespa/config-summary.h>
#include <vespa/config-summarymap.h>
#include <vespa/config/helper/configgetter.hpp>
#include <vespa/document/config/documenttypes_config_fwd.h>
#include <vespa/document/repo/documenttyperepo.h>
#include <vespa/searchcore/proton/server/bootstrapconfig.h>
#include <vespa/searchcore/proton/server/fileconfigmanager.h>
#include <vespa/searchcore/proton/test/documentdb_config_builder.h>
#include <vespa/searchsummary/config/config-juniperrc.h>
#include <vespa/vespalib/io/fileutil.h>
#include <vespa/config-bucketspaces.h>
#include <vespa/vespalib/testkit/test_kit.h>

using namespace cloud::config::filedistribution;
using namespace config;
using namespace document;
using namespace proton;
using namespace search::index;
using namespace search;
using namespace vespa::config::search::core;
using namespace vespa::config::search;
using namespace std::chrono_literals;
using vespa::config::content::core::BucketspacesConfig;
using proton::matching::RankingConstants;
using proton::matching::RankingExpressions;
using proton::matching::OnnxModels;

typedef DocumentDBConfigHelper DBCM;
typedef DocumentDBConfig::DocumenttypesConfigSP DocumenttypesConfigSP;
using vespalib::nbostream;

vespalib::string myId("myconfigid");

DocumentDBConfig::SP
makeBaseConfigSnapshot()
{
    ::config::DirSpec spec(TEST_PATH("cfg"));

    DBCM dbcm(spec, "test");
    DocumenttypesConfigSP dtcfg(::config::ConfigGetter<DocumenttypesConfig>::getConfig("", spec).release());
    auto b = std::make_shared<BootstrapConfig>(1, dtcfg,
                                               std::make_shared<DocumentTypeRepo>(*dtcfg),
                                               std::make_shared<ProtonConfig>(),
                                               std::make_shared<FiledistributorrpcConfig>(),
                                               std::make_shared<BucketspacesConfig>(),
                                               std::make_shared<TuneFileDocumentDB>(), HwInfo());
    dbcm.forwardConfig(b);
    dbcm.nextGeneration(0ms);
    DocumentDBConfig::SP snap = dbcm.getConfig();
    snap->setConfigId(myId);
    ASSERT_TRUE(snap);
    return snap;
}

void
saveBaseConfigSnapshot(const DocumentDBConfig &snap, SerialNum num)
{
    FileConfigManager cm("out", myId, snap.getDocTypeName());
    cm.saveConfig(snap, num);
}


DocumentDBConfig::SP
makeEmptyConfigSnapshot()
{
    return test::DocumentDBConfigBuilder(0, std::make_shared<Schema>(), "client", "test").build();
}

void
assertEqualSnapshot(const DocumentDBConfig &exp, const DocumentDBConfig &act)
{
    EXPECT_TRUE(exp.getRankProfilesConfig() == act.getRankProfilesConfig());
    EXPECT_TRUE(exp.getRankingConstants() == act.getRankingConstants());
    EXPECT_TRUE(exp.getRankingExpressions() == act.getRankingExpressions());
    EXPECT_TRUE(exp.getOnnxModels() == act.getOnnxModels());
    EXPECT_EQUAL(0u, exp.getRankingConstants().size());
    EXPECT_EQUAL(0u, exp.getRankingExpressions().size());
    EXPECT_EQUAL(0u, exp.getOnnxModels().size());
    EXPECT_TRUE(exp.getIndexschemaConfig() == act.getIndexschemaConfig());
    EXPECT_TRUE(exp.getAttributesConfig() == act.getAttributesConfig());
    EXPECT_TRUE(exp.getSummaryConfig() == act.getSummaryConfig());
    EXPECT_TRUE(exp.getSummarymapConfig() == act.getSummarymapConfig());
    EXPECT_TRUE(exp.getJuniperrcConfig() == act.getJuniperrcConfig());
    EXPECT_TRUE(exp.getImportedFieldsConfig() == act.getImportedFieldsConfig());
    EXPECT_EQUAL(0u, exp.getImportedFieldsConfig().attribute.size());

    int expTypeCount = 0;
    int actTypeCount = 0;
    exp.getDocumentTypeRepoSP()->forEachDocumentType(*DocumentTypeRepo::makeLambda([&expTypeCount](const DocumentType &) {
        expTypeCount++;
    }));
    act.getDocumentTypeRepoSP()->forEachDocumentType(*DocumentTypeRepo::makeLambda([&actTypeCount](const DocumentType &) {
        actTypeCount++;
    }));
    EXPECT_EQUAL(expTypeCount, actTypeCount);
    EXPECT_TRUE(*exp.getSchemaSP() == *act.getSchemaSP());
    EXPECT_EQUAL(expTypeCount, actTypeCount);
    EXPECT_EQUAL(exp.getConfigId(), act.getConfigId());
}

DocumentDBConfig::SP
addConfigsThatAreNotSavedToDisk(const DocumentDBConfig &cfg)
{
    test::DocumentDBConfigBuilder builder(cfg);
    RankingConstants::Vector constants = {{"my_name", "my_type", "my_path"}};
    builder.rankingConstants(std::make_shared<RankingConstants>(constants));

    auto expr_list = RankingExpressions().add("my_expr", "my_file");
    builder.rankingExpressions(std::make_shared<RankingExpressions>(expr_list));

    OnnxModels::Vector models = {{"my_model_name", "my_model_file"}};
    builder.onnxModels(std::make_shared<OnnxModels>(models));

    ImportedFieldsConfigBuilder importedFields;
    importedFields.attribute.resize(1);
    importedFields.attribute.back().name = "my_name";
    builder.importedFields(std::make_shared<ImportedFieldsConfig>(importedFields));
    return builder.build();
}

TEST_F("requireThatConfigCanBeSavedAndLoaded", DocumentDBConfig::SP(makeBaseConfigSnapshot()))
{

    DocumentDBConfig::SP fullCfg = addConfigsThatAreNotSavedToDisk(*f);
    saveBaseConfigSnapshot(*fullCfg, 20);
    DocumentDBConfig::SP esnap(makeEmptyConfigSnapshot());
    {
        FileConfigManager cm("out", myId, "dummy");
        cm.loadConfig(*esnap, 20, esnap);
    }
    assertEqualSnapshot(*f, *esnap);
}

TEST_F("requireThatConfigCanBeSerializedAndDeserialized", DocumentDBConfig::SP(makeBaseConfigSnapshot()))
{
    saveBaseConfigSnapshot(*f, 30);
    nbostream stream;
    {
        FileConfigManager cm("out", myId, "dummy");
        cm.serializeConfig(30, stream);
    }
    {
        FileConfigManager cm("out", myId, "dummy");
        cm.deserializeConfig(40, stream);
    }
    DocumentDBConfig::SP fsnap(makeEmptyConfigSnapshot());
    {
        FileConfigManager cm("out", myId, "dummy");
        cm.loadConfig(*fsnap, 40, fsnap);
    }
    assertEqualSnapshot(*f, *fsnap);
    EXPECT_EQUAL("dummy", fsnap->getDocTypeName());
}

TEST_F("requireThatConfigCanBeLoadedWithoutExtraConfigsDataFile", DocumentDBConfig::SP(makeBaseConfigSnapshot()))
{
    saveBaseConfigSnapshot(*f, 70);
    EXPECT_FALSE(vespalib::unlink("out/config-70/extraconfigs.dat"));
    DocumentDBConfig::SP esnap(makeEmptyConfigSnapshot());
    {
        FileConfigManager cm("out", myId, "dummy");
        cm.loadConfig(*esnap, 70, esnap);
    }
}


TEST_F("requireThatVisibilityDelayIsPropagated", DocumentDBConfig::SP(makeBaseConfigSnapshot()))
{
    saveBaseConfigSnapshot(*f, 80);
    DocumentDBConfig::SP esnap(makeEmptyConfigSnapshot());
    {
        ProtonConfigBuilder protonConfigBuilder;
        ProtonConfigBuilder::Documentdb ddb;
        ddb.inputdoctypename = "dummy";
        ddb.visibilitydelay = 61.0;
        protonConfigBuilder.documentdb.push_back(ddb);
        protonConfigBuilder.maxvisibilitydelay = 100.0;
        FileConfigManager cm("out", myId, "dummy");
        cm.setProtonConfig(std::make_shared<ProtonConfig>(protonConfigBuilder));
        cm.loadConfig(*esnap, 70, esnap);
    }
    EXPECT_EQUAL(61s, esnap->getMaintenanceConfigSP()->getVisibilityDelay());
}

TEST_MAIN() { TEST_RUN_ALL(); }
