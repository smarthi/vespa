// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.container.di;

import com.google.inject.Injector;
import com.yahoo.config.ConfigInstance;
import com.yahoo.config.ConfigurationRuntimeException;
import com.yahoo.config.subscription.ConfigInterruptedException;
import com.yahoo.config.subscription.SubscriberClosedException;
import com.yahoo.container.ComponentsConfig;
import com.yahoo.container.bundle.BundleInstantiationSpecification;
import com.yahoo.container.di.ConfigRetriever.BootstrapConfigs;
import com.yahoo.container.di.ConfigRetriever.ComponentsConfigs;
import com.yahoo.container.di.ConfigRetriever.ConfigSnapshot;
import com.yahoo.container.di.componentgraph.core.ComponentGraph;
import com.yahoo.container.di.componentgraph.core.ComponentNode;
import com.yahoo.container.di.componentgraph.core.Node;
import com.yahoo.container.di.config.ApplicationBundlesConfig;
import com.yahoo.container.di.config.PlatformBundlesConfig;
import com.yahoo.container.di.config.SubscriberFactory;
import com.yahoo.vespa.config.ConfigKey;
import com.yahoo.yolean.UncheckedInterruptedException;
import org.osgi.framework.Bundle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;

/**
 * @author gjoranv
 * @author Tony Vaagenes
 * @author ollivir
 */
public class Container {

    private static final Logger log = Logger.getLogger(Container.class.getName());

    private final SubscriberFactory subscriberFactory;
    private final ConfigKey<ApplicationBundlesConfig> applicationBundlesConfigKey;
    private final ConfigKey<PlatformBundlesConfig> platformBundlesConfigKey;
    private final ConfigKey<ComponentsConfig> componentsConfigKey;
    private final ComponentDeconstructor destructor;
    private final Osgi osgi;

    private final ConfigRetriever retriever;
    private List<String> platformBundles;  // Used to verify that platform bundles don't change.
    private long previousConfigGeneration = -1L;
    private long leastGeneration = -1L;

    public Container(SubscriberFactory subscriberFactory, String configId, ComponentDeconstructor destructor, Osgi osgi) {
        this.subscriberFactory = subscriberFactory;
        this.destructor = destructor;
        this.osgi = osgi;

        applicationBundlesConfigKey = new ConfigKey<>(ApplicationBundlesConfig.class, configId);
        platformBundlesConfigKey = new ConfigKey<>(PlatformBundlesConfig.class, configId);
        componentsConfigKey = new ConfigKey<>(ComponentsConfig.class, configId);
        var bootstrapKeys = Set.of(applicationBundlesConfigKey, platformBundlesConfigKey, componentsConfigKey);
        this.retriever = new ConfigRetriever(bootstrapKeys, subscriberFactory);
    }

    public Container(SubscriberFactory subscriberFactory, String configId, ComponentDeconstructor destructor) {
        this(subscriberFactory, configId, destructor, new Osgi() {
        });
    }

    public ComponentGraphResult waitForNextGraphGeneration(ComponentGraph oldGraph, Injector fallbackInjector, boolean isInitializing) {
        try {
            Collection<Bundle> obsoleteBundles = new HashSet<>();
            ComponentGraph newGraph = waitForNewConfigGenAndCreateGraph(oldGraph, fallbackInjector, isInitializing, obsoleteBundles);
            newGraph.reuseNodes(oldGraph);
            try {
                constructComponents(newGraph);
            } catch (Exception e) {
                log.log(Level.WARNING, String.format(
                        "Failed to construct graph for generation '%d' - scheduling partial graph for deconstruction",
                        newGraph.generation()), e);
                deconstructFailedGraph(oldGraph, newGraph);
                throw e;
            }
            Runnable cleanupTask = createPreviousGraphDeconstructionTask(oldGraph, newGraph, obsoleteBundles);
            return new ComponentGraphResult(newGraph, cleanupTask);
        } catch (Throwable t) {
            invalidateGeneration(oldGraph.generation(), t);
            throw t;
        }
    }

