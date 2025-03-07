// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.persistence;

import com.yahoo.component.Version;
import com.yahoo.config.application.api.DeploymentSpec;
import com.yahoo.config.application.api.ValidationOverrides;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.InstanceName;
import com.yahoo.config.provision.RegionName;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.security.KeyUtils;
import com.yahoo.slime.ArrayTraverser;
import com.yahoo.slime.Cursor;
import com.yahoo.slime.Inspector;
import com.yahoo.slime.ObjectTraverser;
import com.yahoo.slime.Slime;
import com.yahoo.slime.SlimeUtils;
import com.yahoo.vespa.hosted.controller.Application;
import com.yahoo.vespa.hosted.controller.Instance;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.ApplicationVersion;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.SourceRevision;
import com.yahoo.vespa.hosted.controller.api.integration.organization.IssueId;
import com.yahoo.vespa.hosted.controller.api.integration.organization.User;
import com.yahoo.vespa.hosted.controller.application.AssignedRotation;
import com.yahoo.vespa.hosted.controller.application.Change;
import com.yahoo.vespa.hosted.controller.application.Deployment;
import com.yahoo.vespa.hosted.controller.application.DeploymentActivity;
import com.yahoo.vespa.hosted.controller.application.DeploymentMetrics;
import com.yahoo.vespa.hosted.controller.application.EndpointId;
import com.yahoo.vespa.hosted.controller.application.QuotaUsage;
import com.yahoo.vespa.hosted.controller.application.TenantAndApplicationId;
import com.yahoo.vespa.hosted.controller.metric.ApplicationMetrics;
import com.yahoo.vespa.hosted.controller.routing.rotation.RotationId;
import com.yahoo.vespa.hosted.controller.routing.rotation.RotationState;
import com.yahoo.vespa.hosted.controller.routing.rotation.RotationStatus;

import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Serializes {@link Application}s to/from slime.
 * This class is multithread safe.
 *
 * @author jonmv
 * @author mpolden
 */
public class ApplicationSerializer {

    // WARNING: Since there are multiple servers in a ZooKeeper cluster and they upgrade one by one
    //          (and rewrite all nodes on startup), changes to the serialized format must be made
    //          such that what is serialized on version N+1 can be read by version N:
    //          - ADDING FIELDS: Always ok
    //          - REMOVING FIELDS: Stop reading the field first. Stop writing it on a later version.
    //          - CHANGING THE FORMAT OF A FIELD: Don't do it bro.

    // Application fields
    private static final String idField = "id";
    private static final String createdAtField = "createdAt";
    private static final String deploymentSpecField = "deploymentSpecField";
    private static final String validationOverridesField = "validationOverrides";
    private static final String instancesField = "instances";
    private static final String deployingField = "deployingField";
    private static final String projectIdField = "projectId";
    private static final String latestVersionField = "latestVersion";
    private static final String versionsField = "versions";
    private static final String pinnedField = "pinned";
    private static final String deploymentIssueField = "deploymentIssueId";
    private static final String ownershipIssueIdField = "ownershipIssueId";
    private static final String ownerField = "confirmedOwner";
    private static final String majorVersionField = "majorVersion";
    private static final String writeQualityField = "writeQuality";
    private static final String queryQualityField = "queryQuality";
    private static final String pemDeployKeysField = "pemDeployKeys";
    private static final String assignedRotationClusterField = "clusterId";
    private static final String assignedRotationRotationField = "rotationId";
    private static final String assignedRotationRegionsField = "regions";
    private static final String versionField = "version";

    // Instance fields
    private static final String instanceNameField = "instanceName";
    private static final String deploymentsField = "deployments";
    private static final String deploymentJobsField = "deploymentJobs"; // TODO jonmv: clean up serialisation format
    private static final String assignedRotationsField = "assignedRotations";
    private static final String assignedRotationEndpointField = "endpointId";
    private static final String latestDeployedField = "latestDeployed";

