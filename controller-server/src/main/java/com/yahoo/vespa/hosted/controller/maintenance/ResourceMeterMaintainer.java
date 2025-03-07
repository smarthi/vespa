// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.maintenance;

import com.yahoo.concurrent.UncheckedTimeoutException;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ClusterResources;
import com.yahoo.config.provision.InstanceName;
import com.yahoo.config.provision.NodeResources;
import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.zone.ZoneApi;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.jdisc.Metric;
import com.yahoo.vespa.hosted.controller.ApplicationController;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.api.integration.configserver.Node;
import com.yahoo.vespa.hosted.controller.api.integration.configserver.NodeFilter;
import com.yahoo.vespa.hosted.controller.api.integration.configserver.NodeRepository;
import com.yahoo.vespa.hosted.controller.api.integration.resource.MeteringClient;
import com.yahoo.vespa.hosted.controller.api.integration.resource.ResourceAllocation;
import com.yahoo.vespa.hosted.controller.api.integration.resource.ResourceSnapshot;
import com.yahoo.vespa.hosted.controller.application.SystemApplication;
import com.yahoo.vespa.hosted.controller.application.TenantAndApplicationId;
import com.yahoo.vespa.hosted.controller.persistence.CuratorDb;
import com.yahoo.yolean.Exceptions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Creates a {@link ResourceSnapshot} per application, which is then passed on to a MeteringClient
 *
 * @author olaa
 */
public class ResourceMeterMaintainer extends ControllerMaintainer {

    /**
     * Checks if the node is in some state where it is in active use by the tenant,
     * and not transitioning out of use, in a failed state, etc.
     */
    private static final Set<Node.State> METERABLE_NODE_STATES = EnumSet.of(
            Node.State.reserved,   // an application will soon use this node
            Node.State.active,     // an application is currently using this node
            Node.State.inactive    // an application is not using it, but it is reserved for being re-introduced or decommissioned
    );

    private final ApplicationController applications;
    private final NodeRepository nodeRepository;
    private final MeteringClient meteringClient;
    private final CuratorDb curator;
    private final SystemName systemName;
    private final Metric metric;
    private final Clock clock;

    private static final String METERING_LAST_REPORTED = "metering_last_reported";
    private static final String METERING_TOTAL_REPORTED = "metering_total_reported";
    private static final int METERING_REFRESH_INTERVAL_SECONDS = 1800;

    @SuppressWarnings("WeakerAccess")
    public ResourceMeterMaintainer(Controller controller,
                                   Duration interval,
                                   Metric metric,
                                   MeteringClient meteringClient) {
        super(controller, interval);
        this.applications = controller.applications();
        this.nodeRepository = controller.serviceRegistry().configServer().nodeRepository();
        this.meteringClient = meteringClient;
        this.curator = controller.curator();
        this.systemName = controller.serviceRegistry().zoneRegistry().system();
        this.metric = metric;
        this.clock = controller.clock();
    }

    @Override
    protected double maintain() {
        Collection<ResourceSnapshot> resourceSnapshots;
        try {
            resourceSnapshots = getAllResourceSnapshots();
        } catch (Exception e) {
            log.log(Level.WARNING, "Failed to collect resource snapshots. Retrying in " + interval() + ". Error: " +
                                   Exceptions.toMessageString(e));
            return 0.0;
        }

        if (systemName.isPublic()) reportResourceSnapshots(resourceSnapshots);
        updateDeploymentCost(resourceSnapshots);
        return 1.0;
    }

    void updateDeploymentCost(Collection<ResourceSnapshot> resourceSnapshots) {
        resourceSnapshots.stream()
                .collect(Collectors.groupingBy(snapshot -> TenantAndApplicationId.from(snapshot.getApplicationId()),
                         Collectors.groupingBy(snapshot -> snapshot.getApplicationId().instance())))
                .forEach(this::updateDeploymentCost);
    }

    private void updateDeploymentCost(TenantAndApplicationId tenantAndApplication, Map<InstanceName, List<ResourceSnapshot>> snapshotsByInstance) {
        try {
            applications.lockApplicationIfPresent(tenantAndApplication, locked -> {
                for (InstanceName instanceName : locked.get().instances().keySet()) {
                    Map<ZoneId, Double> deploymentCosts = snapshotsByInstance.getOrDefault(instanceName, List.of()).stream()
                            .collect(Collectors.toUnmodifiableMap(
                                    ResourceSnapshot::getZoneId,
                                    snapshot -> cost(snapshot.allocation(), systemName)));
                    locked = locked.with(instanceName, i -> i.withDeploymentCosts(deploymentCosts));
                    updateCostMetrics(tenantAndApplication.instance(instanceName), deploymentCosts);
                }
                applications.store(locked);
            });
        } catch (UncheckedTimeoutException ignored) {
            // Will be retried on next maintenance, avoid throwing so we can update the other apps instead
        }
    }

