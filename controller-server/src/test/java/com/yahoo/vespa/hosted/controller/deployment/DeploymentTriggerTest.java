// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.deployment;

import com.yahoo.component.Version;
import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.zone.RoutingMethod;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.ApplicationVersion;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.RunId;
import com.yahoo.vespa.hosted.controller.application.pkg.ApplicationPackage;
import com.yahoo.vespa.hosted.controller.application.Change;
import com.yahoo.vespa.hosted.controller.versions.VespaVersion;
import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Collectors;

import static com.yahoo.config.provision.SystemName.main;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.productionApNortheast1;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.productionApNortheast2;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.productionApSoutheast1;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.productionAwsUsEast1a;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.productionCdAwsUsEast1a;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.productionCdUsEast1;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.productionEuWest1;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.productionUsCentral1;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.productionUsEast3;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.productionUsWest1;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.stagingTest;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.systemTest;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.testApNortheast1;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.testApNortheast2;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.testAwsUsEast1a;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.testEuWest1;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.testUsCentral1;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.testUsEast3;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.testUsWest1;
import static com.yahoo.vespa.hosted.controller.deployment.DeploymentTrigger.ChangesToCancel.ALL;
import static com.yahoo.vespa.hosted.controller.deployment.DeploymentTrigger.ChangesToCancel.PLATFORM;
import static java.util.Collections.emptyList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests a wide variety of deployment scenarios and configurations
 *
 * @author bratseth
 * @author mpolden
 * @author jonmv
 */
public class DeploymentTriggerTest {

    private final DeploymentTester tester = new DeploymentTester();

    @Test
    public void testTriggerFailing() {
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .upgradePolicy("default")
                .region("us-west-1")
                .build();

        // Deploy completely once
        var app = tester.newDeploymentContext().submit(applicationPackage).deploy();

        // New version is released
        Version version = Version.fromString("6.3");
        tester.controllerTester().upgradeSystem(version);
        tester.upgrader().maintain();

        // staging-test fails deployment and is retried
        app.failDeployment(stagingTest);
        tester.triggerJobs();
        assertEquals("Retried dead job", 2, tester.jobs().active().size());
        app.assertRunning(stagingTest);
        app.runJob(stagingTest);

        // system-test is now the only running job -- production jobs haven't started yet, since it is unfinished.
        app.assertRunning(systemTest);
        assertEquals(1, tester.jobs().active().size());

        // system-test fails and is retried
        app.timeOutUpgrade(systemTest);
        tester.triggerJobs();
        assertEquals("Job is retried on failure", 1, tester.jobs().active().size());
        app.runJob(systemTest);

        tester.triggerJobs();
        app.assertRunning(productionUsWest1);

        // production-us-west-1 fails, but the app loses its projectId, and the job isn't retried.
        tester.applications().lockApplicationOrThrow(app.application().id(), locked ->
                tester.applications().store(locked.withProjectId(OptionalLong.empty())));
        app.timeOutConvergence(productionUsWest1);
        tester.triggerJobs();
        assertEquals("Job is not triggered when no projectId is present", 0, tester.jobs().active().size());
    }

    @Test
    public void separateRevisionMakesApplicationChangeWaitForPreviousToComplete() {
        DeploymentContext app = tester.newDeploymentContext();
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .upgradeRevision(null) // separate by default, but we override this in test builder
                .region("us-east-3")
                .test("us-east-3")
                .build();

        app.submit(applicationPackage).runJob(systemTest).runJob(stagingTest).runJob(productionUsEast3);
        Optional<ApplicationVersion> v0 = app.lastSubmission();

        app.submit(applicationPackage);
        Optional<ApplicationVersion> v1 = app.lastSubmission();
        assertEquals(v0, app.instance().change().application());

        // Eager tests still run before new revision rolls out.
        app.runJob(systemTest).runJob(stagingTest);

        // v0 rolls out completely.
        app.runJob(testUsEast3);
        assertEquals(Optional.empty(), app.instance().change().application());

        // v1 starts rolling when v0 is done.
        tester.outstandingChangeDeployer().run();
        assertEquals(v1, app.instance().change().application());

        // v1 fails, so v2 starts immediately.
        app.runJob(productionUsEast3).failDeployment(testUsEast3);
        app.submit(applicationPackage);
        Optional<ApplicationVersion> v2 = app.lastSubmission();
        assertEquals(v2, app.instance().change().application());
    }

    @Test
    public void leadingUpgradeAllowsApplicationChangeWhileUpgrading() {
        var applicationPackage = new ApplicationPackageBuilder().region("us-east-3")
                                                                .upgradeRollout("leading")
                                                                .build();
        var app = tester.newDeploymentContext();

        app.submit(applicationPackage).deploy();

        Change upgrade = Change.of(new Version("7.8.9"));
        tester.controllerTester().upgradeSystem(upgrade.platform().get());
        tester.upgrader().maintain();
        app.runJob(systemTest).runJob(stagingTest);
        tester.triggerJobs();
        app.assertRunning(productionUsEast3);
        assertEquals(upgrade, app.instance().change());

        app.submit(applicationPackage);
        assertEquals(upgrade.with(app.lastSubmission().get()), app.instance().change());
    }

    @Test
    public void abortsJobsOnNewApplicationChange() {
        var app = tester.newDeploymentContext();
        app.submit()
           .runJob(systemTest)
           .runJob(stagingTest);

        tester.triggerJobs();
        RunId id = tester.jobs().last(app.instanceId(), productionUsCentral1).get().id();
        assertTrue(tester.jobs().active(id).isPresent());

        app.submit();
        assertTrue(tester.jobs().active(id).isPresent());

        tester.triggerJobs();
        tester.runner().run();
        assertTrue(tester.jobs().active(id).isPresent()); // old run

        app.runJob(systemTest).runJob(stagingTest).runJob(stagingTest); // outdated run is aborted when otherwise blocking a new run
        tester.triggerJobs();
        app.jobAborted(productionUsCentral1);

        app.runJob(productionUsCentral1).runJob(productionUsWest1).runJob(productionUsEast3);
        assertEquals(Change.empty(), app.instance().change());

        tester.controllerTester().upgradeSystem(new Version("8.9"));
        tester.upgrader().maintain();
        app.runJob(systemTest).runJob(stagingTest);
        tester.clock().advance(Duration.ofMinutes(1));
        tester.triggerJobs();

        // Upgrade is allowed to proceed ahead of revision change, and is not aborted.
        app.submit();
        app.runJob(systemTest).runJob(stagingTest);
        tester.triggerJobs();
        tester.runner().run();
        assertEquals(EnumSet.of(productionUsCentral1), tester.jobs().active().stream()
                                                             .map(run -> run.id().type())
                                                             .collect(Collectors.toCollection(() -> EnumSet.noneOf(JobType.class))));
    }

    @Test
    public void deploymentSpecWithDelays() {
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .systemTest()
                .delay(Duration.ofSeconds(30))
                .region("us-west-1")
                .delay(Duration.ofMinutes(2))
                .delay(Duration.ofMinutes(2)) // Multiple delays are summed up
                .region("us-central-1")
                .delay(Duration.ofMinutes(10)) // Delays after last region are valid, but have no effect
                .build();
        var app = tester.newDeploymentContext().submit(applicationPackage);

        // Test jobs pass
        app.runJob(systemTest);
        tester.clock().advance(Duration.ofSeconds(15));
        app.runJob(stagingTest);
        tester.triggerJobs();

        // No jobs have started yet, as 30 seconds have not yet passed.
        assertEquals(0, tester.jobs().active().size());
        tester.clock().advance(Duration.ofSeconds(15));
        tester.triggerJobs();

        // 30 seconds after the declared test, jobs may begin. The implicit test does not affect the delay.
        assertEquals(1, tester.jobs().active().size());
        app.assertRunning(productionUsWest1);

        // 3 minutes pass, delayed trigger does nothing as us-west-1 is still in progress
        tester.clock().advance(Duration.ofMinutes(3));
        tester.triggerJobs();
        assertEquals(1, tester.jobs().active().size());
        app.assertRunning(productionUsWest1);

        // us-west-1 completes
        app.runJob(productionUsWest1);

        // Delayed trigger does nothing as not enough time has passed after us-west-1 completion
        tester.triggerJobs();
        assertTrue("No more jobs triggered at this time", tester.jobs().active().isEmpty());

        // 3 minutes pass, us-central-1 is still not triggered
        tester.clock().advance(Duration.ofMinutes(3));
        tester.triggerJobs();
        assertTrue("No more jobs triggered at this time", tester.jobs().active().isEmpty());

        // 4 minutes pass, us-central-1 is triggered
        tester.clock().advance(Duration.ofMinutes(1));
        tester.triggerJobs();
        app.runJob(productionUsCentral1);
        assertTrue("All jobs consumed", tester.jobs().active().isEmpty());

        // Delayed trigger job runs again, with nothing to trigger
        tester.clock().advance(Duration.ofMinutes(10));
        tester.triggerJobs();
        assertTrue("All jobs consumed", tester.jobs().active().isEmpty());
    }

