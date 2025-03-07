// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server;

import com.yahoo.cloud.config.ConfigserverConfig;
import com.yahoo.component.Version;
import com.yahoo.config.ConfigInstance;
import com.yahoo.config.SimpletypesConfig;
import com.yahoo.config.application.api.ApplicationMetaData;
import com.yahoo.config.model.NullConfigModelRegistry;
import com.yahoo.config.model.application.provider.FilesApplicationPackage;
import com.yahoo.config.provision.AllocatedHosts;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ApplicationName;
import com.yahoo.config.provision.Deployment;
import com.yahoo.config.provision.HostFilter;
import com.yahoo.config.provision.HostSpec;
import com.yahoo.config.provision.InstanceName;
import com.yahoo.config.provision.NetworkPorts;
import com.yahoo.config.provision.TenantName;
import com.yahoo.config.provision.exception.ActivationConflictException;
import com.yahoo.container.jdisc.HttpResponse;
import com.yahoo.io.IOUtils;
import com.yahoo.jdisc.Metric;
import com.yahoo.path.Path;
import com.yahoo.test.ManualClock;
import com.yahoo.text.Utf8;
import com.yahoo.vespa.config.ConfigKey;
import com.yahoo.vespa.config.ConfigPayload;
import com.yahoo.vespa.config.GetConfigRequest;
import com.yahoo.vespa.config.PayloadChecksums;
import com.yahoo.vespa.config.protocol.ConfigResponse;
import com.yahoo.vespa.config.protocol.DefContent;
import com.yahoo.vespa.config.protocol.VespaVersion;
import com.yahoo.vespa.config.server.application.OrchestratorMock;
import com.yahoo.vespa.config.server.deploy.DeployTester;
import com.yahoo.vespa.config.server.deploy.TenantFileSystemDirs;
import com.yahoo.vespa.config.server.filedistribution.MockFileDistributionFactory;
import com.yahoo.vespa.config.server.http.v2.PrepareResult;
import com.yahoo.vespa.config.server.session.LocalSession;
import com.yahoo.vespa.config.server.session.PrepareParams;
import com.yahoo.vespa.config.server.session.Session;
import com.yahoo.vespa.config.server.session.SessionRepository;
import com.yahoo.vespa.config.server.session.SessionZooKeeperClient;
import com.yahoo.vespa.config.server.tenant.Tenant;
import com.yahoo.vespa.config.server.tenant.TenantMetaData;
import com.yahoo.vespa.config.server.tenant.TenantRepository;
import com.yahoo.vespa.config.server.tenant.TestTenantRepository;
import com.yahoo.vespa.config.util.ConfigUtils;
import com.yahoo.vespa.curator.Curator;
import com.yahoo.vespa.curator.mock.MockCurator;
import com.yahoo.vespa.flags.InMemoryFlagSource;
import com.yahoo.vespa.model.VespaModelFactory;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @author hmusum
 */
public class ApplicationRepositoryTest {

    private final static File testApp = new File("src/test/apps/app");
    private final static File testAppJdiscOnly = new File("src/test/apps/app-jdisc-only");
    private final static File testAppJdiscOnlyRestart = new File("src/test/apps/app-jdisc-only-restart");
    private final static File testAppLogServerWithContainer = new File("src/test/apps/app-logserver-with-container");
    private final static File app1 = new File("src/test/apps/cs1");
    private final static File app2 = new File("src/test/apps/cs2");

    private final static TenantName tenant1 = TenantName.from("test1");
    private final static TenantName tenant2 = TenantName.from("test2");
    private final static ManualClock clock = new ManualClock(Instant.now());

    private ApplicationRepository applicationRepository;
    private TenantRepository tenantRepository;
    private MockProvisioner provisioner;
    private OrchestratorMock orchestrator;
    private TimeoutBudget timeoutBudget;
    private Curator curator;
    private ConfigserverConfig configserverConfig;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @SuppressWarnings("deprecation")
    @Rule
    public ExpectedException exceptionRule = ExpectedException.none();

