// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.config.application.api;

import com.yahoo.config.provision.AthenzService;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.InstanceName;
import com.yahoo.config.provision.RegionName;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The deployment spec for an application instance
 *
 * @author bratseth
 */
public class DeploymentInstanceSpec extends DeploymentSpec.Steps {

    /** The maximum number of consecutive days Vespa upgrades are allowed to be blocked */
    private static final int maxUpgradeBlockingDays = 21;

    /** The name of the instance this step deploys */
    private final InstanceName name;

    private final DeploymentSpec.UpgradePolicy upgradePolicy;
    private final DeploymentSpec.UpgradeRevision upgradeRevision;
    private final DeploymentSpec.UpgradeRollout upgradeRollout;
    private final List<DeploymentSpec.ChangeBlocker> changeBlockers;
    private final Optional<String> globalServiceId;
    private final Optional<AthenzService> athenzService;
    private final Notifications notifications;
    private final List<Endpoint> endpoints;

    public DeploymentInstanceSpec(InstanceName name,
                                  List<DeploymentSpec.Step> steps,
                                  DeploymentSpec.UpgradePolicy upgradePolicy,
                                  DeploymentSpec.UpgradeRevision upgradeRevision,
                                  DeploymentSpec.UpgradeRollout upgradeRollout,
                                  List<DeploymentSpec.ChangeBlocker> changeBlockers,
                                  Optional<String> globalServiceId,
                                  Optional<AthenzService> athenzService,
                                  Notifications notifications,
                                  List<Endpoint> endpoints,
                                  Instant now) {
        super(steps);
        this.name = name;
        this.upgradePolicy = upgradePolicy;
        this.upgradeRevision = upgradeRevision;
        this.upgradeRollout = upgradeRollout;
        this.changeBlockers = changeBlockers;
        this.globalServiceId = globalServiceId;
        this.athenzService = athenzService;
        this.notifications = notifications;
        this.endpoints = List.copyOf(endpoints);
        validateZones(new HashSet<>(), new HashSet<>(), this);
        validateEndpoints(steps(), globalServiceId, this.endpoints);
        validateChangeBlockers(changeBlockers, now);
    }

    public InstanceName name() { return name; }

    /**
     * Throws an IllegalArgumentException if any production deployment or test is declared multiple times,
     * or if any production test is declared not after its corresponding deployment.
     *
     * @param deployments previously seen deployments
     * @param tests previously seen tests
     * @param step step whose members to validate
     */
    private static void validateZones(Set<RegionName> deployments, Set<RegionName> tests, DeploymentSpec.Step step) {
        if ( ! step.steps().isEmpty()) {
            Set<RegionName> oldDeployments = Set.copyOf(deployments);
            for (DeploymentSpec.Step nested : step.steps()) {
                Set<RegionName> seenDeployments = new HashSet<>(step.isOrdered() ? deployments : oldDeployments);
                validateZones(seenDeployments, tests, nested);
                deployments.addAll(seenDeployments);
            }
        }
        else if (step.concerns(Environment.prod)) {
            if (step.isTest()) {
                RegionName region = ((DeploymentSpec.DeclaredTest) step).region();
                if ( ! deployments.contains(region))
                    throw new IllegalArgumentException("tests for prod." + region + " must be after the corresponding deployment in deployment.xml");
                if ( ! tests.add(region))
                    throw new IllegalArgumentException("tests for prod." + region + " are listed twice in deployment.xml");
            }
            else {
                RegionName region = ((DeploymentSpec.DeclaredZone) step).region().get();
                if ( ! deployments.add(region))
                    throw new IllegalArgumentException("prod." + region + " is listed twice in deployment.xml");
            }
        }
    }

    /** Throw an IllegalArgumentException if an endpoint refers to a region that is not declared in 'prod' */
    private void validateEndpoints(List<DeploymentSpec.Step> steps, Optional<String> globalServiceId, List<Endpoint> endpoints) {
        if (globalServiceId.isPresent() && ! endpoints.isEmpty()) {
            throw new IllegalArgumentException("Providing both 'endpoints' and 'global-service-id'. Use only 'endpoints'.");
        }

        var stepZones = steps.stream()
                             .flatMap(s -> s.zones().stream())
                             .flatMap(z -> z.region().stream())
                             .collect(Collectors.toSet());

        for (var endpoint : endpoints){
            for (var endpointRegion : endpoint.regions()) {
                if (! stepZones.contains(endpointRegion)) {
                    throw new IllegalArgumentException("Region used in endpoint that is not declared in 'prod': " + endpointRegion);
                }
            }
        }
    }