    @Test
    public void deploymentSpecWithParallelDeployments() {
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .region("us-central-1")
                .parallel("us-west-1", "us-east-3")
                .region("eu-west-1")
                .build();

        var app = tester.newDeploymentContext().submit(applicationPackage);

        // Test jobs pass
        app.runJob(systemTest).runJob(stagingTest);

        // Deploys in first region
        tester.triggerJobs();
        assertEquals(1, tester.jobs().active().size());
        app.runJob(productionUsCentral1);

        // Deploys in two regions in parallel
        tester.triggerJobs();
        assertEquals(2, tester.jobs().active().size());
        app.assertRunning(productionUsEast3);
        app.assertRunning(productionUsWest1);

        app.runJob(productionUsWest1);
        assertEquals(1, tester.jobs().active().size());
        app.assertRunning(productionUsEast3);

        app.runJob(productionUsEast3);

        // Last region completes
        tester.triggerJobs();
        assertEquals(1, tester.jobs().active().size());
        app.runJob(productionEuWest1);
        assertTrue("All jobs consumed", tester.jobs().active().isEmpty());
    }

    @Test
    public void testNoOtherChangesDuringSuspension() {
        // Application is deployed in 3 regions:
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .region("us-central-1")
                .parallel("us-west-1", "us-east-3")
                .build();
        var application = tester.newDeploymentContext().submit().deploy();

        // The first production zone is suspended:
        tester.configServer().setSuspension(application.deploymentIdIn(ZoneId.from("prod", "us-central-1")), true);

        // A new change needs to be pushed out, but should not go beyond the suspended zone:
        application.submit()
                   .runJob(systemTest)
                   .runJob(stagingTest)
                   .runJob(productionUsCentral1);
        tester.triggerJobs();
        application.assertNotRunning(productionUsEast3);
        application.assertNotRunning(productionUsWest1);

        // The zone is unsuspended so jobs start:
        tester.configServer().setSuspension(application.deploymentIdIn(ZoneId.from("prod", "us-central-1")), false);
        tester.triggerJobs();
        application.runJob(productionUsWest1).runJob(productionUsEast3);
        assertEquals(Change.empty(), application.instance().change());
    }

    @Test
    public void testBlockRevisionChange() {
        // Tuesday, 17:30
        tester.at(Instant.parse("2017-09-26T17:30:00.00Z"));

        Version version = Version.fromString("6.2");
        tester.controllerTester().upgradeSystem(version);

        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .upgradePolicy("canary")
                // Block application version changes on tuesday in hours 18 and 19
                .blockChange(true, false, "tue", "18-19", "UTC")
                .region("us-west-1")
                .region("us-central-1")
                .region("us-east-3")
                .build();

        var app = tester.newDeploymentContext().submit(applicationPackage).deploy();

        tester.clock().advance(Duration.ofHours(1)); // --------------- Enter block window: 18:30

        tester.triggerJobs();
        assertEquals(0, tester.jobs().active().size());

        app.submit(applicationPackage);
        assertTrue(app.deploymentStatus().outstandingChange(app.instance().name()).hasTargets());
        app.runJob(systemTest).runJob(stagingTest);

        tester.outstandingChangeDeployer().run();
        assertTrue(app.deploymentStatus().outstandingChange(app.instance().name()).hasTargets());

        tester.triggerJobs();
        assertEquals(emptyList(), tester.jobs().active());

        tester.clock().advance(Duration.ofHours(2)); // ---------------- Exit block window: 20:30

        tester.outstandingChangeDeployer().run();
        assertFalse(app.deploymentStatus().outstandingChange(app.instance().name()).hasTargets());

        tester.triggerJobs(); // Tests already run for the blocked production job.
        app.assertRunning(productionUsWest1);
    }

    @Test
    public void testCompletionOfPartOfChangeDuringBlockWindow() {
        // Tuesday, 17:30
        tester.at(Instant.parse("2017-09-26T17:30:00.00Z"));
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .blockChange(true, true, "tue", "18", "UTC")
                .region("us-west-1")
                .region("us-east-3")
                .build();
        var app = tester.newDeploymentContext().submit(applicationPackage).deploy();

        // Application on (6.1, 1.0.1)
        Version v1 = Version.fromString("6.1");

        // Application is mid-upgrade when block window begins, and gets an outstanding change.
        Version v2 = Version.fromString("6.2");
        tester.controllerTester().upgradeSystem(v2);
        tester.upgrader().maintain();
        app.runJob(stagingTest).runJob(systemTest);

        // Entering block window will keep the outstanding change in place.
        tester.clock().advance(Duration.ofHours(1));
        app.submit(applicationPackage);
        app.runJob(productionUsWest1);
        assertEquals(1, app.instanceJobs().get(productionUsWest1).lastSuccess().get().versions().targetApplication().buildNumber().getAsLong());
        assertEquals(2, app.deploymentStatus().outstandingChange(app.instance().name()).application().get().buildNumber().getAsLong());

        tester.triggerJobs();
        // Platform upgrade keeps rolling, since it began before block window, and tests for the new revision have also started.
        assertEquals(3, tester.jobs().active().size());
        app.runJob(productionUsEast3);
        assertEquals(2, tester.jobs().active().size());

        // Upgrade is done, and outstanding change rolls out when block window ends.
        assertEquals(Change.empty(), app.instance().change());
        assertTrue(app.deploymentStatus().outstandingChange(app.instance().name()).hasTargets());

        app.runJob(stagingTest).runJob(systemTest);
        tester.clock().advance(Duration.ofHours(1));
        tester.outstandingChangeDeployer().run();
        assertTrue(app.instance().change().hasTargets());
        assertFalse(app.deploymentStatus().outstandingChange(app.instance().name()).hasTargets());

        app.runJob(productionUsWest1).runJob(productionUsEast3);

        assertFalse(app.instance().change().hasTargets());
    }

    @Test
    public void testJobPause() {
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .region("us-west-1")
                .region("us-east-3")
                .build();
        var app = tester.newDeploymentContext().submit(applicationPackage).deploy();
        tester.controllerTester().upgradeSystem(new Version("9.8.7"));
        tester.upgrader().maintain();

        tester.deploymentTrigger().pauseJob(app.instanceId(), productionUsWest1,
                                            tester.clock().instant().plus(Duration.ofSeconds(1)));
        tester.deploymentTrigger().pauseJob(app.instanceId(), productionUsEast3,
                                            tester.clock().instant().plus(Duration.ofSeconds(3)));

        // us-west-1 does not trigger when paused.
        app.runJob(systemTest).runJob(stagingTest);
        tester.triggerJobs();
        app.assertNotRunning(productionUsWest1);

        // us-west-1 triggers when no longer paused, but does not retry when paused again.
        tester.clock().advance(Duration.ofMillis(1500));
        tester.triggerJobs();
        app.assertRunning(productionUsWest1);
        tester.deploymentTrigger().pauseJob(app.instanceId(), productionUsWest1, tester.clock().instant().plus(Duration.ofSeconds(1)));
        app.failDeployment(productionUsWest1);
        tester.triggerJobs();
        app.assertNotRunning(productionUsWest1);

        tester.clock().advance(Duration.ofMillis(1000));
        tester.triggerJobs();
        app.runJob(productionUsWest1);

        // us-east-3 does not automatically trigger when paused, but does when forced.
        tester.triggerJobs();
        app.assertNotRunning(productionUsEast3);
        tester.deploymentTrigger().forceTrigger(app.instanceId(), productionUsEast3, "mrTrigger", true);
        app.assertRunning(productionUsEast3);
        assertFalse(app.instance().jobPause(productionUsEast3).isPresent());
    }

    @Test
    public void applicationVersionIsNotDowngraded() {
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .region("us-central-1")
                .region("eu-west-1")
                .build();
        var app = tester.newDeploymentContext().submit(applicationPackage).deploy();

        // productionUsCentral1 fails after deployment, causing a mismatch between deployed and successful state.
        app.submit(applicationPackage)
           .runJob(systemTest)
           .runJob(stagingTest)
           .timeOutUpgrade(productionUsCentral1);

        ApplicationVersion appVersion1 = app.lastSubmission().get();
        assertEquals(appVersion1, app.deployment(ZoneId.from("prod.us-central-1")).applicationVersion());

        // Verify the application change is not removed when platform change is cancelled.
        tester.deploymentTrigger().cancelChange(app.instanceId(), PLATFORM);
        assertEquals(Change.of(appVersion1), app.instance().change());

        // Now cancel the change as is done through the web API.
        tester.deploymentTrigger().cancelChange(app.instanceId(), ALL);
        assertEquals(Change.empty(), app.instance().change());

        // A new version is released, which should now deploy the currently deployed application version to avoid downgrades.
        Version version1 = new Version("6.2");
        tester.controllerTester().upgradeSystem(version1);
        tester.upgrader().maintain();
        app.runJob(systemTest).runJob(stagingTest).failDeployment(productionUsCentral1);

        // The last job has a different target, and the tests need to run again.
        // These may now start, since the first job has been triggered once, and thus is verified already.
        app.runJob(systemTest).runJob(stagingTest);

        // Finally, the two production jobs complete, in order.
        app.runJob(productionUsCentral1).runJob(productionEuWest1);
        assertEquals(appVersion1, app.deployment(ZoneId.from("prod.us-central-1")).applicationVersion());
    }