    @Before
    public void setup() throws IOException {
        curator = new MockCurator();
        configserverConfig = new ConfigserverConfig.Builder()
                .payloadCompressionType(ConfigserverConfig.PayloadCompressionType.Enum.UNCOMPRESSED)
                .configServerDBDir(temporaryFolder.newFolder().getAbsolutePath())
                .configDefinitionsDir(temporaryFolder.newFolder().getAbsolutePath())
                .fileReferencesDir(temporaryFolder.newFolder().getAbsolutePath())
                .build();
        tenantRepository = new TestTenantRepository.Builder()
                .withClock(clock)
                .withConfigserverConfig(configserverConfig)
                .withCurator(curator)
                .withFileDistributionFactory(new MockFileDistributionFactory(configserverConfig))
                .withFlagSource(new InMemoryFlagSource())
                .build();
        tenantRepository.addTenant(TenantRepository.HOSTED_VESPA_TENANT);
        tenantRepository.addTenant(tenant1);
        tenantRepository.addTenant(tenant2);
        orchestrator = new OrchestratorMock();
        provisioner = new MockProvisioner();
        applicationRepository = new ApplicationRepository.Builder()
                .withTenantRepository(tenantRepository)
                .withProvisioner(provisioner)
                .withConfigserverConfig(configserverConfig)
                .withOrchestrator(orchestrator)
                .withLogRetriever(new MockLogRetriever())
                .withClock(clock)
                .build();
        timeoutBudget = new TimeoutBudget(clock, Duration.ofSeconds(60));
    }

    @Test
    public void prepareAndActivateWithTenantMetaData() {
        long startTime = clock.instant().toEpochMilli();
        Duration duration = Duration.ofHours(1);
        clock.advance(duration);
        long deployTime = clock.instant().toEpochMilli();
        PrepareResult result = prepareAndActivate(testApp);
        assertTrue(result.configChangeActions().getRefeedActions().isEmpty());
        assertTrue(result.configChangeActions().getReindexActions().isEmpty());
        assertTrue(result.configChangeActions().getRestartActions().isEmpty());

        Session session = applicationRepository.getActiveLocalSession(tenant(), applicationId());
        session.getAllocatedHosts();

        assertEquals(startTime, tenantMetaData(tenant()).createdTimestamp().toEpochMilli());
        assertEquals(deployTime, tenantMetaData(tenant()).lastDeployTimestamp().toEpochMilli());

        // Creating a new tenant will have metadata with timestamp equal to current time
        clock.advance(duration);
        long createTenantTime = clock.instant().toEpochMilli();
        Tenant fooTenant = tenantRepository.addTenant(TenantName.from("foo"));
        assertEquals(createTenantTime, tenantMetaData(fooTenant).createdTimestamp().toEpochMilli());
        assertEquals(createTenantTime, tenantMetaData(fooTenant).lastDeployTimestamp().toEpochMilli());
    }

    @Test
    public void prepareAndActivateWithRestart() {
        applicationRepository = new ApplicationRepository.Builder()
                .withTenantRepository(tenantRepository)
                .withProvisioner(provisioner)
                .withConfigserverConfig(configserverConfig)
                .withOrchestrator(orchestrator)
                .withLogRetriever(new MockLogRetriever())
                .withClock(clock)
                .withConfigConvergenceChecker(new MockConfigConvergenceChecker(2))
                .build();

        prepareAndActivate(testAppJdiscOnly);
        PrepareResult result = prepareAndActivate(testAppJdiscOnlyRestart);
        assertTrue(result.configChangeActions().getRefeedActions().isEmpty());
        assertTrue(result.configChangeActions().getRestartActions().isEmpty());
        assertEquals(HostFilter.hostname("mytesthost2"), provisioner.lastRestartFilter());
    }

    @Test
    public void prepareAndActivateWithRestartWithoutProvisioner() {
        applicationRepository = new ApplicationRepository.Builder()
                .withTenantRepository(tenantRepository)
                .withOrchestrator(orchestrator)
                .withProvisioner(null)
                .build();

        prepareAndActivate(testAppJdiscOnly);
        PrepareResult result = prepareAndActivate(testAppJdiscOnlyRestart);
        assertTrue(result.configChangeActions().getRefeedActions().isEmpty());
        assertFalse(result.configChangeActions().getRestartActions().isEmpty());
    }

    @Test
    public void createAndPrepareAndActivate() {
        PrepareResult result = deployApp(testApp);
        assertTrue(result.configChangeActions().getRefeedActions().isEmpty());
        assertTrue(result.configChangeActions().getRestartActions().isEmpty());
    }

    @Test
    public void redeploy() {
        long firstSessionId = deployApp(testApp).sessionId();

        long secondSessionId = deployApp(testApp).sessionId();
        assertNotEquals(firstSessionId, secondSessionId);

        Session session = applicationRepository.getActiveLocalSession(tenant(), applicationId());
        assertEquals(firstSessionId, session.getMetaData().getPreviousActiveGeneration());
    }