    // Deployment fields
    private static final String zoneField = "zone";
    private static final String environmentField = "environment";
    private static final String regionField = "region";
    private static final String deployTimeField = "deployTime";
    private static final String applicationBuildNumberField = "applicationBuildNumber";
    private static final String applicationPackageRevisionField = "applicationPackageRevision";
    private static final String sourceRevisionField = "sourceRevision";
    private static final String repositoryField = "repositoryField";
    private static final String branchField = "branchField";
    private static final String commitField = "commitField";
    private static final String authorEmailField = "authorEmailField";
    private static final String deployedDirectlyField = "deployedDirectly";
    private static final String compileVersionField = "compileVersion";
    private static final String buildTimeField = "buildTime";
    private static final String sourceUrlField = "sourceUrl";
    private static final String bundleHashField = "bundleHash";
    private static final String lastQueriedField = "lastQueried";
    private static final String lastWrittenField = "lastWritten";
    private static final String lastQueriesPerSecondField = "lastQueriesPerSecond";
    private static final String lastWritesPerSecondField = "lastWritesPerSecond";

    // DeploymentJobs fields
    private static final String jobStatusField = "jobStatus";

    // JobStatus field
    private static final String jobTypeField = "jobType";
    private static final String pausedUntilField = "pausedUntil";

    // Deployment metrics fields
    private static final String deploymentMetricsField = "metrics";
    private static final String deploymentMetricsQPSField = "queriesPerSecond";
    private static final String deploymentMetricsWPSField = "writesPerSecond";
    private static final String deploymentMetricsDocsField = "documentCount";
    private static final String deploymentMetricsQueryLatencyField = "queryLatencyMillis";
    private static final String deploymentMetricsWriteLatencyField = "writeLatencyMillis";
    private static final String deploymentMetricsUpdateTime = "lastUpdated";
    private static final String deploymentMetricsWarningsField = "warnings";

    // RotationStatus fields
    private static final String rotationStatusField = "rotationStatus2";
    private static final String rotationIdField = "rotationId";
    private static final String lastUpdatedField = "lastUpdated";
    private static final String rotationStateField = "state";
    private static final String statusField = "status";

    // Quota usage fields
    private static final String quotaUsageRateField = "quotaUsageRate";

    private static final String deploymentCostField = "cost";

    // ------------------ Serialization

    public Slime toSlime(Application application) {
        Slime slime = new Slime();
        Cursor root = slime.setObject();
        root.setString(idField, application.id().serialized());
        root.setLong(createdAtField, application.createdAt().toEpochMilli());
        root.setString(deploymentSpecField, application.deploymentSpec().xmlForm());
        root.setString(validationOverridesField, application.validationOverrides().xmlForm());
        application.projectId().ifPresent(projectId -> root.setLong(projectIdField, projectId));
        application.deploymentIssueId().ifPresent(jiraIssueId -> root.setString(deploymentIssueField, jiraIssueId.value()));
        application.ownershipIssueId().ifPresent(issueId -> root.setString(ownershipIssueIdField, issueId.value()));
        application.owner().ifPresent(owner -> root.setString(ownerField, owner.username()));
        application.majorVersion().ifPresent(majorVersion -> root.setLong(majorVersionField, majorVersion));
        root.setDouble(queryQualityField, application.metrics().queryServiceQuality());
        root.setDouble(writeQualityField, application.metrics().writeServiceQuality());
        deployKeysToSlime(application.deployKeys(), root.setArray(pemDeployKeysField));
        application.latestVersion().ifPresent(version -> toSlime(version, root.setObject(latestVersionField)));
        versionsToSlime(application, root.setArray(versionsField));
        instancesToSlime(application, root.setArray(instancesField));
        return slime;
    }

    private void instancesToSlime(Application application, Cursor array) {
        for (Instance instance : application.instances().values()) {
            Cursor instanceObject = array.addObject();
            instanceObject.setString(instanceNameField, instance.name().value());
            deploymentsToSlime(instance.deployments().values(), instanceObject.setArray(deploymentsField));
            toSlime(instance.jobPauses(), instanceObject.setObject(deploymentJobsField));
            assignedRotationsToSlime(instance.rotations(), instanceObject);
            toSlime(instance.rotationStatus(), instanceObject.setArray(rotationStatusField));
            toSlime(instance.change(), instanceObject, deployingField);
            instance.latestDeployed().ifPresent(version -> toSlime(version, instanceObject.setObject(latestDeployedField)));
        }
    }

    private void deployKeysToSlime(Set<PublicKey> deployKeys, Cursor array) {
        deployKeys.forEach(key -> array.addString(KeyUtils.toPem(key)));
    }