    @Test
    public void downgradingApplicationVersionWorks() {
        var app = tester.newDeploymentContext().submit().deploy();
        ApplicationVersion appVersion0 = app.lastSubmission().get();
        assertEquals(Optional.of(appVersion0), app.instance().latestDeployed());

        app.submit().deploy();
        ApplicationVersion appVersion1 = app.lastSubmission().get();
        assertEquals(Optional.of(appVersion1), app.instance().latestDeployed());

        // Downgrading application version.
        tester.deploymentTrigger().forceChange(app.instanceId(), Change.of(appVersion0));
        assertEquals(Change.of(appVersion0), app.instance().change());
        app.runJob(stagingTest)
           .runJob(productionUsCentral1)
           .runJob(productionUsEast3)
           .runJob(productionUsWest1);
        assertEquals(Change.empty(), app.instance().change());
        assertEquals(appVersion0, app.instance().deployments().get(productionUsEast3.zone(tester.controller().system())).applicationVersion());
        assertEquals(Optional.of(appVersion0), app.instance().latestDeployed());
    }

    @Test
    public void settingANoOpChangeIsANoOp() {
        var app = tester.newDeploymentContext().submit();
        assertEquals(Optional.empty(), app.instance().latestDeployed());

        app.deploy();
        ApplicationVersion appVersion0 = app.lastSubmission().get();
        assertEquals(Optional.of(appVersion0), app.instance().latestDeployed());

        app.submit().deploy();
        ApplicationVersion appVersion1 = app.lastSubmission().get();
        assertEquals(Optional.of(appVersion1), app.instance().latestDeployed());

        // Triggering a roll-out of an already deployed application is a no-op.
        assertEquals(Change.empty(), app.instance().change());
        tester.deploymentTrigger().forceChange(app.instanceId(), Change.of(appVersion1));
        assertEquals(Change.empty(), app.instance().change());
        assertEquals(Optional.of(appVersion1), app.instance().latestDeployed());
    }

    @Test
    public void stepIsCompletePreciselyWhenItShouldBe() {
        var app1 = tester.newDeploymentContext("tenant1", "app1", "default");
        var app2 = tester.newDeploymentContext("tenant1", "app2", "default");
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .region("us-central-1")
                .region("eu-west-1")
                .build();

        // System upgrades to version0 and applications deploy on that version
        Version version0 = Version.fromString("7.0");
        tester.controllerTester().upgradeSystem(version0);
        app1.submit(applicationPackage).deploy();
        app2.submit(applicationPackage).deploy();

        // version1 is released and application1 skips upgrading to that version
        Version version1 = Version.fromString("7.1");
        tester.controllerTester().upgradeSystem(version1);
        tester.upgrader().maintain();
        // Deploy application2 to keep this version present in the system
        app2.deployPlatform(version1);
        tester.deploymentTrigger().cancelChange(app1.instanceId(), ALL);

        // version2 is released and application1 starts upgrading
        Version version2 = Version.fromString("7.2");
        tester.controllerTester().upgradeSystem(version2);
        tester.upgrader().maintain();
        tester.triggerJobs();
        app1.jobAborted(systemTest).jobAborted(stagingTest);
        app1.runJob(systemTest).runJob(stagingTest).timeOutConvergence(productionUsCentral1);
        assertEquals(version2, app1.deployment(productionUsCentral1.zone(main)).version());
        Instant triggered = app1.instanceJobs().get(productionUsCentral1).lastTriggered().get().start();
        tester.clock().advance(Duration.ofHours(1));

        // version2 becomes broken and upgrade targets latest non-broken
        tester.upgrader().overrideConfidence(version2, VespaVersion.Confidence.broken);
        tester.controllerTester().computeVersionStatus();
        tester.upgrader().maintain(); // Cancel upgrades to broken version
        assertEquals("Change becomes latest non-broken version", Change.of(version1), app1.instance().change());

        // version1 proceeds 'til the last job, where it fails; us-central-1 is skipped, as current change is strictly dominated by what's deployed there.
        app1.runJob(systemTest).runJob(stagingTest)
            .failDeployment(productionEuWest1);
        assertEquals(triggered, app1.instanceJobs().get(productionUsCentral1).lastTriggered().get().start());

        // Roll out a new application version, which gives a dual change -- this should trigger us-central-1, but only as long as it hasn't yet deployed there.
        ApplicationVersion revision1 = app1.lastSubmission().get();
        app1.submit(applicationPackage);
        ApplicationVersion revision2 = app1.lastSubmission().get();
        app1.runJob(systemTest).runJob(stagingTest);
        assertEquals(Change.of(version1).with(revision2), app1.instance().change());
        tester.triggerJobs();
        app1.assertRunning(productionUsCentral1);
        assertEquals(version2, app1.instance().deployments().get(productionUsCentral1.zone(main)).version());
        assertEquals(revision1, app1.deployment(productionUsCentral1.zone(main)).applicationVersion());
        assertTrue(triggered.isBefore(app1.instanceJobs().get(productionUsCentral1).lastTriggered().get().start()));

        // Change has a higher application version than what is deployed -- deployment should trigger.
        app1.timeOutUpgrade(productionUsCentral1);
        assertEquals(version2, app1.deployment(productionUsCentral1.zone(main)).version());
        assertEquals(revision2, app1.deployment(productionUsCentral1.zone(main)).applicationVersion());

        // Change is again strictly dominated, and us-central-1 is skipped, even though it is still failing.
        tester.clock().advance(Duration.ofHours(3)); // Enough time for retry
        tester.triggerJobs();
        // Failing job is not retried as change has been deployed
        app1.assertNotRunning(productionUsCentral1);

        // Last job has a different deployment target, so tests need to run again.
        app1.runJob(systemTest)
            .runJob(stagingTest)            // Eager test of outstanding change, assuming upgrade in west succeeds.
            .runJob(productionEuWest1)      // Upgrade completes, and revision is the only change.
            .runJob(productionUsCentral1)   // With only revision change, central should run to cover a previous failure.
            .runJob(productionEuWest1);     // Finally, west changes revision.
        assertEquals(Change.empty(), app1.instance().change());
        assertEquals(Optional.of(RunStatus.success), app1.instanceJobs().get(productionUsCentral1).lastStatus());
    }

    @Test
    public void eachParallelDeployTargetIsTested() {
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .parallel("eu-west-1", "us-east-3")
                .build();
        // Application version 1 and platform version 6.1.
        var app = tester.newDeploymentContext().submit(applicationPackage).deploy();

        // Success in first prod zone, change cancelled between triggering and completion of eu west job.
        // One of the parallel zones get a deployment, but both fail their jobs.
        Version v1 = new Version("6.1");
        Version v2 = new Version("6.2");
        tester.controllerTester().upgradeSystem(v2);
        tester.upgrader().maintain();
        app.runJob(systemTest).runJob(stagingTest);
        app.timeOutConvergence(productionEuWest1);
        tester.deploymentTrigger().cancelChange(app.instanceId(), PLATFORM);
        assertEquals(v2, app.deployment(productionEuWest1.zone(main)).version());
        assertEquals(v1, app.deployment(productionUsEast3.zone(main)).version());

        // New application version should run system and staging tests against both 6.1 and 6.2, in no particular order.
        app.submit(applicationPackage);
        tester.triggerJobs();
        Version firstTested = app.instanceJobs().get(systemTest).lastTriggered().get().versions().targetPlatform();
        assertEquals(firstTested, app.instanceJobs().get(stagingTest).lastTriggered().get().versions().targetPlatform());

        app.runJob(systemTest).runJob(stagingTest);

        // Test jobs for next production zone can start and run immediately.
        tester.triggerJobs();
        assertNotEquals(firstTested, app.instanceJobs().get(systemTest).lastTriggered().get().versions().targetPlatform());
        assertNotEquals(firstTested, app.instanceJobs().get(stagingTest).lastTriggered().get().versions().targetPlatform());
        app.runJob(systemTest).runJob(stagingTest);

        // Finish old run of the aborted production job.
        app.triggerJobs().jobAborted(productionUsEast3);

        // New upgrade is already tested for both jobs.

        // Both jobs fail again, and must be re-triggered -- this is ok, as they are both already triggered on their current targets.
        app.failDeployment(productionEuWest1).failDeployment(productionUsEast3)
           .runJob(productionEuWest1).runJob(productionUsEast3);
        assertFalse(app.instance().change().hasTargets());
        assertEquals(2, app.instanceJobs().get(productionEuWest1).lastSuccess().get().versions().targetApplication().buildNumber().getAsLong());
        assertEquals(2, app.instanceJobs().get(productionUsEast3).lastSuccess().get().versions().targetApplication().buildNumber().getAsLong());
    }

    @Test
    public void retriesFailingJobs() {
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .region("us-central-1")
                .build();

        // Deploy completely on default application and platform versions
        var app = tester.newDeploymentContext().submit(applicationPackage).deploy();

        // New application change is deployed and fails in system-test for a while
        app.submit(applicationPackage).runJob(stagingTest).failDeployment(systemTest);

        // Retries immediately once
        app.failDeployment(systemTest);
        tester.triggerJobs();
        app.assertRunning(systemTest);

        // Stops immediate retry when next triggering is considered after first failure
        tester.clock().advance(Duration.ofSeconds(1));
        app.failDeployment(systemTest);
        tester.triggerJobs();
        app.assertNotRunning(systemTest);

        // Retries after 10 minutes since previous completion, plus half the time since the first failure
        tester.clock().advance(Duration.ofMinutes(10).plus(Duration.ofSeconds(1)));
        tester.triggerJobs();
        app.assertRunning(systemTest);

        // Retries less frequently as more time passes
        app.failDeployment(systemTest);
        tester.clock().advance(Duration.ofMinutes(15));
        tester.triggerJobs();
        app.assertNotRunning(systemTest);

        // Retries again when sufficient time has passed
        tester.clock().advance(Duration.ofSeconds(2));
        tester.triggerJobs();
        app.assertRunning(systemTest);

        // Still fails and is not retried
        app.failDeployment(systemTest);
        tester.triggerJobs();
        app.assertNotRunning(systemTest);

        // Another application change is deployed and fixes system-test. Change is triggered immediately as target changes
        app.submit(applicationPackage).deploy();
        assertTrue("Deployment completed", tester.jobs().active().isEmpty());
    }