    @Test
    public void createFromActiveSession() {
        long originalSessionId = deployApp(testApp).sessionId();

        long sessionId = createSessionFromExisting(applicationId(), timeoutBudget);
        ApplicationMetaData originalApplicationMetaData = getApplicationMetaData(applicationId(), originalSessionId);
        ApplicationMetaData applicationMetaData = getApplicationMetaData(applicationId(), sessionId);

        assertNotEquals(sessionId, originalSessionId);
        assertEquals(originalApplicationMetaData.getApplicationId(), applicationMetaData.getApplicationId());
        assertEquals(originalApplicationMetaData.getGeneration().longValue(), applicationMetaData.getPreviousActiveGeneration());
        assertNotEquals(originalApplicationMetaData.getGeneration(), applicationMetaData.getGeneration());
        assertEquals(originalApplicationMetaData.getDeployedByUser(), applicationMetaData.getDeployedByUser());
    }

    @Test
    public void testSuspension() {
        deployApp(testApp);
        assertFalse(applicationRepository.isSuspended(applicationId()));
        orchestrator.suspend(applicationId());
        assertTrue(applicationRepository.isSuspended(applicationId()));
    }

    @Test
    public void getLogs() {
        deployApp(testAppLogServerWithContainer);
        HttpResponse response = applicationRepository.getLogs(applicationId(), Optional.empty(), "");
        assertEquals(200, response.getStatus());
    }

    @Test
    public void getLogsForHostname() {
        ApplicationId applicationId = ApplicationId.from("hosted-vespa", "tenant-host", "default");
        deployApp(testAppLogServerWithContainer, new PrepareParams.Builder().applicationId(applicationId).build());
        HttpResponse response = applicationRepository.getLogs(applicationId, Optional.of("localhost"), "");
        assertEquals(200, response.getStatus());
    }

    @Test
    public void deleteUnusedFileReferences() throws IOException {
        File fileReferencesDir = temporaryFolder.newFolder();
        Duration keepFileReferencesDuration = Duration.ofSeconds(4);

        // Add file reference that is not in use and should be deleted (older than 'keepFileReferencesDuration')
        File filereferenceDirOldest = createFilereferenceOnDisk(new File(fileReferencesDir, "foo"));
        clock.advance(Duration.ofSeconds(1));

        // Add file references that are not in use and could be deleted
        IntStream.range(0, 3).forEach(i -> {
            try {
                createFilereferenceOnDisk(new File(fileReferencesDir, "bar" + i));
            } catch (IOException e) {
                fail(e.getMessage());
            }
            clock.advance(Duration.ofSeconds(1));
        });
        clock.advance(keepFileReferencesDuration);

        // Add file reference that is not in use, but should not be deleted (newer than 'keepFileReferencesDuration')
        File filereferenceDirNewest = createFilereferenceOnDisk(new File(fileReferencesDir, "baz"));

        applicationRepository = new ApplicationRepository.Builder()
                .withTenantRepository(tenantRepository)
                .withProvisioner(provisioner)
                .withOrchestrator(orchestrator)
                .withClock(clock)
                .build();

        // TODO: Deploy an app with a bundle or file that will be a file reference, too much missing in test setup to get this working now
        PrepareParams prepareParams = new PrepareParams.Builder().applicationId(applicationId()).ignoreValidationErrors(true).build();
        deployApp(new File("src/test/apps/app"), prepareParams);

        List<String> toBeDeleted = applicationRepository.deleteUnusedFileDistributionReferences(fileReferencesDir,
                                                                                                keepFileReferencesDuration,
                                                                                                2);
        Collections.sort(toBeDeleted);
        assertEquals(List.of("bar0", "foo"), toBeDeleted);
        // bar0 and foo are the only ones that will be deleted (keeps 2 newest no matter how old they are)
        assertFalse(filereferenceDirOldest.exists());
        assertFalse(new File(fileReferencesDir, "bar0").exists());
        assertTrue(filereferenceDirNewest.exists());
    }

    private File createFilereferenceOnDisk(File filereferenceDir) throws IOException {
        assertTrue(filereferenceDir.mkdir());
        File file = new File(filereferenceDir, "bar");
        IOUtils.writeFile(file, Utf8.toBytes("test"));
        Files.setAttribute(filereferenceDir.toPath(), "lastAccessTime", FileTime.from(clock.instant()));
        return filereferenceDir;
    }

