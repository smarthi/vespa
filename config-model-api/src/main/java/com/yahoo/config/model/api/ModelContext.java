// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.config.model.api;

import com.yahoo.component.Version;
import com.yahoo.config.application.api.ApplicationPackage;
import com.yahoo.config.application.api.DeployLogger;
import com.yahoo.config.application.api.FileRegistry;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.AthenzDomain;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.DockerImage;
import com.yahoo.config.provision.HostName;
import com.yahoo.config.provision.Zone;

import java.io.File;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Model context containing state provided to model factories.
 *
 * @author Ulf Lilleengen
 */
public interface ModelContext {

    ApplicationPackage applicationPackage();
    Optional<Model> previousModel();
    Optional<ApplicationPackage> permanentApplicationPackage();
    HostProvisioner getHostProvisioner();
    Provisioned provisioned();
    DeployLogger deployLogger();
    ConfigDefinitionRepo configDefinitionRepo();
    FileRegistry getFileRegistry();
    ExecutorService getExecutor();
    default Optional<? extends Reindexing> reindexing() { return Optional.empty(); }
    Properties properties();
    default Optional<File> appDir() { return Optional.empty();}

    /** The Docker image repo we want to use for images for this deployment (optional, will use default if empty) */
    default Optional<DockerImage> wantedDockerImageRepo() { return Optional.empty(); }

    /** The Vespa version this model is built for */
    Version modelVespaVersion();

    /** The Vespa version we want nodes to become */
    Version wantedNodeVespaVersion();

    /**
     * How to remove a temporary feature flags:
     * 1)
     * - Remove flag definition from Flags
     * - Remove method implementation from ModelContextImpl.FeatureFlags
     * - Modify default implementation of below method to return the new default value
     * - Remove all usage of below method from config-model
     *
     * 2)
     * - (optional) Track Vespa version that introduced changes from 1) in annotation field 'removeAfter'
     *
     * 3)
     *  - Remove below method once all config-model versions in hosted production include changes from 1)
     *  - Remove all flag data files from hosted-feature-flag repository
     */
    interface FeatureFlags {
        @ModelFeatureFlag(owners = {"baldersheim"}, comment = "Revisit in May or June 2021") default double defaultTermwiseLimit() { throw new UnsupportedOperationException("TODO specify default value"); }
        @ModelFeatureFlag(owners = {"vekterli"}) default boolean useThreePhaseUpdates() { throw new UnsupportedOperationException("TODO specify default value"); }
        @ModelFeatureFlag(owners = {"baldersheim"}, comment = "Select sequencer type use while feeding") default String feedSequencerType() { return "THROUGHPUT"; }
        @ModelFeatureFlag(owners = {"geirst, baldersheim"}) default int feedTaskLimit() { return 1000; }
        @ModelFeatureFlag(owners = {"geirst, baldersheim"}) default int feedMasterTaskLimit() { return 1000; }
        @ModelFeatureFlag(owners = {"geirst, baldersheim"}) default String sharedFieldWriterExecutor() { return "NONE"; }
        @ModelFeatureFlag(owners = {"baldersheim"}) default String responseSequencerType() { throw new UnsupportedOperationException("TODO specify default value"); }
        @ModelFeatureFlag(owners = {"baldersheim"}) default int defaultNumResponseThreads() { return 2; }
        @ModelFeatureFlag(owners = {"baldersheim"}) default boolean skipCommunicationManagerThread() { throw new UnsupportedOperationException("TODO specify default value"); }
        @ModelFeatureFlag(owners = {"baldersheim"}) default boolean skipMbusRequestThread() { throw new UnsupportedOperationException("TODO specify default value"); }
        @ModelFeatureFlag(owners = {"baldersheim"}) default boolean skipMbusReplyThread() { throw new UnsupportedOperationException("TODO specify default value"); }
        @ModelFeatureFlag(owners = {"baldersheim"}) default boolean useAsyncMessageHandlingOnSchedule() { throw new UnsupportedOperationException("TODO specify default value"); }
        @ModelFeatureFlag(owners = {"baldersheim"}) default double feedConcurrency() { throw new UnsupportedOperationException("TODO specify default value"); }
        @ModelFeatureFlag(owners = {"baldersheim"}) default int defaultPoolNumThreads() { return 2; }
        @ModelFeatureFlag(owners = {"baldersheim"}) default int availableProcessors() { return 2; }
        @ModelFeatureFlag(owners = {"baldersheim"}) default int maxUnCommittedMemory() { return 130000; }
        @ModelFeatureFlag(owners = {"baldersheim"}) default int maxConcurrentMergesPerNode() { return 16; }
        @ModelFeatureFlag(owners = {"baldersheim"}) default int maxMergeQueueSize() { return 100; }
        @ModelFeatureFlag(owners = {"baldersheim"}) default boolean containerDumpHeapOnShutdownTimeout() { throw new UnsupportedOperationException("TODO specify default value"); }
        @ModelFeatureFlag(owners = {"baldersheim"}) default double containerShutdownTimeout() { throw new UnsupportedOperationException("TODO specify default value"); }
        @ModelFeatureFlag(owners = {"geirst"}, removeAfter = "7.541") default boolean enableFeedBlockInDistributor() { return true; }
        @ModelFeatureFlag(owners = {"bjorncs", "tokle"}) default List<String> allowedAthenzProxyIdentities() { return List.of(); }
        @ModelFeatureFlag(owners = {"vekterli"}) default int maxActivationInhibitedOutOfSyncGroups() { return 0; }
        @ModelFeatureFlag(owners = {"hmusum"}) default String jvmOmitStackTraceInFastThrowOption(ClusterSpec.Type type) { return ""; }
        @ModelFeatureFlag(owners = {"hmusum"}) default double resourceLimitDisk() { return 0.75; }
        @ModelFeatureFlag(owners = {"hmusum"}) default double resourceLimitMemory() { return 0.8; }
        @ModelFeatureFlag(owners = {"geirst", "vekterli"}) default double minNodeRatioPerGroup() { return 0.0; }
        @ModelFeatureFlag(owners = {"arnej"}) default boolean forwardIssuesAsErrors() { return true; }
        @ModelFeatureFlag(owners = {"arnej"}) default boolean ignoreThreadStackSizes() { return false; }
        @ModelFeatureFlag(owners = {"vekterli", "geirst"}) default boolean unorderedMergeChaining() { return true; }
        @ModelFeatureFlag(owners = {"arnej"}) default boolean useV8GeoPositions() { return false; }
        @ModelFeatureFlag(owners = {"arnej", "baldersheim"}) default boolean useV8DocManagerCfg() { return false; }
        @ModelFeatureFlag(owners = {"baldersheim", "geirst", "toregge"}) default int maxCompactBuffers() { return 1; }
        @ModelFeatureFlag(owners = {"hmusum"}) default boolean failDeploymentWithInvalidJvmOptions() { return false; }
        @ModelFeatureFlag(owners = {"arnej", "andreer"}) default List<String> ignoredHttpUserAgents() { return List.of(); }
        @ModelFeatureFlag(owners = {"bjorncs"}) default boolean enableServerOcspStapling() { return false; }
        @ModelFeatureFlag(owners = {"vekterli"}) default String persistenceAsyncThrottling() { throw new UnsupportedOperationException("TODO specify default value"); }
        @ModelFeatureFlag(owners = {"vekterli"}) default String mergeThrottlingPolicy() { throw new UnsupportedOperationException("TODO specify default value"); }
        @ModelFeatureFlag(owners = {"vekterli"}) default double persistenceThrottlingWsDecrementFactor() { throw new UnsupportedOperationException("TODO specify default value"); }
        @ModelFeatureFlag(owners = {"vekterli"}) default double persistenceThrottlingWsBackoff() { throw new UnsupportedOperationException("TODO specify default value"); }
        @ModelFeatureFlag(owners = {"geirst", "vekterli"}) default boolean inhibitDefaultMergesWhenGlobalMergesPending() { return false; }
        @ModelFeatureFlag(owners = {"arnej"}) default boolean useQrserverServiceName() { return true; }
        @ModelFeatureFlag(owners = {"bjorncs", "baldersheim"}) default boolean enableJdiscPreshutdownCommand() { return true; }
    }