    @Test
    public void testPlatformVersionSelection() {
        // Setup system
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .region("us-west-1")
                .build();
        Version version1 = tester.controller().readSystemVersion();
        var app1 = tester.newDeploymentContext();

        // First deployment: An application change
        app1.submit(applicationPackage).deploy();

        assertEquals("First deployment gets system version", version1, app1.application().oldestDeployedPlatform().get());
        assertEquals(version1, tester.configServer().lastPrepareVersion().get());

        // Application change after a new system version, and a region added
        Version version2 = new Version(version1.getMajor(), version1.getMinor() + 1);
        tester.controllerTester().upgradeSystem(version2);

        applicationPackage = new ApplicationPackageBuilder()
                .region("us-west-1")
                .region("us-east-3")
                .build();
        app1.submit(applicationPackage).deploy();
        assertEquals("Application change preserves version, and new region gets oldest version too",
                     version1, app1.application().oldestDeployedPlatform().get());
        assertEquals(version1, tester.configServer().lastPrepareVersion().get());
        assertFalse("Change deployed", app1.instance().change().hasTargets());

        tester.upgrader().maintain();
        app1.deployPlatform(version2);

        assertEquals("Version upgrade changes version", version2, app1.application().oldestDeployedPlatform().get());
        assertEquals(version2, tester.configServer().lastPrepareVersion().get());
    }

    @Test
    public void requeueOutOfCapacityStagingJob() {
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .region("us-east-3")
                .build();

        var app1 = tester.newDeploymentContext("tenant1", "app1", "default").submit(applicationPackage);
        var app2 = tester.newDeploymentContext("tenant2", "app2", "default").submit(applicationPackage);
        var app3 = tester.newDeploymentContext("tenant3", "app3", "default").submit(applicationPackage);

        // all applications: system-test completes successfully with some time in between, to determine trigger order.
        app2.runJob(systemTest);
        tester.clock().advance(Duration.ofMinutes(1));

        app1.runJob(systemTest);
        tester.clock().advance(Duration.ofMinutes(1));

        app3.runJob(systemTest);

        // all applications: staging test jobs queued
        tester.triggerJobs();
        assertEquals(3, tester.jobs().active().size());

        // Abort all running jobs, so we have three candidate jobs, of which only one should be triggered at a time.
        tester.abortAll();

        assertEquals(List.of(), tester.jobs().active());

        tester.readyJobsTrigger().maintain();
        assertEquals(1, tester.jobs().active().size());

        tester.readyJobsTrigger().maintain();
        assertEquals(2, tester.jobs().active().size());

        tester.readyJobsTrigger().maintain();
        assertEquals(3, tester.jobs().active().size());

        // Remove the jobs for app1 and app2, and then let app3 fail with outOfCapacity.
        // All three jobs are now eligible, but the one for app3 should trigger first as an outOfCapacity-retry.
        app3.outOfCapacity(stagingTest);
        app1.abortJob(stagingTest);
        app2.abortJob(stagingTest);

        tester.readyJobsTrigger().maintain();
        app3.assertRunning(stagingTest);
        assertEquals(1, tester.jobs().active().size());

        tester.readyJobsTrigger().maintain();
        assertEquals(2, tester.jobs().active().size());

        tester.readyJobsTrigger().maintain();
        assertEquals(3, tester.jobs().active().size());

        // Finish deployment for apps 2 and 3, then release a new version, leaving only app1 with an application upgrade.
        app2.deploy();
        app3.deploy();
        app1.runJob(stagingTest);
        assertEquals(0, tester.jobs().active().size());

        tester.controllerTester().upgradeSystem(new Version("6.2"));
        tester.upgrader().maintain();
        app1.submit(applicationPackage);

        // Tests for app1 trigger before the others since it carries an application upgrade.
        tester.readyJobsTrigger().run();
        app1.assertRunning(systemTest);
        app1.assertRunning(stagingTest);
        assertEquals(2, tester.jobs().active().size());

        // Let the test jobs start, remove everything except system test for app3, which fails with outOfCapacity again.
        tester.triggerJobs();
        app3.outOfCapacity(systemTest);
        app1.abortJob(systemTest);
        app1.abortJob(stagingTest);
        app2.abortJob(systemTest);
        app2.abortJob(stagingTest);
        app3.abortJob(stagingTest);
        assertEquals(0, tester.jobs().active().size());

        assertTrue(app1.instance().change().application().isPresent());
        assertFalse(app2.instance().change().application().isPresent());
        assertFalse(app3.instance().change().application().isPresent());

        tester.readyJobsTrigger().maintain();
        app1.assertRunning(stagingTest);
        app3.assertRunning(systemTest);
        assertEquals(2, tester.jobs().active().size());

        tester.readyJobsTrigger().maintain();
        app1.assertRunning(systemTest);
        assertEquals(4, tester.jobs().active().size());

        tester.readyJobsTrigger().maintain();
        app3.assertRunning(stagingTest);
        app2.assertRunning(stagingTest);
        app2.assertRunning(systemTest);
        assertEquals(6, tester.jobs().active().size());
    }

    @Test
    public void testUserInstancesNotInDeploymentSpec() {
        var app = tester.newDeploymentContext();
        tester.controller().applications().createInstance(app.application().id().instance("user"));
        app.submit().deploy();
    }

    @Test
    public void testMultipleInstancesWithDifferentChanges() {
        DeploymentContext i1 = tester.newDeploymentContext("t", "a", "i1");
        DeploymentContext i2 = tester.newDeploymentContext("t", "a", "i2");
        DeploymentContext i3 = tester.newDeploymentContext("t", "a", "i3");
        DeploymentContext i4 = tester.newDeploymentContext("t", "a", "i4");
        ApplicationPackage applicationPackage = ApplicationPackageBuilder
                .fromDeploymentXml("<deployment version='1'>\n" +
                                   "  <upgrade revision='separate' />\n" +
                                   "  <parallel>\n" +
                                   "    <instance id='i1'>\n" +
                                   "      <prod>\n" +
                                   "        <region>us-east-3</region>\n" +
                                   "        <delay hours='6' />\n" +
                                   "      </prod>\n" +
                                   "    </instance>\n" +
                                   "    <instance id='i2'>\n" +
                                   "      <prod>\n" +
                                   "        <region>us-east-3</region>\n" +
                                   "      </prod>\n" +
                                   "    </instance>\n" +
                                   "  </parallel>\n" +
                                   "  <instance id='i3'>\n" +
                                   "    <prod>\n" +
                                   "      <region>us-east-3</region>\n" +
                                   "        <delay hours='18' />\n" +
                                   "      <test>us-east-3</test>\n" +
                                   "    </prod>\n" +
                                   "  </instance>\n" +
                                   "  <instance id='i4'>\n" +
                                   "    <test />\n" +
                                   "    <staging />\n" +
                                   "    <prod>\n" +
                                   "      <region>us-east-3</region>\n" +
                                   "    </prod>\n" +
                                   "  </instance>\n" +
                                   "</deployment>\n");

        // Package is submitted, and change propagated to the two first instances.
        i1.submit(applicationPackage);
        Optional<ApplicationVersion> v0 = i1.lastSubmission();
        tester.outstandingChangeDeployer().run();
        assertEquals(v0, i1.instance().change().application());
        assertEquals(v0, i2.instance().change().application());
        assertEquals(Optional.empty(), i3.instance().change().application());
        assertEquals(Optional.empty(), i4.instance().change().application());

        // Tests run in i4, as they're declared there, and i1 and i2 get to work
        i4.runJob(systemTest).runJob(stagingTest);
        i1.runJob(productionUsEast3);
        i2.runJob(productionUsEast3);

        // Since the post-deployment delay of i1 is incomplete, i3 doesn't yet get the change.
        tester.outstandingChangeDeployer().run();
        assertEquals(v0, i1.instance().latestDeployed());
        assertEquals(v0, i2.instance().latestDeployed());
        assertEquals(Optional.empty(), i1.instance().change().application());
        assertEquals(Optional.empty(), i2.instance().change().application());
        assertEquals(Optional.empty(), i3.instance().change().application());
        assertEquals(Optional.empty(), i4.instance().change().application());

        // When the delay is done, i3 gets the change.
        tester.clock().advance(Duration.ofHours(6));
        tester.outstandingChangeDeployer().run();
        assertEquals(Optional.empty(), i1.instance().change().application());
        assertEquals(Optional.empty(), i2.instance().change().application());
        assertEquals(v0, i3.instance().change().application());
        assertEquals(Optional.empty(), i4.instance().change().application());

        // v0 begins roll-out in i3, and v1 is submitted and rolls out in i1 and i2 some time later
        i3.runJob(productionUsEast3); // v0
        tester.clock().advance(Duration.ofHours(12));
        i1.submit(applicationPackage);
        Optional<ApplicationVersion> v1 = i1.lastSubmission();
        i4.runJob(systemTest).runJob(stagingTest);
        i1.runJob(productionUsEast3); // v1
        i2.runJob(productionUsEast3); // v1
        assertEquals(v1, i1.instance().latestDeployed());
        assertEquals(v1, i2.instance().latestDeployed());
        assertEquals(Optional.empty(), i1.instance().change().application());
        assertEquals(Optional.empty(), i2.instance().change().application());
        assertEquals(v0, i3.instance().change().application());
        assertEquals(Optional.empty(), i4.instance().change().application());

        // After some time, v2 also starts rolling out to i1 and i2, but does not complete in i2
        tester.clock().advance(Duration.ofHours(3));
        i1.submit(applicationPackage);
        Optional<ApplicationVersion> v2 = i1.lastSubmission();
        i4.runJob(systemTest).runJob(stagingTest);
        i1.runJob(productionUsEast3); // v2
        tester.clock().advance(Duration.ofHours(3));

        // v1 is all done in i1 and i2, but does not yet roll out in i3; v2 is not completely rolled out there yet.
        tester.outstandingChangeDeployer().run();
        assertEquals(v0, i3.instance().change().application());

        // i3 completes v0, which rolls out to i4; v1 is ready for i3, but v2 is not.
        i3.runJob(testUsEast3);
        assertEquals(Optional.empty(), i3.instance().change().application());
        tester.outstandingChangeDeployer().run();
        assertEquals(v2, i1.instance().latestDeployed());
        assertEquals(v1, i2.instance().latestDeployed());
        assertEquals(v0, i3.instance().latestDeployed());
        assertEquals(Optional.empty(), i1.instance().change().application());
        assertEquals(v2, i2.instance().change().application());
        assertEquals(v1, i3.instance().change().application());
        assertEquals(v0, i4.instance().change().application());
    }