    @Test
    public void delete() {
        SessionRepository sessionRepository = tenant().getSessionRepository();
        {
            PrepareResult result = deployApp(testApp);
            long sessionId = result.sessionId();
            Session applicationData = sessionRepository.getLocalSession(sessionId);
            assertNotNull(applicationData);
            assertNotNull(applicationData.getApplicationId());
            assertNotNull(sessionRepository.getLocalSession(sessionId));
            assertNotNull(applicationRepository.getActiveSession(applicationId()));
            Path sessionNode = sessionRepository.getSessionPath(sessionId);
            assertTrue(curator.exists(sessionNode));
            TenantFileSystemDirs tenantFileSystemDirs = tenant().getApplicationRepo().getTenantFileSystemDirs();
            File sessionFile = new File(tenantFileSystemDirs.sessionsPath(), String.valueOf(sessionId));
            assertTrue(sessionFile.exists());

            // Delete app and verify that it has been deleted from repos and provisioner and no application set exists
            assertTrue(applicationRepository.delete(applicationId()));
            assertNull(applicationRepository.getActiveSession(applicationId()));
            assertEquals(Optional.empty(), sessionRepository.getRemoteSession(sessionId).applicationSet());
            assertTrue(provisioner.removed());
            assertEquals(tenant().getName(), provisioner.lastApplicationId().tenant());
            assertEquals(applicationId(), provisioner.lastApplicationId());
            assertTrue(curator.exists(sessionNode));
            assertEquals(Session.Status.DELETE.name(), Utf8.toString(curator.getData(sessionNode.append("sessionState")).get()));
            assertTrue(sessionFile.exists());

            assertFalse(applicationRepository.delete(applicationId()));
        }

        {
            deployApp(testApp);
            assertTrue(applicationRepository.delete(applicationId()));
            deployApp(testApp);

            // Deploy another app (with id fooId)
            ApplicationId fooId = applicationId(tenant2);
            PrepareParams prepareParams2 = new PrepareParams.Builder().applicationId(fooId).build();
            deployApp(testAppJdiscOnly, prepareParams2);
            assertNotNull(applicationRepository.getActiveSession(fooId));

            // Delete app with id fooId, should not affect original app
            assertTrue(applicationRepository.delete(fooId));
            assertEquals(fooId, provisioner.lastApplicationId());
            assertNotNull(applicationRepository.getActiveSession(applicationId()));

            assertTrue(applicationRepository.delete(applicationId()));
        }

        // If delete fails, a retry should work if the failure is transient and zookeeper state should be consistent
        {
            long sessionId = deployApp(testApp).sessionId();
            assertNotNull(sessionRepository.getRemoteSession(sessionId));
            assertNotNull(applicationRepository.getActiveSession(applicationId()));
            assertEquals(sessionId, applicationRepository.getActiveSession(applicationId()).getSessionId());
            assertNotNull(applicationRepository.getApplication(applicationId()));

            provisioner.failureOnRemove(true);
            try {
                applicationRepository.delete(applicationId());
                fail("Should fail with RuntimeException");
            } catch (RuntimeException e) {
                // ignore
            }
            assertNotNull(sessionRepository.getRemoteSession(sessionId));
            assertNotNull(applicationRepository.getActiveSession(applicationId()));
            assertEquals(sessionId, applicationRepository.getActiveSession(applicationId()).getSessionId());

            // Delete should work when there is no failure anymore
            provisioner.failureOnRemove(false);
            assertTrue(applicationRepository.delete(applicationId()));

            // Session should be in state DELETE
            Path sessionNode = sessionRepository.getSessionPath(sessionId);
            assertEquals(Session.Status.DELETE.name(), Utf8.toString(curator.getData(sessionNode.append("sessionState")).get()));
            assertNotNull(sessionRepository.getRemoteSession(sessionId)); // session still exists
            assertNull(applicationRepository.getActiveSession(applicationId())); // but it is not active
            try {
                applicationRepository.getApplication(applicationId());
                fail("Should fail with NotFoundException, application should not exist");
            } catch (NotFoundException e) {
                // ignore
            }
        }
    }

