// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.maintenance;

import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.TenantName;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.jdisc.test.MockMetric;
import com.yahoo.vespa.hosted.controller.ControllerTester;
import com.yahoo.vespa.hosted.controller.LockedTenant;
import com.yahoo.vespa.hosted.controller.api.integration.archive.ArchiveBucket;
import com.yahoo.vespa.hosted.controller.api.integration.archive.MockArchiveService;
import com.yahoo.vespa.hosted.controller.tenant.Tenant;
import org.junit.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * @author andreer
 */
public class ArchiveAccessMaintainerTest {

    @Test
    public void grantsRoleAccess() {
        var tester = new ControllerTester(SystemName.Public);

        String tenant1role = "arn:aws:iam::123456789012:role/my-role";
        String tenant2role = "arn:aws:iam::210987654321:role/my-role";
        var tenant1 = createTenantWithAccessRole(tester, "tenant1", tenant1role);
        createTenantWithAccessRole(tester, "tenant2", tenant2role);

        ZoneId testZone = ZoneId.from("prod.aws-us-east-1c");
        tester.controller().archiveBucketDb().archiveUriFor(testZone, tenant1, true);
        var testBucket = new ArchiveBucket("bucketName", "keyArn").withTenant(tenant1);

        MockArchiveService archiveService = (MockArchiveService) tester.controller().serviceRegistry().archiveService();
        assertNull(archiveService.authorizedIamRolesForBucket.get(testBucket));
        assertNull(archiveService.authorizedIamRolesForKey.get(testBucket.keyArn()));
        MockMetric metric = new MockMetric();
        new ArchiveAccessMaintainer(tester.controller(), metric, Duration.ofMinutes(10)).maintain();
        assertEquals(Map.of(tenant1, tenant1role), archiveService.authorizedIamRolesForBucket.get(testBucket));
        assertEquals(Set.of(tenant1role), archiveService.authorizedIamRolesForKey.get(testBucket.keyArn()));

        var expected = Map.of("archive.bucketCount",
                tester.controller().zoneRegistry().zones().all().ids().stream()
                        .collect(Collectors.toMap(
                                zone -> Map.of("zone", zone.value()),
                                zone -> zone.equals(testZone) ? 1d : 0d)));

        assertEquals(expected, metric.metrics());
    }

    private TenantName createTenantWithAccessRole(ControllerTester tester, String tenantName, String role) {
        var tenant = tester.createTenant(tenantName, Tenant.Type.cloud);
        tester.controller().tenants().lockOrThrow(tenant, LockedTenant.Cloud.class, lockedTenant -> {
            lockedTenant = lockedTenant.withArchiveAccessRole(Optional.of(role));
            tester.controller().tenants().store(lockedTenant);
        });
        return tenant;
    }
}