    private void deploymentsToSlime(Collection<Deployment> deployments, Cursor array) {
        for (Deployment deployment : deployments)
            deploymentToSlime(deployment, array.addObject());
    }

    private void deploymentToSlime(Deployment deployment, Cursor object) {
        zoneIdToSlime(deployment.zone(), object.setObject(zoneField));
        object.setString(versionField, deployment.version().toString());
        object.setLong(deployTimeField, deployment.at().toEpochMilli());
        toSlime(deployment.applicationVersion(), object.setObject(applicationPackageRevisionField));
        deploymentMetricsToSlime(deployment.metrics(), object);
        deployment.activity().lastQueried().ifPresent(instant -> object.setLong(lastQueriedField, instant.toEpochMilli()));
        deployment.activity().lastWritten().ifPresent(instant -> object.setLong(lastWrittenField, instant.toEpochMilli()));
        deployment.activity().lastQueriesPerSecond().ifPresent(value -> object.setDouble(lastQueriesPerSecondField, value));
        deployment.activity().lastWritesPerSecond().ifPresent(value -> object.setDouble(lastWritesPerSecondField, value));
        object.setDouble(quotaUsageRateField, deployment.quota().rate());
        deployment.cost().ifPresent(cost -> object.setDouble(deploymentCostField, cost));
    }

    private void deploymentMetricsToSlime(DeploymentMetrics metrics, Cursor object) {
        Cursor root = object.setObject(deploymentMetricsField);
        root.setDouble(deploymentMetricsQPSField, metrics.queriesPerSecond());
        root.setDouble(deploymentMetricsWPSField, metrics.writesPerSecond());
        root.setDouble(deploymentMetricsDocsField, metrics.documentCount());
        root.setDouble(deploymentMetricsQueryLatencyField, metrics.queryLatencyMillis());
        root.setDouble(deploymentMetricsWriteLatencyField, metrics.writeLatencyMillis());
        metrics.instant().ifPresent(instant -> root.setLong(deploymentMetricsUpdateTime, instant.toEpochMilli()));
        if (!metrics.warnings().isEmpty()) {
            Cursor warningsObject = root.setObject(deploymentMetricsWarningsField);
            metrics.warnings().forEach((warning, count) -> warningsObject.setLong(warning.name(), count));
        }
    }

    private void zoneIdToSlime(ZoneId zone, Cursor object) {
        object.setString(environmentField, zone.environment().value());
        object.setString(regionField, zone.region().value());
    }

    private void versionsToSlime(Application application, Cursor object) {
        application.versions().forEach(version -> toSlime(version, object.addObject()));
    }

    private void toSlime(ApplicationVersion applicationVersion, Cursor object) {
        applicationVersion.buildNumber().ifPresent(number -> object.setLong(applicationBuildNumberField, number));
        applicationVersion.source().ifPresent(source -> toSlime(source, object.setObject(sourceRevisionField)));
        applicationVersion.authorEmail().ifPresent(email -> object.setString(authorEmailField, email));
        applicationVersion.compileVersion().ifPresent(version -> object.setString(compileVersionField, version.toString()));
        applicationVersion.buildTime().ifPresent(time -> object.setLong(buildTimeField, time.toEpochMilli()));
        applicationVersion.sourceUrl().ifPresent(url -> object.setString(sourceUrlField, url));
        applicationVersion.commit().ifPresent(commit -> object.setString(commitField, commit));
        object.setBool(deployedDirectlyField, applicationVersion.isDeployedDirectly());
        applicationVersion.bundleHash().ifPresent(bundleHash -> object.setString(bundleHashField, bundleHash));
    }

    private void toSlime(SourceRevision sourceRevision, Cursor object) {
        object.setString(repositoryField, sourceRevision.repository());
        object.setString(branchField, sourceRevision.branch());
        object.setString(commitField, sourceRevision.commit());
    }

    private void toSlime(Map<JobType, Instant> jobPauses, Cursor cursor) {
        Cursor jobStatusArray = cursor.setArray(jobStatusField);
        jobPauses.forEach((type, until) -> {
            Cursor jobPauseObject = jobStatusArray.addObject();
            jobPauseObject.setString(jobTypeField, type.jobName());
            jobPauseObject.setLong(pausedUntilField, until.toEpochMilli());
        });
    }