    @Test
    public void testDeletingInactiveSessions() throws IOException {
        File serverdb = temporaryFolder.newFolder("serverdb");
        ConfigserverConfig configserverConfig =
                new ConfigserverConfig(new ConfigserverConfig.Builder()
                                               .configServerDBDir(serverdb.getAbsolutePath())
                                               .configDefinitionsDir(temporaryFolder.newFolder("configdefinitions").getAbsolutePath())
                                               .fileReferencesDir(temporaryFolder.newFolder("filedistribution").getAbsolutePath())
                                               .sessionLifetime(60));
        DeployTester tester = new DeployTester.Builder().configserverConfig(configserverConfig).clock(clock).build();
        tester.deployApp("src/test/apps/app"); // session 2 (numbering starts at 2)

        clock.advance(Duration.ofSeconds(10));
        Optional<Deployment> deployment2 = tester.redeployFromLocalActive();

        assertTrue(deployment2.isPresent());
        deployment2.get().activate(); // session 3
        long activeSessionId = tester.tenant().getApplicationRepo().requireActiveSessionOf(tester.applicationId());

        clock.advance(Duration.ofSeconds(10));
        Optional<com.yahoo.config.provision.Deployment> deployment3 = tester.redeployFromLocalActive();
        assertTrue(deployment3.isPresent());
        deployment3.get().prepare();  // session 4 (not activated)

        Session deployment3session = ((com.yahoo.vespa.config.server.deploy.Deployment) deployment3.get()).session();
        assertNotEquals(activeSessionId, deployment3session.getSessionId());
        // No change to active session id
        assertEquals(activeSessionId, tester.tenant().getApplicationRepo().requireActiveSessionOf(tester.applicationId()));
        SessionRepository sessionRepository = tester.tenant().getSessionRepository();
        assertEquals(3, sessionRepository.getLocalSessions().size());

        clock.advance(Duration.ofHours(1)); // longer than session lifetime

        // All sessions except 3 should be removed after the call to deleteExpiredLocalSessions
        tester.applicationRepository().deleteExpiredLocalSessions();
        Collection<LocalSession> sessions = sessionRepository.getLocalSessions();
        assertEquals(1, sessions.size());
        ArrayList<LocalSession> localSessions = new ArrayList<>(sessions);
        LocalSession localSession = localSessions.get(0);
        assertEquals(3, localSession.getSessionId());

        // All sessions except 3 should be removed after the call to deleteExpiredRemoteSessions
        assertEquals(2, tester.applicationRepository().deleteExpiredRemoteSessions(clock, Duration.ofSeconds(0)));
        ArrayList<Long> remoteSessions = new ArrayList<>(sessionRepository.getRemoteSessionsFromZooKeeper());
        Session remoteSession = sessionRepository.getRemoteSession(remoteSessions.get(0));
        assertEquals(3, remoteSession.getSessionId());

        // Deploy, but do not activate
        Optional<com.yahoo.config.provision.Deployment> deployment4 = tester.redeployFromLocalActive();
        assertTrue(deployment4.isPresent());
        deployment4.get().prepare();  // session 5 (not activated)

        assertEquals(2, sessionRepository.getLocalSessions().size());
        sessionRepository.deleteLocalSession(localSession);
        assertEquals(1, sessionRepository.getLocalSessions().size());

        // Create a local session without any data in zookeeper (corner case seen in production occasionally)
        // and check that expiring local sessions still works
        int sessionId = 6;
        TenantName tenantName = tester.tenant().getName();
        java.nio.file.Path dir = Files.createDirectory(new TenantFileSystemDirs(serverdb, tenantName).getUserApplicationDir(sessionId).toPath());
        LocalSession localSession2 = new LocalSession(tenant1,
                                                      sessionId,
                                                      FilesApplicationPackage.fromFile(testApp),
                                                      new SessionZooKeeperClient(curator,
                                                                                 tenantName,
                                                                                 sessionId,
                                                                                 ConfigUtils.getCanonicalHostName()));
        sessionRepository.addLocalSession(localSession2);
        assertEquals(2, sessionRepository.getLocalSessions().size());

        // Create a session, set status to UNKNOWN, we don't want to expire those (creation time is then EPOCH,
        // so will be candidate for expiry)
        Session session = sessionRepository.createRemoteSession(7);
        sessionRepository.createSetStatusTransaction(session, Session.Status.UNKNOWN);
        assertEquals(2, sessionRepository.getLocalSessions().size());  // Still 2, no new local session

        // Check that trying to expire local session when there exists a local session without any data in zookeeper
        // should not delete session if this is a new file ...
        deleteExpiredLocalSessionsAndAssertNumberOfSessions(2, tester, sessionRepository);

        // ... but it should be deleted if some time has passed
        clock.advance(Duration.ofSeconds(60));
        deleteExpiredLocalSessionsAndAssertNumberOfSessions(1, tester, sessionRepository);

        // Set older created timestamp for session dir for local session without any data in zookeeper, should be deleted
        setCreatedTime(dir, Instant.now().minus(Duration.ofDays(31)));
        deleteExpiredLocalSessionsAndAssertNumberOfSessions(0, tester, sessionRepository);
    }

    @Test
    public void testMetrics() {
        MockMetric actual = new MockMetric();
        applicationRepository = new ApplicationRepository.Builder()
                .withTenantRepository(tenantRepository)
                .withProvisioner(provisioner)
                .withOrchestrator(orchestrator)
                .withMetric(actual)
                .withClock(new ManualClock())
                .build();
        deployApp(testAppLogServerWithContainer);
        Map<String, ?> context = Map.of("applicationId", "test1.testapp.default",
                                        "tenantName", "test1",
                                        "app", "testapp.default",
                                        "zone", "prod.default");
        MockMetric expected = new MockMetric();
        expected.set("deployment.prepareMillis", 0L, expected.createContext(context));
        expected.set("deployment.activateMillis", 0L, expected.createContext(context));
        assertEquals(expected.values, actual.values);
    }