    @Test
    public void testMultipleInstances() {
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .instances("instance1,instance2")
                .region("us-east-3")
                .build();
        var app = tester.newDeploymentContext("tenant1", "application1", "instance1")
                        .submit(applicationPackage)
                        .completeRollout();
        assertEquals(2, app.application().instances().size());
        assertEquals(2, app.application().productionDeployments().values().stream()
                           .mapToInt(Collection::size)
                           .sum());
    }

    @Test
    public void testDeclaredProductionTests() {
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .region("us-east-3")
                .delay(Duration.ofMinutes(1))
                .test("us-east-3")
                .region("us-west-1")
                .region("us-central-1")
                .test("us-central-1")
                .test("us-west-1")
                .build();
        var app = tester.newDeploymentContext().submit(applicationPackage);

        app.runJob(systemTest).runJob(stagingTest).runJob(productionUsEast3);
        app.assertNotRunning(productionUsWest1);

        tester.clock().advance(Duration.ofMinutes(1));
        app.runJob(testUsEast3)
           .runJob(productionUsWest1).runJob(productionUsCentral1)
           .runJob(testUsCentral1).runJob(testUsWest1);
        assertEquals(Change.empty(), app.instance().change());

        // Application starts upgrade, but is confidence is broken cancelled after first zone. Tests won't run.
        Version version0 = app.application().oldestDeployedPlatform().get();
        Version version1 = Version.fromString("7.7");
        tester.controllerTester().upgradeSystem(version1);
        tester.upgrader().maintain();

        app.runJob(systemTest).runJob(stagingTest).runJob(productionUsEast3);
        tester.clock().advance(Duration.ofMinutes(1));
        app.failDeployment(testUsEast3);
        tester.triggerJobs();
        app.assertRunning(testUsEast3);

        tester.upgrader().overrideConfidence(version1, VespaVersion.Confidence.broken);
        tester.controllerTester().computeVersionStatus();
        tester.upgrader().maintain();
        app.failDeployment(testUsEast3);
        app.assertNotRunning(testUsEast3);
        assertEquals(Change.empty(), app.instance().change());

        // Application is pinned to previous version, and downgrades to that. Tests are re-run.
        tester.deploymentTrigger().triggerChange(app.instanceId(), Change.of(version0).withPin());
        app.runJob(stagingTest).runJob(productionUsEast3);
        tester.clock().advance(Duration.ofMinutes(1));
        app.failDeployment(testUsEast3);
        tester.clock().advance(Duration.ofMinutes(11)); // Job is cooling down after consecutive failures.
        app.runJob(testUsEast3);
        assertEquals(Change.empty().withPin(), app.instance().change());
    }

    @Test
    public void testDeployComplicatedDeploymentSpec() {
        String complicatedDeploymentSpec =
                "<deployment version='1.0' athenz-domain='domain' athenz-service='service'>\n" +
                "    <parallel>\n" +
                "        <instance id='instance' athenz-service='in-service'>\n" +
                "            <staging />\n" +
                "            <prod>\n" +
                "                <parallel>\n" +
                "                    <region active='true'>us-west-1</region>\n" +
                "                    <steps>\n" +
                "                        <region active='true'>us-east-3</region>\n" +
                "                        <delay hours='2' />\n" +
                "                        <region active='true'>eu-west-1</region>\n" +
                "                        <delay hours='2' />\n" +
                "                    </steps>\n" +
                "                    <steps>\n" +
                "                        <delay hours='3' />\n" +
                "                        <region active='true'>aws-us-east-1a</region>\n" +
                "                        <parallel>\n" +
                "                            <region active='true' athenz-service='no-service'>ap-northeast-1</region>\n" +
                "                            <region active='true'>ap-northeast-2</region>\n" +
                "                            <test>aws-us-east-1a</test>\n" +
                "                        </parallel>\n" +
                "                    </steps>\n" +
                "                    <delay hours='3' minutes='30' />\n" +
                "                </parallel>\n" +
                "                <parallel>\n" +
                "                   <test>ap-northeast-2</test>\n" +
                "                   <test>ap-northeast-1</test>\n" +
                "                </parallel>\n" +
                "                <test>us-east-3</test>\n" +
                "                <region active='true'>ap-southeast-1</region>\n" +
                "            </prod>\n" +
                "            <endpoints>\n" +
                "                <endpoint id='foo' container-id='bar'>\n" +
                "                    <region>us-east-3</region>\n" +
                "                </endpoint>\n" +
                "                <endpoint id='nalle' container-id='frosk' />\n" +
                "                <endpoint container-id='quux' />\n" +
                "            </endpoints>\n" +
                "        </instance>\n" +
                "        <instance id='other'>\n" +
                "            <upgrade policy='conservative' />\n" +
                "            <test />\n" +
                "            <block-change revision='true' version='false' days='sat' hours='0-23' time-zone='CET' />\n" +
                "            <prod>\n" +
                "                <region active='true'>eu-west-1</region>\n" +
                "                <test>eu-west-1</test>\n" +
                "            </prod>\n" +
                "            <notifications when='failing'>\n" +
                "                <email role='author' />\n" +
                "                <email address='john@dev' when='failing-commit' />\n" +
                "                <email address='jane@dev' />\n" +
                "            </notifications>\n" +
                "        </instance>\n" +
                "    </parallel>\n" +
                "    <instance id='last'>\n" +
                "        <upgrade policy='conservative' />\n" +
                "        <prod>\n" +
                "            <region active='true'>eu-west-1</region>\n" +
                "        </prod>\n" +
                "    </instance>\n" +
                "</deployment>\n";

        ApplicationPackage applicationPackage = ApplicationPackageBuilder.fromDeploymentXml(complicatedDeploymentSpec);
        var app1 = tester.newDeploymentContext("t", "a", "instance").submit(applicationPackage);
        var app2 = tester.newDeploymentContext("t", "a", "other");
        var app3 = tester.newDeploymentContext("t", "a", "last");

        // Verify that the first submission rolls out as per the spec.
        tester.triggerJobs();
        assertEquals(2, tester.jobs().active().size());
        app1.runJob(stagingTest);
        tester.triggerJobs();
        assertEquals(1, tester.jobs().active().size());
        app2.runJob(systemTest);

        app1.runJob(productionUsWest1);
        tester.triggerJobs();
        assertEquals(2, tester.jobs().active().size());
        app1.runJob(productionUsEast3);
        tester.triggerJobs();
        assertEquals(1, tester.jobs().active().size());

        tester.clock().advance(Duration.ofHours(2));

        app1.runJob(productionEuWest1);
        tester.triggerJobs();
        assertEquals(1, tester.jobs().active().size());
        app2.assertNotRunning(testEuWest1);
        app2.runJob(productionEuWest1);
        tester.triggerJobs();
        assertEquals(1, tester.jobs().active().size());
        app2.runJob(testEuWest1);
        tester.triggerJobs();
        assertEquals(List.of(), tester.jobs().active());

        tester.clock().advance(Duration.ofHours(1));
        app1.runJob(productionAwsUsEast1a);
        tester.triggerJobs();
        assertEquals(3, tester.jobs().active().size());
        app1.runJob(testAwsUsEast1a);
        tester.triggerJobs();
        assertEquals(2, tester.jobs().active().size());
        app1.runJob(productionApNortheast2);
        tester.triggerJobs();
        assertEquals(1, tester.jobs().active().size());
        app1.runJob(productionApNortheast1);
        tester.triggerJobs();
        assertEquals(List.of(), tester.jobs().active());

        tester.clock().advance(Duration.ofMinutes(30));
        tester.triggerJobs();
        assertEquals(List.of(), tester.jobs().active());

        tester.clock().advance(Duration.ofMinutes(30));
        app1.runJob(testApNortheast1);
        tester.triggerJobs();
        assertEquals(1, tester.jobs().active().size());
        app1.runJob(testApNortheast2);
        tester.triggerJobs();
        assertEquals(1, tester.jobs().active().size());
        app1.runJob(testUsEast3);
        tester.triggerJobs();
        assertEquals(1, tester.jobs().active().size());
        app1.runJob(productionApSoutheast1);
        tester.triggerJobs();
        assertEquals(1, tester.jobs().active().size());
        app3.runJob(productionEuWest1);
        tester.triggerJobs();
        assertEquals(List.of(), tester.jobs().active());

        tester.atMondayMorning().clock().advance(Duration.ofDays(5)); // Inside revision block window for second, conservative instance.
        Version version = Version.fromString("8.1");
        tester.controllerTester().upgradeSystem(version);
        tester.upgrader().maintain();
        assertEquals(Change.of(version), app1.instance().change());
        assertEquals(Change.empty(), app2.instance().change());
        assertEquals(Change.empty(), app3.instance().change());

        // Upgrade instance 1; upgrade rolls out first, with revision following.
        // The new platform won't roll out to the conservative instance until the normal one is upgraded.
        app1.submit(applicationPackage);
        assertEquals(Change.of(version).with(app1.application().latestVersion().get()), app1.instance().change());
        // Upgrade platform.
        app2.runJob(systemTest);
        app1.runJob(stagingTest)
            .runJob(productionUsWest1)
            .runJob(productionUsEast3);
        // Upgrade revision
        tester.clock().advance(Duration.ofSeconds(1)); // Ensure we see revision as rolling after upgrade.
        app2.runJob(systemTest);        // R
        app1.runJob(stagingTest)        // R
            .runJob(productionUsWest1); // R
            // productionUsEast3 won't change revision before its production test has completed for the upgrade, which is one of the last jobs!
        tester.clock().advance(Duration.ofHours(2));
        app1.runJob(productionEuWest1);
        tester.clock().advance(Duration.ofHours(1));
        app1.runJob(productionAwsUsEast1a);
        app1.runJob(testAwsUsEast1a);
        tester.clock().advance(Duration.ofSeconds(1));
        app1.runJob(productionAwsUsEast1a); // R
        app1.runJob(testAwsUsEast1a);       // R
        app1.runJob(productionApNortheast2);
        app1.runJob(productionApNortheast1);
        tester.clock().advance(Duration.ofHours(1));
        app1.runJob(testApNortheast1);
        app1.runJob(testApNortheast2);
        app1.runJob(productionApNortheast2); // R
        app1.runJob(productionApNortheast1); // R
        app1.runJob(testUsEast3);
        app1.runJob(productionApSoutheast1);
        tester.clock().advance(Duration.ofSeconds(1));
        app1.runJob(productionUsEast3);      // R
        tester.clock().advance(Duration.ofHours(2));
        app1.runJob(productionEuWest1);      // R
        tester.clock().advance(Duration.ofMinutes(330));
        app1.runJob(testApNortheast1);       // R
        app1.runJob(testApNortheast2);       // R
        app1.runJob(testUsEast3);            // R
        app1.runJob(productionApSoutheast1); // R

        app1.runJob(stagingTest);   // Tests with only the outstanding application change.
        app2.runJob(systemTest);    // Tests with only the outstanding application change.

        // Confidence rises to high, for the new version, and instance 2 starts to upgrade.
        tester.controllerTester().computeVersionStatus();
        tester.upgrader().maintain();
        tester.outstandingChangeDeployer().run();
        tester.triggerJobs();
        assertEquals(tester.jobs().active().toString(), 1, tester.jobs().active().size());
        assertEquals(Change.empty(), app1.instance().change());
        assertEquals(Change.of(version), app2.instance().change());
        assertEquals(Change.empty(), app3.instance().change());

        app2.runJob(productionEuWest1)
            .failDeployment(testEuWest1);

        // Instance 2 failed the last job, and now exits block window, letting application change roll out with the upgrade.
        tester.clock().advance(Duration.ofDays(1)); // Leave block window for revisions.
        tester.upgrader().maintain();
        tester.outstandingChangeDeployer().run();
        assertEquals(0, tester.jobs().active().size());
        tester.triggerJobs();
        assertEquals(1, tester.jobs().active().size());
        assertEquals(Change.empty(), app1.instance().change());
        assertEquals(Change.of(version).with(app1.application().latestVersion().get()), app2.instance().change());

        app2.runJob(productionEuWest1)
            .runJob(testEuWest1);
        assertEquals(Change.empty(), app2.instance().change());
        assertEquals(Change.empty(), app3.instance().change());

        // Two first instances upgraded and with new revision — last instance gets both changes as well.
        tester.upgrader().maintain();
        tester.outstandingChangeDeployer().run();
        assertEquals(Change.of(version).with(app1.lastSubmission().get()), app3.instance().change());

        tester.deploymentTrigger().cancelChange(app3.instanceId(), ALL);
        tester.outstandingChangeDeployer().run();
        tester.upgrader().maintain();
        assertEquals(Change.of(app1.lastSubmission().get()), app3.instance().change());

        app3.runJob(productionEuWest1);
        tester.upgrader().maintain();
        app1.runJob(stagingTest);
        app3.runJob(productionEuWest1);
        tester.triggerJobs();
        assertEquals(List.of(), tester.jobs().active());
        assertEquals(Change.empty(), app3.instance().change());
    }