    /** Warning: As elsewhere in this package, do not make backwards incompatible changes that will break old config models! */
    interface Properties {
        FeatureFlags featureFlags();
        boolean multitenant();
        ApplicationId applicationId();
        List<ConfigServerSpec> configServerSpecs();
        HostName loadBalancerName();
        URI ztsUrl();
        String athenzDnsSuffix();
        boolean hostedVespa();
        Zone zone();
        Set<ContainerEndpoint> endpoints();
        boolean isBootstrap();
        boolean isFirstTimeDeployment();

        default Optional<EndpointCertificateSecrets> endpointCertificateSecrets() { return Optional.empty(); }

        default Optional<AthenzDomain> athenzDomain() { return Optional.empty(); }

        // applicationRoles is no longer in use and should be removed. Replaced by AwsEnvironment. Remove after 7.458
        default Optional<ApplicationRoles> applicationRoles() { return Optional.empty(); }

        default Quota quota() { return Quota.unlimited(); }

        default List<TenantSecretStore> tenantSecretStores() { return List.of(); }

        // Default setting for the gc-options attribute if not specified explicit by application
        default String jvmGCOptions() { return jvmGCOptions(Optional.empty()); }

        // Default setting for the gc-options attribute if not specified explicit by application
        String jvmGCOptions(Optional<ClusterSpec.Type> clusterType);

        // Note: Used in unit tests (set to false in TestProperties) to avoid needing to deal with implicitly created node for logserver
        default boolean useDedicatedNodeForLogserver() { return true; }

        // Allow disabling mTLS for now, harden later
        default boolean allowDisableMtls() { return true; }

        default List<X509Certificate> operatorCertificates() { return List.of(); }

        default List<String> tlsCiphersOverride() { return List.of(); }

        default List<String> zoneDnsSuffixes() { return List.of(); }
        List<String> environmentVariables();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface ModelFeatureFlag {
        String[] owners();
        String removeAfter() default ""; // On the form "7.100.10"
        String comment() default "";
    }

}