    @Test
    public void require_that_provision_info_can_be_read() {
        prepareAndActivate(testAppJdiscOnly);

        Tenant tenant = applicationRepository.getTenant(applicationId());
        Session session = applicationRepository.getActiveLocalSession(tenant, applicationId());

        List<NetworkPorts.Allocation> list = new ArrayList<>();
        list.add(new NetworkPorts.Allocation(8080, "container", "container/container.0", "http"));
        list.add(new NetworkPorts.Allocation(19070, "configserver", "admin/configservers/configserver.0", "rpc"));
        list.add(new NetworkPorts.Allocation(19071, "configserver", "admin/configservers/configserver.0", "http"));
        list.add(new NetworkPorts.Allocation(19080, "logserver", "admin/logserver", "rpc"));
        list.add(new NetworkPorts.Allocation(19081, "logserver", "admin/logserver", "unused/1"));
        list.add(new NetworkPorts.Allocation(19082, "logserver", "admin/logserver", "unused/2"));
        list.add(new NetworkPorts.Allocation(19083, "logserver", "admin/logserver", "unused/3"));
        list.add(new NetworkPorts.Allocation(19089, "logd", "hosts/mytesthost2/logd", "http"));
        list.add(new NetworkPorts.Allocation(19090, "configproxy", "hosts/mytesthost2/configproxy", "rpc"));
        list.add(new NetworkPorts.Allocation(19092, "metricsproxy-container", "admin/metrics/mytesthost2", "http"));
        list.add(new NetworkPorts.Allocation(19093, "metricsproxy-container", "admin/metrics/mytesthost2", "http/1"));
        list.add(new NetworkPorts.Allocation(19094, "metricsproxy-container", "admin/metrics/mytesthost2", "rpc/admin"));
        list.add(new NetworkPorts.Allocation(19095, "metricsproxy-container", "admin/metrics/mytesthost2", "rpc/metrics"));
        list.add(new NetworkPorts.Allocation(19097, "config-sentinel", "hosts/mytesthost2/sentinel", "rpc"));
        list.add(new NetworkPorts.Allocation(19098, "config-sentinel", "hosts/mytesthost2/sentinel", "http"));
        list.add(new NetworkPorts.Allocation(19099, "slobrok", "admin/slobrok.0", "rpc"));
        list.add(new NetworkPorts.Allocation(19100, "container", "container/container.0", "http/1"));
        list.add(new NetworkPorts.Allocation(19101, "container", "container/container.0", "messaging"));
        list.add(new NetworkPorts.Allocation(19102, "container", "container/container.0", "rpc/admin"));
        list.add(new NetworkPorts.Allocation(19103, "slobrok", "admin/slobrok.0", "http"));

        AllocatedHosts info = session.getAllocatedHosts();
        assertNotNull(info);
        assertEquals(1, info.getHosts().size());
        assertTrue(info.getHosts().contains(new HostSpec("mytesthost2",
                                                         Collections.emptyList(),
                                                         Optional.empty())));
        Optional<NetworkPorts> portsCopy = info.getHosts().iterator().next().networkPorts();
        assertTrue(portsCopy.isPresent());
        assertEquals(list, portsCopy.get().allocations());
    }

    @Test
    public void testActivationOfUnpreparedSession() {
        // Needed so we can test that the original active session is still active after a failed activation
        PrepareResult result = deployApp(testApp);
        long firstSession = result.sessionId();

        TimeoutBudget timeoutBudget = new TimeoutBudget(clock, Duration.ofSeconds(10));
        long sessionId = createSession(applicationId(), timeoutBudget, testAppJdiscOnly);
        exceptionRule.expect(IllegalArgumentException.class);
        exceptionRule.expectMessage("tenant:test1 Session 3 is not prepared");
        activate(applicationId(), sessionId, timeoutBudget);

        Session activeSession = applicationRepository.getActiveSession(applicationId());
        assertEquals(firstSession, activeSession.getSessionId());
        assertEquals(Session.Status.ACTIVATE, activeSession.getStatus());
    }

    @Test
    public void testActivationTimesOut() {
        // Needed so we can test that the original active session is still active after a failed activation
        long firstSession = deployApp(testAppJdiscOnly).sessionId();

        long sessionId = createSession(applicationId(), timeoutBudget, testAppJdiscOnly);
        applicationRepository.prepare(sessionId, prepareParams());
        exceptionRule.expect(RuntimeException.class);
        exceptionRule.expectMessage("Timeout exceeded when trying to activate 'test1.testapp'");
        activate(applicationId(), sessionId, new TimeoutBudget(clock, Duration.ofSeconds(0)));

        Session activeSession = applicationRepository.getActiveSession(applicationId());
        assertEquals(firstSession, activeSession.getSessionId());
        assertEquals(Session.Status.ACTIVATE, activeSession.getStatus());
    }