    private void reportResourceSnapshots(Collection<ResourceSnapshot> resourceSnapshots) {
        meteringClient.consume(resourceSnapshots);

        updateMeteringMetrics(resourceSnapshots);

        try (var lock = curator.lockMeteringRefreshTime()) {
            if (needsRefresh(curator.readMeteringRefreshTime())) {
                meteringClient.refresh();
                curator.writeMeteringRefreshTime(clock.millis());
            }
        } catch (TimeoutException ignored) {
            // If it's locked, it means we're currently refreshing
        }
    }

    private List<ResourceSnapshot> getAllResourceSnapshots() {
        return controller().zoneRegistry().zones()
                .reachable().zones().stream()
                .map(ZoneApi::getId)
                .map(zoneId -> createResourceSnapshotsFromNodes(zoneId, nodeRepository.list(zoneId, NodeFilter.all())))
                .flatMap(Collection::stream)
                .collect(Collectors.toList());
    }

    private Collection<ResourceSnapshot> createResourceSnapshotsFromNodes(ZoneId zoneId, List<Node> nodes) {
        return nodes.stream()
                .filter(this::unlessNodeOwnerIsSystemApplication)
                .filter(this::isNodeStateMeterable)
                .filter(this::isClusterTypeMeterable)
                .collect(Collectors.groupingBy(node ->
                                node.owner().get(),
                                Collectors.collectingAndThen(Collectors.toList(),
                                        nodeList -> ResourceSnapshot.from(
                                                nodeList,
                                                clock.instant(),
                                                zoneId))
                                )).values();
    }

    private boolean unlessNodeOwnerIsSystemApplication(Node node) {
        return node.owner()
                   .map(owner -> !owner.tenant().equals(SystemApplication.TENANT))
                   .orElse(false);
    }

    private boolean isNodeStateMeterable(Node node) {
        return METERABLE_NODE_STATES.contains(node.state());
    }

    private boolean isClusterTypeMeterable(Node node) {
        return node.clusterType() != Node.ClusterType.admin; // log servers and shared cluster controllers
    }

    private boolean needsRefresh(long lastRefreshTimestamp) {
        return clock.instant()
                .minusSeconds(METERING_REFRESH_INTERVAL_SECONDS)
                .isAfter(Instant.ofEpochMilli(lastRefreshTimestamp));
    }

    public static double cost(ClusterResources clusterResources, SystemName systemName) {
        NodeResources nr = clusterResources.nodeResources();
        return cost(new ResourceAllocation(nr.vcpu(), nr.memoryGb(), nr.diskGb()).multiply(clusterResources.nodes()), systemName);
    }

    private static double cost(ResourceAllocation allocation, SystemName systemName) {
        // Divide cost by 3 in non-public zones to show approx. AWS equivalent cost
        double costDivisor = systemName.isPublic() ? 1.0 : 3.0;
        double cost = new NodeResources(allocation.getCpuCores(), allocation.getMemoryGb(), allocation.getDiskGb(), 0).cost();
        return Math.round(cost * 100.0 / costDivisor) / 100.0;
    }

    private void updateMeteringMetrics(Collection<ResourceSnapshot> resourceSnapshots) {
        metric.set(METERING_LAST_REPORTED, clock.millis() / 1000, metric.createContext(Collections.emptyMap()));
        // total metered resource usage, for alerting on drastic changes
        metric.set(METERING_TOTAL_REPORTED,
                resourceSnapshots.stream()
                        .mapToDouble(r -> r.getCpuCores() + r.getMemoryGb() + r.getDiskGb()).sum(),
                metric.createContext(Collections.emptyMap()));

        resourceSnapshots.forEach(snapshot -> {
            var context = getMetricContext(snapshot.getApplicationId(), snapshot.getZoneId());
            metric.set("metering.vcpu", snapshot.getCpuCores(), context);
            metric.set("metering.memoryGB", snapshot.getMemoryGb(), context);
            metric.set("metering.diskGB", snapshot.getDiskGb(), context);
        });
    }

    private void updateCostMetrics(ApplicationId applicationId, Map<ZoneId, Double> deploymentCost) {
        deploymentCost.forEach((zoneId, cost) -> {
            var context = getMetricContext(applicationId, zoneId);
            metric.set("metering.cost.hourly", cost, context);
        });
    }

    private Metric.Context getMetricContext(ApplicationId applicationId, ZoneId zoneId) {
        return metric.createContext(Map.of(
                "tenant", applicationId.tenant().value(),
                "applicationId", applicationId.toFullString(),
                "zoneId", zoneId.value()
        ));
    }
}
