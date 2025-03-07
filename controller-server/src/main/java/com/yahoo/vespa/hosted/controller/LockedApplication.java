// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller;

import com.yahoo.config.application.api.DeploymentSpec;
import com.yahoo.config.application.api.ValidationOverrides;
import com.yahoo.config.provision.InstanceName;
import com.yahoo.vespa.curator.Lock;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.ApplicationVersion;
import com.yahoo.vespa.hosted.controller.api.integration.organization.IssueId;
import com.yahoo.vespa.hosted.controller.api.integration.organization.User;
import com.yahoo.vespa.hosted.controller.application.TenantAndApplicationId;
import com.yahoo.vespa.hosted.controller.metric.ApplicationMetrics;

import java.security.PublicKey;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

/**
 * An application that has been locked for modification. Provides methods for modifying an application's fields.
 *
 * @author jonmv
 */
public class LockedApplication {

    private final Lock lock;
    private final TenantAndApplicationId id;
    private final Instant createdAt;
    private final DeploymentSpec deploymentSpec;
    private final ValidationOverrides validationOverrides;
    private final Optional<IssueId> deploymentIssueId;
    private final Optional<IssueId> ownershipIssueId;
    private final Optional<User> owner;
    private final OptionalInt majorVersion;
    private final ApplicationMetrics metrics;
    private final Set<PublicKey> deployKeys;
    private final OptionalLong projectId;
    private final Optional<ApplicationVersion> latestVersion;
    private final SortedSet<ApplicationVersion> versions;
    private final Map<InstanceName, Instance> instances;

    /**
     * Used to create a locked application
     *
     * @param application The application to lock.
     * @param lock The lock for the application.
     */
    LockedApplication(Application application, Lock lock) {
        this(Objects.requireNonNull(lock, "lock cannot be null"), application.id(), application.createdAt(),
             application.deploymentSpec(), application.validationOverrides(),
             application.deploymentIssueId(), application.ownershipIssueId(),
             application.owner(), application.majorVersion(), application.metrics(), application.deployKeys(),
             application.projectId(), application.latestVersion(), application.versions(), application.instances());
    }

    private LockedApplication(Lock lock, TenantAndApplicationId id, Instant createdAt, DeploymentSpec deploymentSpec,
                              ValidationOverrides validationOverrides,
                              Optional<IssueId> deploymentIssueId, Optional<IssueId> ownershipIssueId, Optional<User> owner,
                              OptionalInt majorVersion, ApplicationMetrics metrics, Set<PublicKey> deployKeys,
                              OptionalLong projectId, Optional<ApplicationVersion> latestVersion, SortedSet<ApplicationVersion> versions,
                              Map<InstanceName, Instance> instances) {
        this.lock = lock;
        this.id = id;
        this.createdAt = createdAt;
        this.deploymentSpec = deploymentSpec;
        this.validationOverrides = validationOverrides;
        this.deploymentIssueId = deploymentIssueId;
        this.ownershipIssueId = ownershipIssueId;
        this.owner = owner;
        this.majorVersion = majorVersion;
        this.metrics = metrics;
        this.deployKeys = deployKeys;
        this.projectId = projectId;
        this.latestVersion = latestVersion;
        this.versions = versions;
        this.instances = Map.copyOf(instances);
    }

    /** Returns a read-only copy of this */
    public Application get() {
        return new Application(id, createdAt, deploymentSpec, validationOverrides,
                               deploymentIssueId, ownershipIssueId, owner, majorVersion, metrics, deployKeys,
                               projectId, latestVersion,  versions, instances.values());
    }

    LockedApplication withNewInstance(InstanceName instance) {
        var instances = new HashMap<>(this.instances);
        instances.put(instance, new Instance(id.instance(instance)));
        return new LockedApplication(lock, id, createdAt, deploymentSpec, validationOverrides,
                                     deploymentIssueId, ownershipIssueId, owner, majorVersion, metrics, deployKeys,
                                     projectId, latestVersion, versions, instances);
    }

    public LockedApplication with(InstanceName instance, UnaryOperator<Instance> modification) {
        var instances = new HashMap<>(this.instances);
        instances.put(instance, modification.apply(instances.get(instance)));
        return new LockedApplication(lock, id, createdAt, deploymentSpec, validationOverrides,
                                     deploymentIssueId, ownershipIssueId, owner, majorVersion, metrics, deployKeys,
                                     projectId, latestVersion, versions, instances);
    }

    public LockedApplication without(InstanceName instance) {
        var instances = new HashMap<>(this.instances);
        instances.remove(instance);
        return new LockedApplication(lock, id, createdAt, deploymentSpec, validationOverrides,
                                     deploymentIssueId, ownershipIssueId, owner, majorVersion, metrics, deployKeys,
                                     projectId, latestVersion, versions, instances);
    }