    @Test
    public void testActivationOfSessionCreatedFromNoLongerActiveSessionFails() {
        TimeoutBudget timeoutBudget = new TimeoutBudget(clock, Duration.ofSeconds(10));

        deployApp(testAppJdiscOnly);

        long sessionId2 = createSessionFromExisting(applicationId(), timeoutBudget);
        // Deploy and activate another session
        deployApp(testAppJdiscOnly);

        applicationRepository.prepare(sessionId2, prepareParams());
        exceptionRule.expect(ActivationConflictException.class);
        exceptionRule.expectMessage("app:test1.testapp.default Cannot activate session 3 because the currently active session (4) has changed since session 3 was created (was 2 at creation time)");
        activate(applicationId(), sessionId2, timeoutBudget);
    }

    @Test
    public void testPrepareAndActivateAlreadyActivatedSession() {
        PrepareResult result = deployApp(testAppJdiscOnly);
        long sessionId = result.sessionId();

        exceptionRule.expect(IllegalArgumentException.class);
        exceptionRule.expectMessage("Session is active: 2");
        applicationRepository.prepare(sessionId, prepareParams());

        exceptionRule.expect(IllegalArgumentException.class);
        exceptionRule.expectMessage("app:test1.testapp.default Session 2 is already active");
        activate(applicationId(), sessionId, timeoutBudget);
    }

    @Test
    public void testThatPreviousSessionIsDeactivated() {
        deployApp(testAppJdiscOnly);
        Session firstSession = applicationRepository.getActiveSession(applicationId());

        deployApp(testAppJdiscOnly);

        assertEquals(Session.Status.DEACTIVATE, firstSession.getStatus());
    }

    @Test
    public void testResolveForAppId() {
        Version vespaVersion = new VespaModelFactory(new NullConfigModelRegistry()).version();
        applicationRepository.deploy(app1, new PrepareParams.Builder()
                .applicationId(applicationId())
                .vespaVersion(vespaVersion)
                .build());

        SimpletypesConfig config = resolve(applicationId(), vespaVersion);
        assertEquals(1337, config.intval());
    }

    @Test
    public void testResolveConfigForMultipleApps() {
        Version vespaVersion = new VespaModelFactory(new NullConfigModelRegistry()).version();
        applicationRepository.deploy(app1, new PrepareParams.Builder()
                .applicationId(applicationId())
                .vespaVersion(vespaVersion)
                .build());

        ApplicationId appId2 = new ApplicationId.Builder()
                .tenant(tenant1)
                .applicationName("myapp2")
                .instanceName("default")
                .build();
        applicationRepository.deploy(app2, new PrepareParams.Builder()
                .applicationId(appId2)
                .vespaVersion(vespaVersion)
                .build());

        SimpletypesConfig config = resolve(applicationId(), vespaVersion);
        assertEquals(1337, config.intval());

        SimpletypesConfig config2 = resolve(appId2, vespaVersion);
        assertEquals(1330, config2.intval());

        RequestHandler requestHandler = getRequestHandler(applicationId());
        assertTrue(requestHandler.hasApplication(applicationId(), Optional.of(vespaVersion)));
        assertNull(requestHandler.resolveApplicationId("doesnotexist"));
        assertEquals(new ApplicationId.Builder().tenant(tenant1).applicationName("testapp").build(),
                     requestHandler.resolveApplicationId("mytesthost")); // Host set in application package.
    }

    @Test
    public void testResolveMultipleVersions() {
        Version vespaVersion = new VespaModelFactory(new NullConfigModelRegistry()).version();
        applicationRepository.deploy(app1, new PrepareParams.Builder()
                .applicationId(applicationId())
                .vespaVersion(vespaVersion)
                .build());

        SimpletypesConfig config = resolve(applicationId(), vespaVersion);
        assertEquals(1337, config.intval());

        // TODO: Revisit this test, I cannot see that we create a model for version 3.2.1
        config = resolve(applicationId(), new Version(3, 2, 1));
        assertEquals(1337, config.intval());
    }

    @Test
    public void testResolveForDeletedApp() {
        Version vespaVersion = new VespaModelFactory(new NullConfigModelRegistry()).version();
        applicationRepository.deploy(app1, new PrepareParams.Builder()
                .applicationId(applicationId())
                .vespaVersion(vespaVersion)
                .build());

        SimpletypesConfig config = resolve(applicationId(), vespaVersion);
        assertEquals(1337, config.intval());

        applicationRepository.delete(applicationId());

        exceptionRule.expect(com.yahoo.vespa.config.server.NotFoundException.class);
        exceptionRule.expectMessage("No such application id: test1.testapp");
        resolve(applicationId(), vespaVersion);
    }

    private PrepareResult prepareAndActivate(File application) {
        return applicationRepository.deploy(application, prepareParams());
    }