    private void toSlime(Change deploying, Cursor parentObject, String fieldName) {
        if (deploying.isEmpty()) return;

        Cursor object = parentObject.setObject(fieldName);
        if (deploying.platform().isPresent())
            object.setString(versionField, deploying.platform().get().toString());
        if (deploying.application().isPresent())
            toSlime(deploying.application().get(), object);
        if (deploying.isPinned())
            object.setBool(pinnedField, true);
    }

    private void toSlime(RotationStatus status, Cursor array) {
        status.asMap().forEach((rotationId, targets) -> {
            Cursor rotationObject = array.addObject();
            rotationObject.setString(rotationIdField, rotationId.asString());
            rotationObject.setLong(lastUpdatedField, targets.lastUpdated().toEpochMilli());
            Cursor statusArray = rotationObject.setArray(statusField);
            targets.asMap().forEach((zone, state) -> {
                Cursor statusObject = statusArray.addObject();
                zoneIdToSlime(zone, statusObject);
                statusObject.setString(rotationStateField, state.name());
            });
        });
    }

    private void assignedRotationsToSlime(List<AssignedRotation> rotations, Cursor parent) {
        var rotationsArray = parent.setArray(assignedRotationsField);
        for (var rotation : rotations) {
            var object = rotationsArray.addObject();
            object.setString(assignedRotationEndpointField, rotation.endpointId().id());
            object.setString(assignedRotationRotationField, rotation.rotationId().asString());
            object.setString(assignedRotationClusterField, rotation.clusterId().value());
            var regionsArray = object.setArray(assignedRotationRegionsField);
            for (var region : rotation.regions()) {
                regionsArray.addString(region.value());
            }
        }
    }

    // ------------------ Deserialization

    public Application fromSlime(byte[] data) {
        return fromSlime(SlimeUtils.jsonToSlime(data));
    }

    private Application fromSlime(Slime slime) {
        Inspector root = slime.get();

        TenantAndApplicationId id = TenantAndApplicationId.fromSerialized(root.field(idField).asString());
        Instant createdAt = SlimeUtils.instant(root.field(createdAtField));
        DeploymentSpec deploymentSpec = DeploymentSpec.fromXml(root.field(deploymentSpecField).asString(), false);
        ValidationOverrides validationOverrides = ValidationOverrides.fromXml(root.field(validationOverridesField).asString());
        Optional<IssueId> deploymentIssueId = SlimeUtils.optionalString(root.field(deploymentIssueField)).map(IssueId::from);
        Optional<IssueId> ownershipIssueId = SlimeUtils.optionalString(root.field(ownershipIssueIdField)).map(IssueId::from);
        Optional<User> owner = SlimeUtils.optionalString(root.field(ownerField)).map(User::from);
        OptionalInt majorVersion = SlimeUtils.optionalInteger(root.field(majorVersionField));
        ApplicationMetrics metrics = new ApplicationMetrics(root.field(queryQualityField).asDouble(),
                                                            root.field(writeQualityField).asDouble());
        Set<PublicKey> deployKeys = deployKeysFromSlime(root.field(pemDeployKeysField));
        List<Instance> instances = instancesFromSlime(id, root.field(instancesField));
        OptionalLong projectId = SlimeUtils.optionalLong(root.field(projectIdField));
        Optional<ApplicationVersion> latestVersion = latestVersionFromSlime(root.field(latestVersionField));
        SortedSet<ApplicationVersion> versions = versionsFromSlime(root.field(versionsField));

        return new Application(id, createdAt, deploymentSpec, validationOverrides,
                               deploymentIssueId, ownershipIssueId, owner, majorVersion, metrics,
                               deployKeys, projectId, latestVersion, versions, instances);
    }

    private Optional<ApplicationVersion> latestVersionFromSlime(Inspector latestVersionObject) {
        return Optional.of(applicationVersionFromSlime(latestVersionObject))
                       .filter(version -> ! version.isUnknown());
    }

    private SortedSet<ApplicationVersion> versionsFromSlime(Inspector versionsObject) {
        SortedSet<ApplicationVersion> versions = new TreeSet<>();
        versionsObject.traverse((ArrayTraverser) (name, object) -> versions.add(applicationVersionFromSlime(object)));
        return versions;
    }