    public LockedApplication withNewSubmission(ApplicationVersion latestVersion) {
        SortedSet<ApplicationVersion> applicationVersions = new TreeSet<>(versions);
        applicationVersions.add(latestVersion);
        return new LockedApplication(lock, id, createdAt, deploymentSpec, validationOverrides,
                                     deploymentIssueId, ownershipIssueId, owner, majorVersion, metrics, deployKeys,
                                     projectId, Optional.of(latestVersion), applicationVersions, instances);
    }

    public LockedApplication withProjectId(OptionalLong projectId) {
        return new LockedApplication(lock, id, createdAt, deploymentSpec, validationOverrides,
                                     deploymentIssueId, ownershipIssueId, owner, majorVersion, metrics, deployKeys,
                                     projectId, latestVersion, versions, instances);
    }

    public LockedApplication withDeploymentIssueId(IssueId issueId) {
        return new LockedApplication(lock, id, createdAt, deploymentSpec, validationOverrides,
                                     Optional.ofNullable(issueId), ownershipIssueId, owner, majorVersion, metrics, deployKeys,
                                     projectId, latestVersion, versions, instances);
    }

    public LockedApplication with(DeploymentSpec deploymentSpec) {
        return new LockedApplication(lock, id, createdAt, deploymentSpec, validationOverrides,
                                     deploymentIssueId, ownershipIssueId, owner, majorVersion, metrics, deployKeys,
                                     projectId, latestVersion, versions, instances);
    }

    public LockedApplication with(ValidationOverrides validationOverrides) {
        return new LockedApplication(lock, id, createdAt, deploymentSpec, validationOverrides,
                                     deploymentIssueId, ownershipIssueId, owner, majorVersion, metrics, deployKeys,
                                     projectId, latestVersion, versions, instances);
    }

    public LockedApplication withOwnershipIssueId(IssueId issueId) {
        return new LockedApplication(lock, id, createdAt, deploymentSpec, validationOverrides,
                                     deploymentIssueId, Optional.of(issueId), owner, majorVersion, metrics, deployKeys,
                                     projectId, latestVersion, versions, instances);
    }

    public LockedApplication withOwner(User owner) {
        return new LockedApplication(lock, id, createdAt, deploymentSpec, validationOverrides,
                                     deploymentIssueId, ownershipIssueId, Optional.of(owner), majorVersion, metrics, deployKeys,
                                     projectId, latestVersion, versions, instances);
    }

    /** Set a major version for this, or set to null to remove any major version override */
    public LockedApplication withMajorVersion(Integer majorVersion) {
        return new LockedApplication(lock, id, createdAt, deploymentSpec, validationOverrides,
                                     deploymentIssueId, ownershipIssueId, owner,
                                     majorVersion == null ? OptionalInt.empty() : OptionalInt.of(majorVersion),
                                     metrics, deployKeys, projectId, latestVersion, versions, instances);
    }

    public LockedApplication with(ApplicationMetrics metrics) {
        return new LockedApplication(lock, id, createdAt, deploymentSpec, validationOverrides,
                                     deploymentIssueId, ownershipIssueId, owner, majorVersion, metrics, deployKeys,
                                     projectId, latestVersion, versions, instances);
    }

    public LockedApplication withDeployKey(PublicKey pemDeployKey) {
        Set<PublicKey> keys = new LinkedHashSet<>(deployKeys);
        keys.add(pemDeployKey);
        return new LockedApplication(lock, id, createdAt, deploymentSpec, validationOverrides,
                                     deploymentIssueId, ownershipIssueId, owner, majorVersion, metrics, keys,
                                     projectId, latestVersion, versions, instances);
    }

    public LockedApplication withoutDeployKey(PublicKey pemDeployKey) {
        Set<PublicKey> keys = new LinkedHashSet<>(deployKeys);
        keys.remove(pemDeployKey);
        return new LockedApplication(lock, id, createdAt, deploymentSpec, validationOverrides,
                                     deploymentIssueId, ownershipIssueId, owner, majorVersion, metrics, keys,
                                     projectId, latestVersion, versions, instances);
    }

    public LockedApplication withoutVersion(ApplicationVersion version) {
        SortedSet<ApplicationVersion> applicationVersions = new TreeSet<>(versions);
        applicationVersions.remove(version);
        return new LockedApplication(lock, id, createdAt, deploymentSpec, validationOverrides,
                deploymentIssueId, ownershipIssueId, owner, majorVersion, metrics, deployKeys,
                projectId, latestVersion, applicationVersions, instances);
    }

    @Override
    public String toString() {
        return "application '" + id + "'";
    }

}