    private ComponentGraph waitForNewConfigGenAndCreateGraph(
            ComponentGraph graph, Injector fallbackInjector, boolean isInitializing, Collection<Bundle> obsoleteBundles) // NOTE: Return value
    {
        ConfigSnapshot snapshot;
        while (true) {
            snapshot = retriever.getConfigs(graph.configKeys(), leastGeneration, isInitializing);

            if (log.isLoggable(FINE))
                log.log(FINE, String.format("getConfigAndCreateGraph:\n" + "graph.configKeys = %s\n" + "graph.generation = %s\n" + "snapshot = %s\n",
                                            graph.configKeys(), graph.generation(), snapshot));

            if (snapshot instanceof BootstrapConfigs) {
                if (getBootstrapGeneration() <= previousConfigGeneration) {
                    throw new IllegalStateException(String.format(
                            "Got bootstrap configs out of sequence for old config generation %d.\n" + "Previous config generation is %d",
                            getBootstrapGeneration(), previousConfigGeneration));
                }
                log.log(FINE, () -> "Got new bootstrap generation\n" + configGenerationsString());

                if (graph.generation() == 0) {
                    platformBundles = getConfig(platformBundlesConfigKey, snapshot.configs()).bundlePaths();
                    osgi.installPlatformBundles(platformBundles);
                } else {
                    throwIfPlatformBundlesChanged(snapshot);
                }
                Collection<Bundle> bundlesToRemove = installApplicationBundles(snapshot.configs());
                obsoleteBundles.addAll(bundlesToRemove);

                graph = createComponentsGraph(snapshot.configs(), getBootstrapGeneration(), fallbackInjector);

                // Continues loop

            } else if (snapshot instanceof ComponentsConfigs) {
                break;
            }
        }
        log.log(FINE, () -> "Got components configs,\n" + configGenerationsString());
        return createAndConfigureComponentsGraph(snapshot.configs(), fallbackInjector);
    }

    private long getBootstrapGeneration() {
        return retriever.getBootstrapGeneration();
    }

    private long getComponentsGeneration() {
        return retriever.getComponentsGeneration();
    }

    private String configGenerationsString() {
        return String.format("bootstrap generation = %d\n" + "components generation: %d\n" + "previous generation: %d",
                             getBootstrapGeneration(), getComponentsGeneration(), previousConfigGeneration);
    }

    private void throwIfPlatformBundlesChanged(ConfigSnapshot snapshot) {
        var checkPlatformBundles = getConfig(platformBundlesConfigKey, snapshot.configs()).bundlePaths();
        if (! checkPlatformBundles.equals(platformBundles))
            throw new RuntimeException("Platform bundles are not allowed to change!\nOld: " + platformBundles + "\nNew: " + checkPlatformBundles);
    }

    private ComponentGraph createAndConfigureComponentsGraph(Map<ConfigKey<? extends ConfigInstance>, ConfigInstance> componentsConfigs,
                                                             Injector fallbackInjector) {
        ComponentGraph componentGraph = createComponentsGraph(componentsConfigs, getComponentsGeneration(), fallbackInjector);
        componentGraph.setAvailableConfigs(componentsConfigs);
        return componentGraph;
    }

    private void constructComponents(ComponentGraph graph) {
        graph.nodes().forEach(n -> {
            if (Thread.interrupted())
                throw new UncheckedInterruptedException("Interrupted while constructing component graph", true);
            n.constructInstance();
        });
    }

    private void deconstructFailedGraph(ComponentGraph currentGraph, ComponentGraph failedGraph) {
        Set<Object> currentComponents = Collections.newSetFromMap(new IdentityHashMap<>(currentGraph.size()));
        currentComponents.addAll(currentGraph.allConstructedComponentsAndProviders());

        List<Object> unusedComponents = new ArrayList<>();
        for (Object component : failedGraph.allConstructedComponentsAndProviders()) {
            if (!currentComponents.contains(component)) unusedComponents.add(component);
        }
        destructor.deconstruct(failedGraph.generation(), unusedComponents, List.of());
    }

    private Runnable createPreviousGraphDeconstructionTask(ComponentGraph oldGraph,
                                                           ComponentGraph newGraph,
                                                           Collection<Bundle> obsoleteBundles) {
        Map<Object, ?> newComponents = new IdentityHashMap<>(newGraph.size());
        for (Object component : newGraph.allConstructedComponentsAndProviders())
            newComponents.put(component, null);

        List<Object> obsoleteComponents = new ArrayList<>();
        for (Object component : oldGraph.allConstructedComponentsAndProviders())
            if ( ! newComponents.containsKey(component))
                obsoleteComponents.add(component);

        return () -> destructor.deconstruct(oldGraph.generation(), obsoleteComponents, obsoleteBundles);
    }

    private Set<Bundle> installApplicationBundles(Map<ConfigKey<? extends ConfigInstance>, ConfigInstance> configsIncludingBootstrapConfigs) {
        ApplicationBundlesConfig applicationBundlesConfig = getConfig(applicationBundlesConfigKey, configsIncludingBootstrapConfigs);
        return osgi.useApplicationBundles(applicationBundlesConfig.bundles());
    }

