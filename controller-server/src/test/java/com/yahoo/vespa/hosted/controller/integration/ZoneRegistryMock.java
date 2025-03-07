// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.integration;

import com.yahoo.component.AbstractComponent;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.AthenzDomain;
import com.yahoo.config.provision.CloudName;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.NodeType;
import com.yahoo.config.provision.RegionName;
import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.TenantName;
import com.yahoo.config.provision.zone.RoutingMethod;
import com.yahoo.config.provision.zone.UpgradePolicy;
import com.yahoo.config.provision.zone.ZoneApi;
import com.yahoo.config.provision.zone.ZoneFilter;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.text.Text;
import com.yahoo.vespa.athenz.api.AthenzIdentity;
import com.yahoo.vespa.athenz.api.AthenzService;
import com.yahoo.vespa.hosted.controller.api.identifiers.DeploymentId;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.RunId;
import com.yahoo.vespa.hosted.controller.api.integration.zone.ZoneRegistry;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * @author mpolden
 */
public class ZoneRegistryMock extends AbstractComponent implements ZoneRegistry {

    private final Map<ZoneId, Duration> deploymentTimeToLive = new HashMap<>();
    private final Map<Environment, RegionName> defaultRegionForEnvironment = new HashMap<>();
    private final Map<CloudName, UpgradePolicy> osUpgradePolicies = new HashMap<>();
    private final Map<ZoneApi, List<RoutingMethod>> zoneRoutingMethods = new HashMap<>();
    private final Set<ZoneApi> reprovisionToUpgradeOs = new HashSet<>();

    private List<? extends ZoneApi> zones;
    private SystemName system;
    private UpgradePolicy upgradePolicy = null;

    /**
     * This sets the default list of zones contained in this. If your test need a particular set of zones, use
     * {@link #setZones(List)}  instead of changing the default set.}
     */
    public ZoneRegistryMock(SystemName system) {
        this.system = system;
        if (system.isPublic()) {
            this.zones = List.of(ZoneApiMock.fromId("test.aws-us-east-1c"),
                                 ZoneApiMock.fromId("staging.aws-us-east-1c"),
                                 ZoneApiMock.fromId("prod.aws-us-east-1c"),
                                 ZoneApiMock.fromId("prod.aws-eu-west-1a"));
            setRoutingMethod(this.zones, RoutingMethod.exclusive);
        } else {
            this.zones = List.of(ZoneApiMock.fromId("test.us-east-1"),
                                 ZoneApiMock.fromId("staging.us-east-3"),
                                 ZoneApiMock.fromId("dev.us-east-1"),
                                 ZoneApiMock.fromId("dev.aws-us-east-2a"),
                                 ZoneApiMock.fromId("perf.us-east-3"),
                                 ZoneApiMock.fromId("prod.aws-us-east-1a"),
                                 ZoneApiMock.fromId("prod.ap-northeast-1"),
                                 ZoneApiMock.fromId("prod.ap-northeast-2"),
                                 ZoneApiMock.fromId("prod.ap-southeast-1"),
                                 ZoneApiMock.fromId("prod.us-east-3"),
                                 ZoneApiMock.fromId("prod.us-west-1"),
                                 ZoneApiMock.fromId("prod.us-central-1"),
                                 ZoneApiMock.fromId("prod.eu-west-1"));
            setRoutingMethod(this.zones, RoutingMethod.sharedLayer4);
        }
    }

    public ZoneRegistryMock setDeploymentTimeToLive(ZoneId zone, Duration duration) {
        deploymentTimeToLive.put(zone, duration);
        return this;
    }

    public ZoneRegistryMock setDefaultRegionForEnvironment(Environment environment, RegionName region) {
        defaultRegionForEnvironment.put(environment, region);
        return this;
    }

    public ZoneRegistryMock setZones(List<? extends ZoneApi> zones) {
        this.zones = zones;
        return this;
    }

    public ZoneRegistryMock setZones(ZoneApi... zone) {
        return setZones(List.of(zone));
    }

    public ZoneRegistryMock setSystemName(SystemName system) {
        this.system = system;
        return this;
    }

    public ZoneRegistryMock setUpgradePolicy(UpgradePolicy upgradePolicy) {
        this.upgradePolicy = upgradePolicy;
        return this;
    }

    public ZoneRegistryMock setOsUpgradePolicy(CloudName cloud, UpgradePolicy upgradePolicy) {
        osUpgradePolicies.put(cloud, upgradePolicy);
        return this;
    }

    public ZoneRegistryMock exclusiveRoutingIn(ZoneApi... zones) {
        return exclusiveRoutingIn(List.of(zones));
    }