    @Test
    public void testRevisionJoinsUpgradeWithSeparateRollout() {
        var appPackage = new ApplicationPackageBuilder().region("us-central-1")
                                                        .region("us-east-3")
                                                        .region("us-west-1")
                                                        .upgradeRollout("separate")
                                                        .build();
        var app = tester.newDeploymentContext().submit(appPackage).deploy();

        // Platform rolls through first production zone.
        var version0 = tester.controller().readSystemVersion();
        var version1 = new Version("7.1");
        tester.controllerTester().upgradeSystem(version1);
        tester.upgrader().maintain();
        app.runJob(systemTest).runJob(stagingTest).runJob(productionUsCentral1);
        tester.clock().advance(Duration.ofMinutes(1));

        // Revision starts rolling, but stays behind.
        var revision0 = app.lastSubmission();
        app.submit(appPackage);
        var revision1 = app.lastSubmission();
        assertEquals(Change.of(version1).with(revision1.get()), app.instance().change());
        app.runJob(systemTest).runJob(stagingTest).runJob(productionUsCentral1);

        // Upgrade got here first, so attempts to proceed alone, but the upgrade fails.
        app.triggerJobs();
        assertEquals(new Versions(version1, revision0.get(), Optional.of(version0), revision0),
                     tester.jobs().last(app.instanceId(), productionUsEast3).get().versions());
        app.timeOutConvergence(productionUsEast3);

        // Revision is allowed to join.
        app.triggerJobs();
        assertEquals(new Versions(version1, revision1.get(), Optional.of(version1), revision0),
                     tester.jobs().last(app.instanceId(), productionUsEast3).get().versions());
        app.runJob(productionUsEast3);

        // Platform and revision now proceed together.
        app.runJob(stagingTest);
        app.triggerJobs();
        assertEquals(new Versions(version1, revision1.get(), Optional.of(version0), revision0),
                     tester.jobs().last(app.instanceId(), productionUsWest1).get().versions());
        app.runJob(productionUsWest1);
        assertEquals(Change.empty(), app.instance().change());
    }

    @Test
    public void testProductionTestBlockingDeploymentWithSeparateRollout() {
        var appPackage = new ApplicationPackageBuilder().region("us-east-3")
                                                        .region("us-west-1")
                                                        .delay(Duration.ofHours(1))
                                                        .test("us-east-3")
                                                        .upgradeRollout("separate")
                                                        .build();
        var app = tester.newDeploymentContext().submit(appPackage)
                        .runJob(systemTest).runJob(stagingTest)
                        .runJob(productionUsEast3).runJob(productionUsWest1);
        tester.clock().advance(Duration.ofHours(1));
        app.runJob(testUsEast3);
        assertEquals(Change.empty(), app.instance().change());

        // Platform rolls through first production zone.
        var version0 = tester.controller().readSystemVersion();
        var version1 = new Version("7.1");
        tester.controllerTester().upgradeSystem(version1);
        tester.upgrader().maintain();
        app.runJob(systemTest).runJob(stagingTest).runJob(productionUsEast3);

        // Revision starts rolling, but waits for production test to verify the upgrade.
        var revision0 = app.lastSubmission();
        app.submit(appPackage);
        var revision1 = app.lastSubmission();
        assertEquals(Change.of(version1).with(revision1.get()), app.instance().change());
        app.runJob(systemTest).runJob(stagingTest).triggerJobs();
        app.assertRunning(productionUsWest1);
        app.assertNotRunning(productionUsEast3);

        // Upgrade got here first, so attempts to proceed alone, but the upgrade fails.
        app.triggerJobs();
        assertEquals(new Versions(version1, revision0.get(), Optional.of(version0), revision0),
                     tester.jobs().last(app.instanceId(), productionUsWest1).get().versions());
        app.timeOutConvergence(productionUsWest1).triggerJobs();

        // Upgrade now fails between us-east-3 deployment and test, so test is abandoned, and revision unblocked.
        app.assertRunning(productionUsEast3);
        assertEquals(new Versions(version1, revision1.get(), Optional.of(version1), revision0),
                     tester.jobs().last(app.instanceId(), productionUsEast3).get().versions());
        app.runJob(productionUsEast3).triggerJobs()
                .jobAborted(productionUsWest1).runJob(productionUsWest1);
        tester.clock().advance(Duration.ofHours(1));
        app.runJob(testUsEast3);
        assertEquals(Change.empty(), app.instance().change());
    }