    private ComponentGraph createComponentsGraph(Map<ConfigKey<? extends ConfigInstance>, ConfigInstance> configsIncludingBootstrapConfigs,
                                                 long generation, Injector fallbackInjector) {
        previousConfigGeneration = generation;

        ComponentGraph graph = new ComponentGraph(generation);
        ComponentsConfig componentsConfig = getConfig(componentsConfigKey, configsIncludingBootstrapConfigs);
        if (componentsConfig == null) {
            throw new ConfigurationRuntimeException("The set of all configs does not include a valid 'components' config. Config set: "
                    + configsIncludingBootstrapConfigs.keySet());
        }
        addNodes(componentsConfig, graph);
        injectNodes(componentsConfig, graph);

        graph.complete(fallbackInjector);
        return graph;
    }

    private void addNodes(ComponentsConfig componentsConfig, ComponentGraph graph) {

        for (ComponentsConfig.Components config : componentsConfig.components()) {
            BundleInstantiationSpecification specification = bundleInstantiationSpecification(config);
            Class<?> componentClass = osgi.resolveClass(specification);
            Node componentNode = new ComponentNode(specification.id, config.configId(), componentClass, null);
            graph.add(componentNode);
        }
    }

    private void injectNodes(ComponentsConfig config, ComponentGraph graph) {
        for (ComponentsConfig.Components component : config.components()) {
            Node componentNode = ComponentGraph.getNode(graph, component.id());

            for (ComponentsConfig.Components.Inject inject : component.inject()) {
                //TODO: Support inject.name()
                componentNode.inject(ComponentGraph.getNode(graph, inject.id()));
            }
        }
    }

    @SuppressWarnings("removal") // TODO Vespa 8: remove
    private void invalidateGeneration(long generation, Throwable cause) {
        leastGeneration = Math.max(retriever.getComponentsGeneration(), retriever.getBootstrapGeneration()) + 1;
        if (!(cause instanceof InterruptedException) && !(cause instanceof ConfigInterruptedException) && !(cause instanceof SubscriberClosedException)) {
            log.log(Level.WARNING, newGraphErrorMessage(generation, cause), cause);
        }
    }

    private static String newGraphErrorMessage(long generation, Throwable cause) {
        String failedFirstMessage = "Failed to set up first component graph";
        String failedNewMessage = "Failed to set up new component graph";
        String constructMessage = " due to error when constructing one of the components";
        String retainMessage = ". Retaining previous component generation.";

        if (generation == 0) {
            if (cause instanceof ComponentNode.ComponentConstructorException) {
                return failedFirstMessage + constructMessage;
            } else {
                return failedFirstMessage;
            }
        } else {
            if (cause instanceof ComponentNode.ComponentConstructorException) {
                return failedNewMessage + constructMessage + retainMessage;
            } else {
                return failedNewMessage + retainMessage;
            }
        }
    }

    public void shutdown(ComponentGraph graph) {
        shutdownConfigRetriever();
        if (graph != null) {
            scheduleGraphForDeconstruction(graph);
            destructor.shutdown();
        }
    }

    public void shutdownConfigRetriever() {
        retriever.shutdown();
    }

    // Reload config manually, when subscribing to non-configserver sources
    public void reloadConfig(long generation) {
        subscriberFactory.reloadActiveSubscribers(generation);
    }

    private void scheduleGraphForDeconstruction(ComponentGraph graph) {
        // This is only used for shutdown and cleanup of failed graph, so no need to uninstall any bundles.
        destructor.deconstruct(graph.generation(), graph.allConstructedComponentsAndProviders(), List.of());
    }

    public static <T extends ConfigInstance> T getConfig(ConfigKey<T> key,
                                                         Map<ConfigKey<? extends ConfigInstance>, ConfigInstance> configs) {
        ConfigInstance inst = configs.get(key);

        if (inst == null || key.getConfigClass() == null) {
            throw new RuntimeException("Missing config " + key);
        }

        return key.getConfigClass().cast(inst);
    }

    private static BundleInstantiationSpecification bundleInstantiationSpecification(ComponentsConfig.Components config) {
        return BundleInstantiationSpecification.getFromStrings(config.id(), config.classId(), config.bundle());
    }

    public static class ComponentGraphResult {
        private final ComponentGraph newGraph;
        private final Runnable oldComponentsCleanupTask;

        public ComponentGraphResult(ComponentGraph newGraph, Runnable oldComponentsCleanupTask) {
            this.newGraph = newGraph;
            this.oldComponentsCleanupTask = oldComponentsCleanupTask;
        }

        public ComponentGraph newGraph() { return newGraph; }
        public Runnable oldComponentsCleanupTask() { return oldComponentsCleanupTask; }
    }

}