    private PrepareResult deployApp(File applicationPackage) {
        return deployApp(applicationPackage, prepareParams());
    }

    private PrepareResult deployApp(File applicationPackage, PrepareParams prepareParams) {
        return applicationRepository.deploy(applicationPackage, prepareParams);
    }

    private PrepareParams prepareParams() {
        return new PrepareParams.Builder().applicationId(applicationId()).build();
    }

    private ApplicationId applicationId() { return applicationId(tenant1); }

    private ApplicationId applicationId(TenantName tenantName) {
        return ApplicationId.from(tenantName, ApplicationName.from("testapp"), InstanceName.defaultName());
    }

    private Tenant tenant() { return applicationRepository.getTenant(applicationId()); }

    private ApplicationMetaData getApplicationMetaData(ApplicationId applicationId, long sessionId) {
        Tenant tenant = tenantRepository.getTenant(applicationId.tenant());
        return applicationRepository.getMetadataFromLocalSession(tenant, sessionId);
    }

    private void setCreatedTime(java.nio.file.Path file, Instant createdTime) {
        try {
            BasicFileAttributeView attributes = Files.getFileAttributeView(file, BasicFileAttributeView.class);
            FileTime time = FileTime.fromMillis(createdTime.toEpochMilli());
            attributes.setTimes(time, time, time);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Stores all added or set values for each metric and context. */
    static class MockMetric implements Metric {

        final Map<String, Map<Map<String, ?>, Number>> values = new HashMap<>();

        @Override
        public void set(String key, Number val, Metric.Context ctx) {
            values.putIfAbsent(key, new HashMap<>());
            values.get(key).put(((Context) ctx).point, val);
        }

        @Override
        public void add(String key, Number val, Metric.Context ctx) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Context createContext(Map<String, ?> properties) {
            return new Context(properties);
        }

        private static class Context implements Metric.Context {

            private final Map<String, ?> point;

            public Context(Map<String, ?> point) {
                this.point = Map.copyOf(point);
            }

        }

    }

    private SimpletypesConfig resolve(ApplicationId applicationId, Version vespaVersion) {
        String configId = "";
        RequestHandler requestHandler = getRequestHandler(applicationId);
        Class<SimpletypesConfig> clazz = SimpletypesConfig.class;
        ConfigResponse response = getConfigResponse(clazz, requestHandler, applicationId, vespaVersion, configId);
        return ConfigPayload.fromUtf8Array(response.getPayload()).toInstance(clazz, configId);
    }

    private <T extends ConfigInstance> ConfigResponse getConfigResponse(Class<T> clazz,
                                                                        RequestHandler applications,
                                                                        ApplicationId appId,
                                                                        Version vespaVersion,
                                                                        String configId) {
        return applications.resolveConfig(appId, new GetConfigRequest() {
            @Override
            public ConfigKey<T> getConfigKey() {
                return new ConfigKey<>(clazz, configId);
            }

            @Override
            public DefContent getDefContent() {
                return DefContent.fromClass(clazz);
            }

            @Override
            public Optional<VespaVersion> getVespaVersion() {
                return Optional.of(VespaVersion.fromString(vespaVersion.toFullString()));
            }

            @Override
            public boolean noCache() {
                return false;
            }

            @Override
            public String getRequestDefMd5() { return ""; }

            @Override
            public PayloadChecksums configPayloadChecksums() { return PayloadChecksums.empty(); }

        }, Optional.empty());
    }

    private RequestHandler getRequestHandler(ApplicationId applicationId) {
        return tenantRepository.getTenant(applicationId.tenant()).getRequestHandler();
    }

    private static void deleteExpiredLocalSessionsAndAssertNumberOfSessions(int expectedNumberOfSessions,
                                                                            DeployTester tester,
                                                                            SessionRepository sessionRepository) {
        tester.applicationRepository().deleteExpiredLocalSessions();
        assertEquals(expectedNumberOfSessions, sessionRepository.getLocalSessions().size());
    }

    private void activate(ApplicationId applicationId, long sessionId, TimeoutBudget timeoutBudget) {
        applicationRepository.activate(applicationRepository.getTenant(applicationId), sessionId, timeoutBudget, false);
    }

    private TenantMetaData tenantMetaData(Tenant tenant) {
        return applicationRepository.getTenantMetaData(tenant);
    }

    private long createSession(ApplicationId applicationId, TimeoutBudget timeoutBudget, File app) {
        return applicationRepository.createSession(applicationId, timeoutBudget, app);
    }

    private long createSessionFromExisting(ApplicationId applicationId, TimeoutBudget timeoutBudget) {
        return applicationRepository.createSessionFromExisting(applicationId, false, timeoutBudget);
    }

}