    private void validateChangeBlockers(List<DeploymentSpec.ChangeBlocker> changeBlockers, Instant now) {
        // Find all possible dates an upgrade block window can start
        Stream<Instant> blockingFrom = changeBlockers.stream()
                                                     .filter(blocker -> blocker.blocksVersions())
                                                     .map(blocker -> blocker.window())
                                                     .map(window -> window.dateRange().start()
                                                                          .map(date -> date.atStartOfDay(window.zone())
                                                                                           .toInstant())
                                                                          .orElse(now))
                                                     .distinct();
        if (!blockingFrom.allMatch(this::canUpgradeWithinDeadline)) {
            throw new IllegalArgumentException("Cannot block Vespa upgrades for longer than " +
                                               maxUpgradeBlockingDays + " consecutive days");
        }
    }

    /** Returns whether this allows upgrade within deadline, relative to given instant */
    private boolean canUpgradeWithinDeadline(Instant instant) {
        instant = instant.truncatedTo(ChronoUnit.HOURS);
        Duration step = Duration.ofHours(1);
        Duration max = Duration.ofDays(maxUpgradeBlockingDays);
        for (Instant current = instant; !canUpgradeAt(current); current = current.plus(step)) {
            Duration blocked = Duration.between(instant, current);
            if (blocked.compareTo(max) > 0) {
                return false;
            }
        }
        return true;
    }

    /** Returns the upgrade policy of this, which is defaultPolicy if none is specified */
    public DeploymentSpec.UpgradePolicy upgradePolicy() { return upgradePolicy; }

    /** Returns the upgrade revision strategy of this, which is separate if none is specified */
    public DeploymentSpec.UpgradeRevision upgradeRevision() { return upgradeRevision; }

    /** Returns the upgrade rollout strategy of this, which is separate if none is specified */
    public DeploymentSpec.UpgradeRollout upgradeRollout() { return upgradeRollout; }

    /** Returns time windows where upgrades are disallowed for these instances */
    public List<DeploymentSpec.ChangeBlocker> changeBlocker() { return changeBlockers; }

    /** Returns the ID of the service to expose through global routing, if present */
    public Optional<String> globalServiceId() { return globalServiceId; }

    /** Returns whether the instances in this step can upgrade at the given instant */
    public boolean canUpgradeAt(Instant instant) {
        return changeBlockers.stream().filter(block -> block.blocksVersions())
                                      .noneMatch(block -> block.window().includes(instant));
    }

    /** Returns whether an application revision change for these instances can occur at the given instant */
    public boolean canChangeRevisionAt(Instant instant) {
        return changeBlockers.stream().filter(block -> block.blocksRevisions())
                             .noneMatch(block -> block.window().includes(instant));
    }

    /** Returns the athenz service for environment/region if configured, defaulting to that of the instance */
    public Optional<AthenzService> athenzService(Environment environment, RegionName region) {
        return zones().stream()
                      .filter(zone -> zone.concerns(environment, Optional.of(region)))
                      .findFirst()
                      .flatMap(DeploymentSpec.DeclaredZone::athenzService)
                      .or(() -> this.athenzService);
    }

    /** Returns the notification configuration of these instances */
    public Notifications notifications() { return notifications; }

    /** Returns the rotations configuration of these instances */
    public List<Endpoint> endpoints() { return endpoints; }

    /** Returns whether this instance deploys to the given zone, either implicitly or explicitly */
    public boolean deploysTo(Environment environment, RegionName region) {
        return zones().stream().anyMatch(zone -> zone.concerns(environment, Optional.of(region)));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DeploymentInstanceSpec other = (DeploymentInstanceSpec) o;
        return globalServiceId.equals(other.globalServiceId) &&
               upgradePolicy == other.upgradePolicy &&
               upgradeRevision == other.upgradeRevision &&
               upgradeRollout == other.upgradeRollout &&
               changeBlockers.equals(other.changeBlockers) &&
               steps().equals(other.steps()) &&
               athenzService.equals(other.athenzService) &&
               notifications.equals(other.notifications) &&
               endpoints.equals(other.endpoints);
    }

    @Override
    public int hashCode() {
        return Objects.hash(globalServiceId, upgradePolicy, upgradeRevision, upgradeRollout, changeBlockers, steps(), athenzService, notifications, endpoints);
    }

    @Override
    public String toString() {
        return "instance '" + name + "'";
    }

}
