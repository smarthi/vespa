// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.builder;

import com.yahoo.config.model.deploy.ConfigDefinitionStore;
import com.yahoo.config.application.api.DeployLogger;
import com.yahoo.config.model.producer.UserConfigRepo;
import java.util.logging.Level;
import com.yahoo.text.XML;
import com.yahoo.vespa.config.*;
import com.yahoo.vespa.model.builder.xml.dom.DomConfigPayloadBuilder;
import org.w3c.dom.Element;

import java.util.*;
import java.util.logging.Logger;

/**
 * @author Ulf Lilleengen
 * @since 5.1
 */
public class UserConfigBuilder {

    public static final Logger log = Logger.getLogger(UserConfigBuilder.class.getPackage().toString());

    public static UserConfigRepo build(Element producerSpec, ConfigDefinitionStore configDefinitionStore, DeployLogger deployLogger) {
        final Map<ConfigDefinitionKey, ConfigPayloadBuilder> builderMap = new LinkedHashMap<>();
        if (producerSpec == null) {
            log.log(Level.FINEST, "In getUserConfigs. producerSpec is null");
        }
        log.log(Level.FINE, () -> "getUserConfigs for " + producerSpec);
        for (Element configE : XML.getChildren(producerSpec, "config")) {
            buildElement(configE, builderMap, configDefinitionStore, deployLogger);
        }
        return new UserConfigRepo(builderMap);
    }


    private static void buildElement(Element element, Map<ConfigDefinitionKey, ConfigPayloadBuilder> builderMap, 
                                     ConfigDefinitionStore configDefinitionStore, DeployLogger logger) {
        ConfigDefinitionKey key = DomConfigPayloadBuilder.parseConfigName(element);

        Optional<ConfigDefinition> def = configDefinitionStore.getConfigDefinition(key);
        if ( ! def.isPresent()) { // TODO: Fail instead of warn
            logger.logApplicationPackage(Level.WARNING, "Unable to find config definition '" + key.asFileName() +
                                         "'. Please ensure that the name is spelled correctly, and that the def file is included in a bundle.");
        }
        ConfigPayloadBuilder payloadBuilder = new DomConfigPayloadBuilder(def.orElse(null), logger).build(element);
        ConfigPayloadBuilder old = builderMap.get(key);
        if (old != null) {
            logger.logApplicationPackage(Level.WARNING, "Multiple overrides for " + key + " found. Applying in the order they are discovered");
            old.override(payloadBuilder);
        } else {
            builderMap.put(key, payloadBuilder);
        }
    }

}

