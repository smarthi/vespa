# Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
vespa_define_module(
    DEPENDS
    fastos
    vespalib
    config_cloudconfig

    LIBS
    src/vespa
)

install_fat_java_artifact(configdefinitions)
