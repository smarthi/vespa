// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.maintenance;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.vespa.flags.Flags;
import com.yahoo.vespa.flags.InMemoryFlagSource;
import com.yahoo.vespa.hosted.controller.ControllerTester;
import com.yahoo.vespa.hosted.controller.api.integration.certificates.EndpointCertificateMetadata;
import com.yahoo.vespa.hosted.controller.api.integration.certificates.EndpointCertificateMock;
import com.yahoo.vespa.hosted.controller.deployment.ApplicationPackageBuilder;
import com.yahoo.vespa.hosted.controller.deployment.DeploymentContext;
import com.yahoo.vespa.hosted.controller.deployment.DeploymentTester;
import com.yahoo.vespa.hosted.controller.integration.SecretStoreMock;
import com.yahoo.yolean.concurrent.Sleeper;
import org.junit.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.productionUsWest1;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.stagingTest;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.systemTest;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @author andreer
 */
public class EndpointCertificateMaintainerTest {

    private final ControllerTester tester = new ControllerTester();
    private final SecretStoreMock secretStore = (SecretStoreMock) tester.controller().secretStore();
    private final EndpointCertificateMaintainer maintainer = new EndpointCertificateMaintainer(tester.controller(), Duration.ofHours(1));
    private final EndpointCertificateMetadata exampleMetadata = new EndpointCertificateMetadata("keyName", "certName", 0, 0, "uuid", List.of(), "issuer", Optional.empty(), Optional.empty());
    {
        ((InMemoryFlagSource) tester.controller().flagSource()).withBooleanFlag(Flags.DELETE_UNMAINTAINED_CERTIFICATES.id(), true);
    }

    @Test
    public void old_and_unused_cert_is_deleted() {
        tester.curator().writeEndpointCertificateMetadata(ApplicationId.defaultId(), exampleMetadata);
        assertEquals(1.0, maintainer.maintain(), 0.0000001);
        assertTrue(tester.curator().readEndpointCertificateMetadata(ApplicationId.defaultId()).isEmpty());
    }

    @Test
    public void unused_but_recently_used_cert_is_not_deleted() {
        EndpointCertificateMetadata recentlyRequestedCert = exampleMetadata.withLastRequested(tester.clock().instant().minusSeconds(3600).getEpochSecond());
        tester.curator().writeEndpointCertificateMetadata(ApplicationId.defaultId(), recentlyRequestedCert);
        assertEquals(1.0, maintainer.maintain(), 0.0000001);
        assertEquals(Optional.of(recentlyRequestedCert), tester.curator().readEndpointCertificateMetadata(ApplicationId.defaultId()));
    }

    @Test
    public void refreshed_certificate_is_updated() {
        EndpointCertificateMetadata recentlyRequestedCert = exampleMetadata.withLastRequested(tester.clock().instant().minusSeconds(3600).getEpochSecond());
        tester.curator().writeEndpointCertificateMetadata(ApplicationId.defaultId(), recentlyRequestedCert);

        secretStore.setSecret(exampleMetadata.keyName(), "foo", 1);
        secretStore.setSecret(exampleMetadata.certName(), "bar", 1);

        assertEquals(1.0, maintainer.maintain(), 0.0000001);

        var updatedCert = Optional.of(recentlyRequestedCert.withLastRefreshed(tester.clock().instant().getEpochSecond()).withVersion(1));

        assertEquals(updatedCert, tester.curator().readEndpointCertificateMetadata(ApplicationId.defaultId()));
    }

    @Test
    public void certificate_in_use_is_not_deleted() {
        var appId = ApplicationId.from("tenant", "application", "default");

        DeploymentTester deploymentTester = new DeploymentTester(tester);

        var applicationPackage = new ApplicationPackageBuilder()
                .region("us-west-1")
                .build();

        DeploymentContext deploymentContext = deploymentTester.newDeploymentContext("tenant", "application", "default");

        deploymentContext.submit(applicationPackage).runJob(systemTest).runJob(stagingTest).runJob(productionUsWest1);


        tester.curator().writeEndpointCertificateMetadata(appId, exampleMetadata);

        assertEquals(1.0, maintainer.maintain(), 0.0000001);
        assertTrue(tester.curator().readEndpointCertificateMetadata(appId).isPresent()); // cert should not be deleted, the app is deployed!
    }

    @Test
    public void refreshed_certificate_is_deployed_after_four_days() {
        var appId = ApplicationId.from("tenant", "application", "default");

        DeploymentTester deploymentTester = new DeploymentTester(tester);

        var applicationPackage = new ApplicationPackageBuilder()
                .region("us-west-1")
                .build();

        DeploymentContext deploymentContext = deploymentTester.newDeploymentContext("tenant", "application", "default");

        deploymentContext.submit(applicationPackage).runJob(systemTest).runJob(stagingTest).runJob(productionUsWest1);

        tester.curator().writeEndpointCertificateMetadata(appId, exampleMetadata);

        assertEquals(1.0, maintainer.maintain(), 0.0000001);
        assertTrue(tester.curator().readEndpointCertificateMetadata(appId).isPresent()); // cert should not be deleted, the app is deployed!

        tester.clock().advance(Duration.ofDays(3));

        secretStore.setSecret(exampleMetadata.keyName(), "foo", 1);
        secretStore.setSecret(exampleMetadata.certName(), "bar", 1);

        maintainer.maintain();

        tester.clock().advance(Duration.ofDays(4));

        deploymentContext.assertNotRunning(productionUsWest1);

        maintainer.maintain();

        deploymentContext.assertRunning(productionUsWest1);
    }

    @Test
    public void unmaintained_cert_is_deleted() {
        EndpointCertificateMock endpointCertificateProvider = (EndpointCertificateMock) tester.controller().serviceRegistry().endpointCertificateProvider();

        ApplicationId unknown = ApplicationId.fromSerializedForm("applicationid:is:unknown");
        endpointCertificateProvider.requestCaSignedCertificate(unknown, List.of("a", "b", "c"), Optional.empty()); // Unknown to controller!

        assertEquals(1.0, maintainer.maintain(), 0.0000001);

        assertTrue(endpointCertificateProvider.dnsNamesOf(unknown).isEmpty());
        assertTrue(endpointCertificateProvider.listCertificates().isEmpty());
    }
}