    private List<Instance> instancesFromSlime(TenantAndApplicationId id, Inspector field) {
        List<Instance> instances = new ArrayList<>();
        field.traverse((ArrayTraverser) (name, object) -> {
            InstanceName instanceName = InstanceName.from(object.field(instanceNameField).asString());
            List<Deployment> deployments = deploymentsFromSlime(object.field(deploymentsField));
            Map<JobType, Instant> jobPauses = jobPausesFromSlime(object.field(deploymentJobsField));
            List<AssignedRotation> assignedRotations = assignedRotationsFromSlime(object);
            RotationStatus rotationStatus = rotationStatusFromSlime(object);
            Change change = changeFromSlime(object.field(deployingField));
            Optional<ApplicationVersion> latestDeployed = latestVersionFromSlime(object.field(latestDeployedField));
            instances.add(new Instance(id.instance(instanceName),
                                       deployments,
                                       jobPauses,
                                       assignedRotations,
                                       rotationStatus,
                                       change,
                                       latestDeployed));
        });
        return instances;
    }

    private Set<PublicKey> deployKeysFromSlime(Inspector array) {
        Set<PublicKey> keys = new LinkedHashSet<>();
        array.traverse((ArrayTraverser) (__, key) -> keys.add(KeyUtils.fromPemEncodedPublicKey(key.asString())));
        return keys;
    }

    private List<Deployment> deploymentsFromSlime(Inspector array) {
        List<Deployment> deployments = new ArrayList<>();
        array.traverse((ArrayTraverser) (int i, Inspector item) -> deployments.add(deploymentFromSlime(item)));
        return deployments;
    }

    private Deployment deploymentFromSlime(Inspector deploymentObject) {
        return new Deployment(zoneIdFromSlime(deploymentObject.field(zoneField)),
                              applicationVersionFromSlime(deploymentObject.field(applicationPackageRevisionField)),
                              Version.fromString(deploymentObject.field(versionField).asString()),
                              SlimeUtils.instant(deploymentObject.field(deployTimeField)),
                              deploymentMetricsFromSlime(deploymentObject.field(deploymentMetricsField)),
                              DeploymentActivity.create(SlimeUtils.optionalInstant(deploymentObject.field(lastQueriedField)),
                                                        SlimeUtils.optionalInstant(deploymentObject.field(lastWrittenField)),
                                                        SlimeUtils.optionalDouble(deploymentObject.field(lastQueriesPerSecondField)),
                                                        SlimeUtils.optionalDouble(deploymentObject.field(lastWritesPerSecondField))),
                              QuotaUsage.create(SlimeUtils.optionalDouble(deploymentObject.field(quotaUsageRateField))),
                              SlimeUtils.optionalDouble(deploymentObject.field(deploymentCostField)));
    }

    private DeploymentMetrics deploymentMetricsFromSlime(Inspector object) {
        Optional<Instant> instant = SlimeUtils.optionalInstant(object.field(deploymentMetricsUpdateTime));
        return new DeploymentMetrics(object.field(deploymentMetricsQPSField).asDouble(),
                                     object.field(deploymentMetricsWPSField).asDouble(),
                                     object.field(deploymentMetricsDocsField).asDouble(),
                                     object.field(deploymentMetricsQueryLatencyField).asDouble(),
                                     object.field(deploymentMetricsWriteLatencyField).asDouble(),
                                     instant,
                                     deploymentWarningsFrom(object.field(deploymentMetricsWarningsField)));
    }

    private Map<DeploymentMetrics.Warning, Integer> deploymentWarningsFrom(Inspector object) {
        Map<DeploymentMetrics.Warning, Integer> warnings = new HashMap<>();
        object.traverse((ObjectTraverser) (name, value) -> warnings.put(DeploymentMetrics.Warning.valueOf(name),
                                                                        (int) value.asLong()));
        return Collections.unmodifiableMap(warnings);
    }

    private RotationStatus rotationStatusFromSlime(Inspector parentObject) {
        var object = parentObject.field(rotationStatusField);
        var statusMap = new LinkedHashMap<RotationId, RotationStatus.Targets>();
        object.traverse((ArrayTraverser) (idx, statusObject) -> statusMap.put(new RotationId(statusObject.field(rotationIdField).asString()),
                                                                              new RotationStatus.Targets(
                                                                                      singleRotationStatusFromSlime(statusObject.field(statusField)),
                                                                                      SlimeUtils.instant(statusObject.field(lastUpdatedField)))));
        return RotationStatus.from(statusMap);
    }