    @Test
    public void testProductionTestNotBlockingDeploymentWithSimultaneousRollout() {
        var appPackage = new ApplicationPackageBuilder().region("us-east-3")
                                                        .region("us-central-1")
                                                        .region("us-west-1")
                                                        .delay(Duration.ofHours(1))
                                                        .test("us-east-3")
                                                        .test("us-west-1")
                                                        .upgradeRollout("simultaneous")
                                                        .build();
        var app = tester.newDeploymentContext().submit(appPackage)
                .runJob(systemTest).runJob(stagingTest)
                .runJob(productionUsEast3).runJob(productionUsCentral1).runJob(productionUsWest1);
        tester.clock().advance(Duration.ofHours(1));
        app.runJob(testUsEast3).runJob(testUsWest1);
        assertEquals(Change.empty(), app.instance().change());

        // Platform rolls through first production zone.
        var version0 = tester.controller().readSystemVersion();
        var version1 = new Version("7.1");
        tester.controllerTester().upgradeSystem(version1);
        tester.upgrader().maintain();
        app.runJob(systemTest).runJob(stagingTest).runJob(productionUsEast3);

        // Revision starts rolling, and causes production test to abort when it reaches deployment.
        var revision0 = app.lastSubmission();
        app.submit(appPackage);
        var revision1 = app.lastSubmission();
        assertEquals(Change.of(version1).with(revision1.get()), app.instance().change());
        app.runJob(systemTest).runJob(stagingTest).triggerJobs();
        app.assertRunning(productionUsCentral1);
        app.assertRunning(productionUsEast3);

        // Revision deploys to first prod zone.
        app.triggerJobs();
        assertEquals(new Versions(version1, revision1.get(), Optional.of(version1), revision0),
                     tester.jobs().last(app.instanceId(), productionUsEast3).get().versions());
        tester.clock().advance(Duration.ofSeconds(1));
        app.runJob(productionUsEast3);

        // Revision catches up in second prod zone.
        app.runJob(systemTest).runJob(stagingTest).runJob(stagingTest).triggerJobs();
        app.jobAborted(productionUsCentral1).triggerJobs();
        assertEquals(new Versions(version1, revision1.get(), Optional.of(version0), revision0),
                     tester.jobs().last(app.instanceId(), productionUsCentral1).get().versions());
        app.runJob(productionUsCentral1).triggerJobs();

        // Revision proceeds alone in third prod zone, making test targets different for the two prod tests.
        assertEquals(new Versions(version0, revision1.get(), Optional.of(version0), revision0),
                     tester.jobs().last(app.instanceId(), productionUsWest1).get().versions());
        app.runJob(productionUsWest1);
        app.triggerJobs();
        app.assertNotRunning(testUsEast3);
        tester.clock().advance(Duration.ofHours(1));

        // Test lets revision proceed alone, and us-west-1 is blocked until tested.
        app.runJob(testUsEast3).triggerJobs();
        app.assertNotRunning(productionUsWest1);
        app.runJob(testUsWest1).runJob(productionUsWest1).runJob(testUsWest1); // Test for us-east-3 is not re-run.
        assertEquals(Change.empty(), app.instance().change());
    }

    @Test
    public void testsVeryLengthyPipeline() {
        String lengthyDeploymentSpec =
                "<deployment version='1.0'>\n" +
                "    <instance id='alpha'>\n" +
                "        <test />\n" +
                "        <staging />\n" +
                "        <upgrade rollout='simultaneous' />\n" +
                "        <prod>\n" +
                "            <region>us-east-3</region>\n" +
                "            <test>us-east-3</test>\n" +
                "        </prod>\n" +
                "    </instance>\n" +
                "    <instance id='beta'>\n" +
                "        <upgrade rollout='simultaneous' />\n" +
                "        <prod>\n" +
                "            <region>us-east-3</region>\n" +
                "            <test>us-east-3</test>\n" +
                "        </prod>\n" +
                "    </instance>\n" +
                "    <instance id='gamma'>\n" +
                "        <upgrade rollout='separate' />\n" +
                "        <prod>\n" +
                "            <region>us-east-3</region>\n" +
                "            <test>us-east-3</test>\n" +
                "        </prod>\n" +
                "    </instance>\n" +
                "</deployment>\n";
        var appPackage = ApplicationPackageBuilder.fromDeploymentXml(lengthyDeploymentSpec);
        var alpha = tester.newDeploymentContext("t", "a", "alpha");
        var beta  = tester.newDeploymentContext("t", "a", "beta");
        var gamma = tester.newDeploymentContext("t", "a", "gamma");
        alpha.submit(appPackage).deploy();

        // A version releases, but when the first upgrade has gotten through alpha, beta, and gamma, a newer version has high confidence.
        var version0 = tester.controller().readSystemVersion();
        var version1 = new Version("7.1");
        var version2 = new Version("7.2");
        tester.controllerTester().upgradeSystem(version1);

        tester.upgrader().maintain();
        alpha.runJob(systemTest).runJob(stagingTest)
             .runJob(productionUsEast3).runJob(testUsEast3);
        assertEquals(Change.empty(), alpha.instance().change());

        tester.upgrader().maintain();
        beta.runJob(productionUsEast3);
        tester.controllerTester().upgradeSystem(version2);
        beta.runJob(testUsEast3);
        assertEquals(Change.empty(), beta.instance().change());

        tester.upgrader().maintain();
        assertEquals(Change.of(version2), alpha.instance().change());
        assertEquals(Change.empty(), beta.instance().change());
        assertEquals(Change.of(version1), gamma.instance().change());
    }

    @Test
    public void testRevisionJoinsUpgradeWithLeadingRollout() {
        var appPackage = new ApplicationPackageBuilder().region("us-central-1")
                                                        .region("us-east-3")
                                                        .region("us-west-1")
                                                        .upgradeRollout("leading")
                                                        .build();
        var app = tester.newDeploymentContext().submit(appPackage).deploy();

        // Platform rolls through first production zone.
        var version0 = tester.controller().readSystemVersion();
        var version1 = new Version("7.1");
        tester.controllerTester().upgradeSystem(version1);
        tester.upgrader().maintain();
        app.runJob(systemTest).runJob(stagingTest).runJob(productionUsCentral1);
        tester.clock().advance(Duration.ofMinutes(1));

        // Revision starts rolling, and catches up.
        var revision0 = app.lastSubmission();
        app.submit(appPackage);
        var revision1 = app.lastSubmission();
        assertEquals(Change.of(version1).with(revision1.get()), app.instance().change());
        app.runJob(systemTest).runJob(stagingTest).runJob(productionUsCentral1);

        // Upgrade got here first, and has triggered, but is now obsolete.
        app.triggerJobs();
        assertEquals(new Versions(version1, revision0.get(), Optional.of(version0), revision0),
                     tester.jobs().last(app.instanceId(), productionUsEast3).get().versions());
        assertEquals(RunStatus.running, tester.jobs().last(app.instanceId(), productionUsEast3).get().status());

        // Once staging tests verify the joint upgrade, the job is replaced with that.
        app.runJob(stagingTest);
        app.triggerJobs();
        app.jobAborted(productionUsEast3).runJob(productionUsEast3);
        assertEquals(new Versions(version1, revision1.get(), Optional.of(version0), revision0),
                     tester.jobs().last(app.instanceId(), productionUsEast3).get().versions());

        // Platform and revision now proceed together.
        app.triggerJobs();
        assertEquals(new Versions(version1, revision1.get(), Optional.of(version0), revision0),
                     tester.jobs().last(app.instanceId(), productionUsWest1).get().versions());
        app.runJob(productionUsWest1);
        assertEquals(Change.empty(), app.instance().change());
    }

    @Test
    public void testRevisionPassesUpgradeWithSimultaneousRollout() {
        var appPackage = new ApplicationPackageBuilder().region("us-central-1")
                                                        .region("us-east-3")
                                                        .region("us-west-1")
                                                        .upgradeRollout("simultaneous")
                                                        .build();
        var app = tester.newDeploymentContext().submit(appPackage).deploy();

        // Platform rolls through first production zone.
        var version0 = tester.controller().readSystemVersion();
        var version1 = new Version("7.1");
        tester.controllerTester().upgradeSystem(version1);
        tester.upgrader().maintain();
        app.runJob(systemTest).runJob(stagingTest).runJob(productionUsCentral1);
        tester.clock().advance(Duration.ofMinutes(1));

        // Revision starts rolling, and catches up.
        var revision0 = app.lastSubmission();
        app.submit(appPackage);
        var revision1 = app.lastSubmission();
        assertEquals(Change.of(version1).with(revision1.get()), app.instance().change());
        app.runJob(systemTest).runJob(stagingTest).runJob(productionUsCentral1);

        // Upgrade got here first, and has triggered, but is now obsolete.
        app.triggerJobs();
        app.assertRunning(productionUsEast3);
        assertEquals(new Versions(version1, revision0.get(), Optional.of(version0), revision0),
                     tester.jobs().last(app.instanceId(), productionUsEast3).get().versions());
        assertEquals(RunStatus.running, tester.jobs().last(app.instanceId(), productionUsEast3).get().status());

        // Once staging tests verify the joint upgrade, the job is replaced with that.
        app.runJob(systemTest).runJob(stagingTest).runJob(stagingTest);
        app.triggerJobs();
        app.jobAborted(productionUsEast3).runJob(productionUsEast3);
        assertEquals(new Versions(version1, revision1.get(), Optional.of(version0), revision0),
                     tester.jobs().last(app.instanceId(), productionUsEast3).get().versions());

        // Revision now proceeds alone.
        app.triggerJobs();
        assertEquals(new Versions(version0, revision1.get(), Optional.of(version0), revision0),
                     tester.jobs().last(app.instanceId(), productionUsWest1).get().versions());
        app.runJob(productionUsWest1);

        // Upgrade follows.
        app.triggerJobs();
        assertEquals(new Versions(version1, revision1.get(), Optional.of(version0), revision1),
                     tester.jobs().last(app.instanceId(), productionUsWest1).get().versions());
        app.runJob(productionUsWest1);
        assertEquals(Change.empty(), app.instance().change());
    }