    public ZoneRegistryMock exclusiveRoutingIn(List<? extends ZoneApi> zones) {
        return setRoutingMethod(zones, RoutingMethod.exclusive);
    }

    public ZoneRegistryMock setRoutingMethod(ZoneApi zone, RoutingMethod... routingMethods) {
        return setRoutingMethod(zone, Set.of(routingMethods));
    }

    public ZoneRegistryMock setRoutingMethod(List<? extends ZoneApi> zones, RoutingMethod... routingMethods) {
        zones.forEach(zone -> setRoutingMethod(zone, Set.of(routingMethods)));
        return this;
    }

    private ZoneRegistryMock setRoutingMethod(ZoneApi zone, Set<RoutingMethod> routingMethods) {
        this.zoneRoutingMethods.put(zone, List.copyOf(routingMethods));
        return this;
    }

    public ZoneRegistryMock reprovisionToUpgradeOsIn(ZoneApi... zones) {
        return reprovisionToUpgradeOsIn(List.of(zones));
    }

    public ZoneRegistryMock reprovisionToUpgradeOsIn(List<ZoneApi> zones) {
        this.reprovisionToUpgradeOs.addAll(zones);
        return this;
    }

    @Override
    public SystemName system() {
        return system;
    }

    @Override
    public ZoneApi systemZone() {
        return ZoneApiMock.fromId("prod.controller");
    }

    @Override
    public ZoneFilter zones() {
        return ZoneFilterMock.from(zones, zoneRoutingMethods, reprovisionToUpgradeOs);
    }

    @Override
    public AthenzService getConfigServerHttpsIdentity(ZoneId zone) {
        return new AthenzService("vespadomain", "provider-" + zone.environment().value() + "-" + zone.region().value());
    }

    @Override
    public AthenzIdentity getNodeAthenzIdentity(ZoneId zoneId, NodeType nodeType) {
        return new AthenzService("vespadomain", "servicename");
    }

    @Override
    public AthenzDomain accessControlDomain() {
        return AthenzDomain.from("vespadomain");
    }

    @Override
    public UpgradePolicy upgradePolicy() {
        return upgradePolicy;
    }

    @Override
    public UpgradePolicy osUpgradePolicy(CloudName cloud) {
        return osUpgradePolicies.get(cloud);
    }

    @Override
    public List<UpgradePolicy> osUpgradePolicies() {
        return List.copyOf(osUpgradePolicies.values());
    }

    @Override
    public List<RoutingMethod> routingMethods(ZoneId zone) {
        return List.copyOf(zoneRoutingMethods.getOrDefault(ZoneApiMock.from(zone), List.of()));
    }

    @Override
    public URI dashboardUrl() {
        return URI.create("https://dashboard.tld");
    }

    @Override
    public URI dashboardUrl(ApplicationId id) {
        return URI.create("https://dashboard.tld/" + id);
    }

    @Override
    public URI dashboardUrl(RunId id) {
        return URI.create("https://dashboard.tld/" + id.application() + "/" + id.type().jobName() + "/" + id.number());
    }

    @Override
    public URI supportUrl() {
        return URI.create("https://help.tld");
    }

    @Override
    public URI apiUrl() {
        return URI.create("https://api.tld:4443/");
    }

    @Override public Optional<String> tenantDeveloperRoleArn(TenantName tenant) { return Optional.empty(); }

    @Override
    public boolean hasZone(ZoneId zoneId) {
        return zones.stream().anyMatch(zone -> zone.getId().equals(zoneId));
    }

    @Override
    public URI getConfigServerVipUri(ZoneId zoneId) {
        return URI.create(Text.format("https://cfg.%s.test.vip:4443/", zoneId.value()));
    }

    @Override
    public Optional<String> getVipHostname(ZoneId zoneId) {
        if (routingMethods(zoneId).stream().anyMatch(RoutingMethod::isShared)) {
            return Optional.of("vip." + zoneId.value());
        }
        return Optional.empty();
    }

    @Override
    public Optional<Duration> getDeploymentTimeToLive(ZoneId zoneId) {
        return Optional.ofNullable(deploymentTimeToLive.get(zoneId));
    }

    @Override
    public Optional<RegionName> getDefaultRegion(Environment environment) {
        return Optional.ofNullable(defaultRegionForEnvironment.get(environment));
    }

    @Override
    public URI getMonitoringSystemUri(DeploymentId deploymentId) {
        return URI.create("http://monitoring-system.test/?environment=" + deploymentId.zoneId().environment().value() + "&region="
                          + deploymentId.zoneId().region().value() + "&application=" + deploymentId.applicationId().toShortString());
    }

}