    private Map<ZoneId, RotationState> singleRotationStatusFromSlime(Inspector object) {
        if (!object.valid()) {
            return Collections.emptyMap();
        }
        Map<ZoneId, RotationState> rotationStatus = new LinkedHashMap<>();
        object.traverse((ArrayTraverser) (idx, statusObject) -> {
            var zone = zoneIdFromSlime(statusObject);
            var status = RotationState.valueOf(statusObject.field(rotationStateField).asString());
            rotationStatus.put(zone, status);
        });
        return Collections.unmodifiableMap(rotationStatus);
    }

    private ZoneId zoneIdFromSlime(Inspector object) {
        return ZoneId.from(object.field(environmentField).asString(), object.field(regionField).asString());
    }

    private ApplicationVersion applicationVersionFromSlime(Inspector object) {
        if ( ! object.valid()) return ApplicationVersion.unknown;
        OptionalLong applicationBuildNumber = SlimeUtils.optionalLong(object.field(applicationBuildNumberField));
        if (applicationBuildNumber.isEmpty())
            return ApplicationVersion.unknown;

        Optional<SourceRevision> sourceRevision = sourceRevisionFromSlime(object.field(sourceRevisionField));
        Optional<String> authorEmail = SlimeUtils.optionalString(object.field(authorEmailField));
        Optional<Version> compileVersion = SlimeUtils.optionalString(object.field(compileVersionField)).map(Version::fromString);
        Optional<Instant> buildTime = SlimeUtils.optionalInstant(object.field(buildTimeField));
        Optional<String> sourceUrl = SlimeUtils.optionalString(object.field(sourceUrlField));
        Optional<String> commit = SlimeUtils.optionalString(object.field(commitField));
        boolean deployedDirectly = object.field(deployedDirectlyField).asBool();
        Optional<String> bundleHash = SlimeUtils.optionalString(object.field(bundleHashField));

        return new ApplicationVersion(sourceRevision, applicationBuildNumber, authorEmail, compileVersion, buildTime, sourceUrl, commit, deployedDirectly, bundleHash);
    }

    private Optional<SourceRevision> sourceRevisionFromSlime(Inspector object) {
        if ( ! object.valid()) return Optional.empty();
        return Optional.of(new SourceRevision(object.field(repositoryField).asString(),
                                              object.field(branchField).asString(),
                                              object.field(commitField).asString()));
    }

    private Map<JobType, Instant> jobPausesFromSlime(Inspector object) {
        Map<JobType, Instant> jobPauses = new HashMap<>();
        object.field(jobStatusField).traverse((ArrayTraverser) (__, jobPauseObject) ->
                JobType.fromOptionalJobName(jobPauseObject.field(jobTypeField).asString())
                       .ifPresent(jobType -> jobPauses.put(jobType,
                                                           SlimeUtils.instant(jobPauseObject.field(pausedUntilField)))));
        return jobPauses;
    }

    private Change changeFromSlime(Inspector object) {
        if ( ! object.valid()) return Change.empty();
        Inspector versionFieldValue = object.field(versionField);
        Change change = Change.empty();
        if (versionFieldValue.valid())
            change = Change.of(Version.fromString(versionFieldValue.asString()));
        if (object.field(applicationBuildNumberField).valid())
            change = change.with(applicationVersionFromSlime(object));
        if (object.field(pinnedField).asBool())
            change = change.withPin();
        return change;
    }

    private List<AssignedRotation> assignedRotationsFromSlime(Inspector root) {
        var assignedRotations = new LinkedHashMap<EndpointId, AssignedRotation>();
        root.field(assignedRotationsField).traverse((ArrayTraverser) (i, inspector) -> {
            var clusterId = new ClusterSpec.Id(inspector.field(assignedRotationClusterField).asString());
            var endpointId = EndpointId.of(inspector.field(assignedRotationEndpointField).asString());
            var rotationId = new RotationId(inspector.field(assignedRotationRotationField).asString());
            var regions = new LinkedHashSet<RegionName>();
            inspector.field(assignedRotationRegionsField).traverse((ArrayTraverser) (j, regionInspector) -> {
                regions.add(RegionName.from(regionInspector.asString()));
            });
            assignedRotations.putIfAbsent(endpointId, new AssignedRotation(clusterId, endpointId, rotationId, regions));
        });

        return List.copyOf(assignedRotations.values());
    }

}