    @Test
    public void mixedDirectAndPipelineJobsInProduction() {
        ApplicationPackage cdPackage = new ApplicationPackageBuilder().region("cd-us-east-1")
                                                                      .region("cd-aws-us-east-1a")
                                                                      .build();
        var zones = List.of(ZoneId.from("test.cd-us-west-1"),
                            ZoneId.from("staging.cd-us-west-1"),
                            ZoneId.from("prod.cd-us-east-1"),
                            ZoneId.from("prod.cd-aws-us-east-1a"));
        tester.controllerTester()
              .setZones(zones, SystemName.cd)
              .setRoutingMethod(zones, RoutingMethod.sharedLayer4);
        tester.controllerTester().upgradeSystem(Version.fromString("6.1"));
        tester.controllerTester().computeVersionStatus();
        var app = tester.newDeploymentContext();

        app.runJob(productionCdUsEast1, cdPackage);
        app.submit(cdPackage);
        app.runJob(systemTest);
        // Staging test requires unknown initial version, and is broken.
        tester.controller().applications().deploymentTrigger().forceTrigger(app.instanceId(), productionCdUsEast1, "user", false);
        app.runJob(productionCdUsEast1)
           .abortJob(stagingTest) // Complete failing run.
           .runJob(stagingTest)   // Run staging-test for production zone with no prior deployment.
           .runJob(productionCdAwsUsEast1a);

        // Manually deploy to east again, then upgrade the system.
        app.runJob(productionCdUsEast1, cdPackage);
        var version = new Version("7.1");
        tester.controllerTester().upgradeSystem(version);
        tester.upgrader().maintain();
        // System and staging tests both require unknown versions, and are broken.
        tester.controller().applications().deploymentTrigger().forceTrigger(app.instanceId(), productionCdUsEast1, "user", false);
        app.runJob(productionCdUsEast1)
           .abortJob(systemTest)
           .jobAborted(stagingTest)
           .runJob(systemTest)  // Run test for aws zone again.
           .runJob(stagingTest) // Run test for aws zone again.
           .runJob(productionCdAwsUsEast1a);

        // Deploy manually again, then submit new package.
        app.runJob(productionCdUsEast1, cdPackage);
        app.submit(cdPackage);
        app.runJob(systemTest);
        // Staging test requires unknown initial version, and is broken.
        tester.controller().applications().deploymentTrigger().forceTrigger(app.instanceId(), productionCdUsEast1, "user", false);
        app.runJob(productionCdUsEast1)
           .jobAborted(stagingTest)
           .runJob(stagingTest)
           .runJob(productionCdAwsUsEast1a);
    }

    @Test
    public void testsInSeparateInstance() {
        String deploymentSpec =
                "<deployment version='1.0' athenz-domain='domain' athenz-service='service'>\n" +
                "    <instance id='canary'>\n" +
                "        <upgrade policy='canary' />\n" +
                "        <test />\n" +
                "        <staging />\n" +
                "    </instance>\n" +
                "    <instance id='default'>\n" +
                "        <prod>\n" +
                "            <region active='true'>eu-west-1</region>\n" +
                "            <test>eu-west-1</test>\n" +
                "        </prod>\n" +
                "    </instance>\n" +
                "</deployment>\n";

        ApplicationPackage applicationPackage = ApplicationPackageBuilder.fromDeploymentXml(deploymentSpec);
        var canary = tester.newDeploymentContext("t", "a", "canary").submit(applicationPackage);
        var conservative = tester.newDeploymentContext("t", "a", "default");

        canary.runJob(systemTest)
              .runJob(stagingTest);
        conservative.runJob(productionEuWest1)
                    .runJob(testEuWest1);

        canary.submit(applicationPackage)
              .runJob(systemTest)
              .runJob(stagingTest);
        tester.outstandingChangeDeployer().run();
        conservative.runJob(productionEuWest1)
                    .runJob(testEuWest1);

        tester.controllerTester().upgradeSystem(new Version("7.7.7"));
        tester.upgrader().maintain();

        canary.runJob(systemTest)
              .runJob(stagingTest);
        tester.upgrader().maintain();
        conservative.runJob(productionEuWest1)
                    .runJob(testEuWest1);

    }

    @Test
    public void testEagerTests() {
        var app = tester.newDeploymentContext().submit().deploy();

        // Start upgrade, then receive new submission.
        Version version1 = new Version("7.8.9");
        ApplicationVersion build1 = app.lastSubmission().get();
        tester.controllerTester().upgradeSystem(version1);
        tester.upgrader().maintain();
        app.runJob(stagingTest);
        app.submit();
        ApplicationVersion build2 = app.lastSubmission().get();
        assertNotEquals(build1, build2);

        // App now free to start system tests eagerly, for new submission. These should run assuming upgrade succeeds.
        tester.triggerJobs();
        app.assertRunning(stagingTest);
        assertEquals(version1,
                     app.instanceJobs().get(stagingTest).lastCompleted().get().versions().targetPlatform());
        assertEquals(build1,
                     app.instanceJobs().get(stagingTest).lastCompleted().get().versions().targetApplication());

        assertEquals(version1,
                     app.instanceJobs().get(stagingTest).lastTriggered().get().versions().sourcePlatform().get());
        assertEquals(build1,
                     app.instanceJobs().get(stagingTest).lastTriggered().get().versions().sourceApplication().get());
        assertEquals(version1,
                     app.instanceJobs().get(stagingTest).lastTriggered().get().versions().targetPlatform());
        assertEquals(build2,
                     app.instanceJobs().get(stagingTest).lastTriggered().get().versions().targetApplication());

        // App completes upgrade, and outstanding change is triggered. This should let relevant, running jobs finish.
        app.runJob(systemTest)
           .runJob(productionUsCentral1)
           .runJob(productionUsEast3)
           .runJob(productionUsWest1);
        tester.outstandingChangeDeployer().run();

        assertEquals(RunStatus.running, tester.jobs().last(app.instanceId(), stagingTest).get().status());
        app.runJob(stagingTest);
        tester.triggerJobs();
        app.assertNotRunning(stagingTest);
    }

    @Test
    public void testTriggeringOfIdleTestJobsWhenFirstDeploymentIsOnNewerVersionThanChange() {
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder().systemTest()
                                                                               .stagingTest()
                                                                               .region("us-east-3")
                                                                               .region("us-west-1")
                                                                               .build();
        var app = tester.newDeploymentContext().submit(applicationPackage).deploy();
        var appToAvoidVersionGC = tester.newDeploymentContext("g", "c", "default").submit().deploy();

        Version version2 = new Version("7.8.9");
        Version version3 = new Version("8.9.10");
        tester.controllerTester().upgradeSystem(version2);
        tester.deploymentTrigger().triggerChange(appToAvoidVersionGC.instanceId(), Change.of(version2));
        appToAvoidVersionGC.deployPlatform(version2);

        // app upgrades first zone to version3, and then the other two to version2.
        tester.controllerTester().upgradeSystem(version3);
        tester.deploymentTrigger().triggerChange(app.instanceId(), Change.of(version3));
        app.runJob(systemTest).runJob(stagingTest);
        tester.triggerJobs();
        tester.upgrader().overrideConfidence(version3, VespaVersion.Confidence.broken);
        tester.controllerTester().computeVersionStatus();
        tester.upgrader().run();
        assertEquals(Optional.of(version2), app.instance().change().platform());

        app.runJob(systemTest)
           .runJob(productionUsEast3)
           .runJob(stagingTest)
           .runJob(productionUsWest1);

        assertEquals(version3, app.instanceJobs().get(productionUsEast3).lastSuccess().get().versions().targetPlatform());
        assertEquals(version2, app.instanceJobs().get(productionUsWest1).lastSuccess().get().versions().targetPlatform());
        assertEquals(Map.of(), app.deploymentStatus().jobsToRun());
        assertEquals(Change.empty(), app.instance().change());
        assertEquals(List.of(), tester.jobs().active());
    }

    @Test
    public void testRetriggerQueue() {
        var app = tester.newDeploymentContext().submit().deploy();
        app.submit();
        tester.triggerJobs();

        tester.deploymentTrigger().reTrigger(app.instanceId(), productionUsEast3);
        tester.deploymentTrigger().reTriggerOrAddToQueue(app.deploymentIdIn(ZoneId.from("prod", "us-east-3")));
        tester.deploymentTrigger().reTriggerOrAddToQueue(app.deploymentIdIn(ZoneId.from("prod", "us-east-3")));

        List<RetriggerEntry> retriggerEntries = tester.controller().curator().readRetriggerEntries();
        Assert.assertEquals(1, retriggerEntries.size());
    }
}
