// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.api.integration.resource;

import com.yahoo.config.provision.ApplicationName;
import com.yahoo.config.provision.TenantName;
import com.yahoo.vespa.hosted.controller.api.integration.billing.Plan;
import com.yahoo.vespa.hosted.controller.api.integration.billing.PlanRegistry;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * @author olaa
 */
public class ResourceDatabaseClientMock implements ResourceDatabaseClient {

    PlanRegistry planRegistry;
    Map<TenantName, Plan> planMap = new HashMap<>();
    List<ResourceSnapshot> resourceSnapshots = new ArrayList<>();
    private boolean hasRefreshedMaterializedView = false;

    public ResourceDatabaseClientMock(PlanRegistry planRegistry) {
        this.planRegistry = planRegistry;
    }

    @Override
    public void writeResourceSnapshots(Collection<ResourceSnapshot> items) {
        this.resourceSnapshots.addAll(items);
    }

    @Override
    public List<ResourceSnapshot> getResourceSnapshotsForMonth(TenantName tenantName, ApplicationName applicationName, YearMonth month) {
        return resourceSnapshots.stream()
                .filter(resourceSnapshot -> {
                    LocalDate snapshotDate = LocalDate.ofInstant(resourceSnapshot.getTimestamp(), ZoneId.of("UTC"));
                    return YearMonth.from(snapshotDate).equals(month) &&
                            snapshotDate.getYear() == month.getYear() &&
                            resourceSnapshot.getApplicationId().tenant().equals(tenantName) &&
                            resourceSnapshot.getApplicationId().application().equals(applicationName);
                })
                .collect(Collectors.toList());
    }

    @Override
    public Set<YearMonth> getMonthsWithSnapshotsForTenant(TenantName tenantName) {
        return Collections.emptySet();
    }

    @Override
    public List<ResourceSnapshot> getRawSnapshotHistoryForTenant(TenantName tenantName, YearMonth yearMonth) {
        return resourceSnapshots;
    }

    @Override
    public Set<TenantName> getTenants() {
        return resourceSnapshots.stream()
                .map(snapshot -> snapshot.getApplicationId().tenant())
                .collect(Collectors.toSet());
    }

    private List<ResourceUsage> resourceUsageFromSnapshots(Plan plan, List<ResourceSnapshot> snapshots) {
        snapshots.sort(Comparator.comparing(ResourceSnapshot::getTimestamp));

        return IntStream.range(0, snapshots.size())
                .mapToObj(idx -> {
                    var a = snapshots.get(idx);
                    var b = (idx + 1) < snapshots.size() ? snapshots.get(idx + 1) : null;
                    var start = a.getTimestamp();
                    var end   = Optional.ofNullable(b).map(ResourceSnapshot::getTimestamp).orElse(start.plusSeconds(120));
                    var d = BigDecimal.valueOf(Duration.between(start, end).toMillis());
                    return new ResourceUsage(
                            a.getApplicationId(),
                            a.getZoneId(),
                            plan,
                            BigDecimal.valueOf(a.getCpuCores()).multiply(d),
                            BigDecimal.valueOf(a.getMemoryGb()).multiply(d),
                            BigDecimal.valueOf(a.getDiskGb()).multiply(d)
                    );
                })
                .collect(Collectors.toList());
    }

    private ResourceUsage resourceUsageAdd(ResourceUsage a, ResourceUsage b) {
        assert a.getApplicationId().equals(b.getApplicationId());
        assert a.getZoneId().equals(b.getZoneId());
        assert a.getPlan().equals(b.getPlan());
        return new ResourceUsage(
                a.getApplicationId(),
                a.getZoneId(),
                a.getPlan(),
                a.getCpuMillis().add(b.getCpuMillis()),
                a.getMemoryMillis().add(b.getMemoryMillis()),
                a.getDiskMillis().add(b.getDiskMillis())
        );
    }

    @Override
    public List<ResourceUsage> getResourceSnapshotsForPeriod(TenantName tenantName, long start, long end) {
        var tenantPlan = planMap.getOrDefault(tenantName, planRegistry.defaultPlan());

        var snapshotsPerDeployment = resourceSnapshots.stream()
                .filter(snapshot -> snapshot.getTimestamp().isAfter(Instant.ofEpochMilli(start)))
                .filter(snapshot -> snapshot.getTimestamp().isBefore(Instant.ofEpochMilli(end)))
                .filter(snapshot -> snapshot.getApplicationId().tenant().equals(tenantName))
                .collect(Collectors.groupingBy(
                        usage -> Objects.hash(usage.getApplicationId(), usage.getZoneId(), tenantPlan.id().value())
                ))
                .values().stream()
                .map(snapshots -> resourceUsageFromSnapshots(tenantPlan, snapshots))
                .map(usages -> usages.stream().reduce(this::resourceUsageAdd))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());

        return snapshotsPerDeployment;
    }

    @Override
    public void refreshMaterializedView() {
        hasRefreshedMaterializedView = true;
    }

    public void setPlan(TenantName tenant, Plan plan) {
        planMap.put(tenant, plan);
    }

    public boolean hasRefreshedMaterializedView() {
        return hasRefreshedMaterializedView;
    }
}
