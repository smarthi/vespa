// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.application;

import com.yahoo.cloud.config.ConfigserverConfig;
import com.yahoo.component.Version;
import com.yahoo.concurrent.InThreadExecutorService;
import com.yahoo.concurrent.StripedExecutor;
import com.yahoo.config.model.NullConfigModelRegistry;
import com.yahoo.config.model.application.provider.FilesApplicationPackage;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.TenantName;
import com.yahoo.text.Utf8;
import com.yahoo.vespa.config.ConfigKey;
import com.yahoo.vespa.config.server.ConfigServerDB;
import com.yahoo.vespa.config.server.ReloadListener;
import com.yahoo.vespa.config.server.ServerCache;
import com.yahoo.vespa.config.server.deploy.TenantFileSystemDirs;
import com.yahoo.vespa.config.server.host.HostRegistry;
import com.yahoo.vespa.config.server.model.TestModelFactory;
import com.yahoo.vespa.config.server.modelfactory.ModelFactoryRegistry;
import com.yahoo.vespa.config.server.monitoring.MetricUpdater;
import com.yahoo.vespa.config.server.monitoring.Metrics;
import com.yahoo.vespa.config.server.tenant.TenantRepository;
import com.yahoo.vespa.config.server.tenant.TestTenantRepository;
import com.yahoo.vespa.curator.CompletionTimeoutException;
import com.yahoo.vespa.curator.Curator;
import com.yahoo.vespa.curator.mock.MockCurator;
import com.yahoo.vespa.curator.mock.MockCuratorFramework;
import com.yahoo.vespa.model.VespaModel;
import com.yahoo.vespa.model.VespaModelFactory;
import org.apache.curator.framework.CuratorFramework;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static com.yahoo.vespa.config.server.application.TenantApplications.RemoveApplicationWaiter;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @author Ulf Lilleengen
 */
public class TenantApplicationsTest {

    private static final TenantName tenantName = TenantName.from("tenant");
    private static final Version vespaVersion = new VespaModelFactory(new NullConfigModelRegistry()).version();

    private Curator curator;
    private CuratorFramework curatorFramework;
    private TenantApplications applications;
    private ConfigserverConfig configserverConfig;

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Before
    public void setup() throws IOException {
        curator = new MockCurator();
        curatorFramework = curator.framework();
        configserverConfig = new ConfigserverConfig.Builder()
                .payloadCompressionType(ConfigserverConfig.PayloadCompressionType.Enum.UNCOMPRESSED)
                .configServerDBDir(tempFolder.newFolder("configserverdb").getAbsolutePath())
                .configDefinitionsDir(tempFolder.newFolder("configdefinitions").getAbsolutePath())
                .build();
        TenantRepository tenantRepository = new TestTenantRepository.Builder()
                .withConfigserverConfig(configserverConfig)
                .withCurator(curator)
                .withModelFactoryRegistry(createRegistry())
                .build();
        tenantRepository.addTenant(TenantRepository.HOSTED_VESPA_TENANT);
        tenantRepository.addTenant(tenantName);
        applications = createTenantApplications(tenantName, curator, configserverConfig, new MockReloadListener());
    }

    @Test
    public void require_that_applications_are_read_from_zookeeper() throws Exception {
        writeApplicationData(createApplicationId("foo"), 3L);
        writeApplicationData(createApplicationId("bar"), 4L);
        TenantApplications repo = createZKAppRepo();
        List<ApplicationId> applications = repo.activeApplications();
        assertEquals(2, applications.size());
        assertEquals("bar", applications.get(0).application().value());
        assertEquals("foo", applications.get(1).application().value());
        assertEquals(4, repo.requireActiveSessionOf(applications.get(0)));
        assertEquals(3, repo.requireActiveSessionOf(applications.get(1)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void require_that_requesting_session_for_unknown_application_throws_exception() {
        TenantApplications repo = createZKAppRepo();
        repo.requireActiveSessionOf(createApplicationId("nonexistent"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void require_that_requesting_session_for_empty_application_throws_exception() throws Exception {
        ApplicationId baz = createApplicationId("baz");
        // No data in node
        curatorFramework.create().creatingParentsIfNeeded()
                .forPath(TenantRepository.getApplicationsPath(tenantName).append(baz.serializedForm()).getAbsolute());
        TenantApplications repo = createZKAppRepo();
        repo.requireActiveSessionOf(baz);
    }

    @Test
    public void require_that_application_ids_can_be_written() throws Exception {
        TenantApplications repo = createZKAppRepo();
        ApplicationId myapp = createApplicationId("myapp");
        repo.createApplication(myapp);
        repo.createPutTransaction(myapp, 3).commit();
        String path = TenantRepository.getApplicationsPath(tenantName).append(myapp.serializedForm()).getAbsolute();
        assertNotNull(curatorFramework.checkExists().forPath(path));
        assertEquals("3", Utf8.toString(curatorFramework.getData().forPath(path)));
        repo.createPutTransaction(myapp, 5).commit();
        assertNotNull(curatorFramework.checkExists().forPath(path));
        assertEquals("5", Utf8.toString(curatorFramework.getData().forPath(path)));
    }

    @Test
    public void require_that_application_ids_can_be_deleted() {
        TenantApplications repo = createZKAppRepo();
        ApplicationId id1 = createApplicationId("myapp");
        ApplicationId id2 = createApplicationId("myapp2");
        repo.createApplication(id1);
        repo.createApplication(id2);
        repo.createPutTransaction(id1, 1).commit();
        repo.createPutTransaction(id2, 1).commit();
        assertEquals(2, repo.activeApplications().size());
        repo.createDeleteTransaction(id1).commit();
        assertEquals(1, repo.activeApplications().size());
        repo.createDeleteTransaction(id2).commit();
        assertEquals(0, repo.activeApplications().size());
    }

    public static class MockReloadListener implements ReloadListener {
        public final AtomicInteger reloaded = new AtomicInteger(0);
        final AtomicInteger removed = new AtomicInteger(0);
        final Map<String, Collection<String>> tenantHosts = new LinkedHashMap<>();

        @Override
        public void configActivated(ApplicationSet application) {
            reloaded.incrementAndGet();
        }

        @Override
        public void hostsUpdated(ApplicationId applicationId, Collection<String> newHosts) {
            tenantHosts.put(applicationId.tenant().value(), newHosts);
        }

        @Override
        public void verifyHostsAreAvailable(ApplicationId applicationId, Collection<String> newHosts) {
        }

        @Override
        public void applicationRemoved(ApplicationId applicationId) {
            removed.incrementAndGet();
        }
    }

    private void assertdefaultAppNotFound() {
        assertFalse(applications.hasApplication(ApplicationId.defaultId(), Optional.of(vespaVersion)));
    }

    @Test
    public void testListConfigs() throws IOException, SAXException {
        applications = createTenantApplications(TenantName.defaultName(), new MockCurator(), configserverConfig, new MockReloadListener());
        assertdefaultAppNotFound();

        VespaModel model = new VespaModel(FilesApplicationPackage.fromFile(new File("src/test/apps/app")));
        ApplicationId applicationId = ApplicationId.defaultId();
        applications.createApplication(applicationId);
        applications.createPutTransaction(applicationId, 1).commit();
        applications.activateApplication(ApplicationSet.from(new Application(model,
                                                                             new ServerCache(),
                                                                             1,
                                                                             vespaVersion,
                                                                             MetricUpdater.createTestUpdater(),
                                                                             applicationId)),
                                         1);
        Set<ConfigKey<?>> configNames = applications.listConfigs(applicationId, Optional.of(vespaVersion), false);
        assertTrue(configNames.contains(new ConfigKey<>("sentinel", "hosts", "cloud.config")));

        configNames = applications.listConfigs(ApplicationId.defaultId(), Optional.of(vespaVersion), true);
        assertTrue(configNames.contains(new ConfigKey<>("documentmanager", "container", "document.config")));
        assertTrue(configNames.contains(new ConfigKey<>("documentmanager", "", "document.config")));
        assertTrue(configNames.contains(new ConfigKey<>("documenttypes", "", "document.config")));
        assertTrue(configNames.contains(new ConfigKey<>("documentmanager", "container", "document.config")));
        assertTrue(configNames.contains(new ConfigKey<>("health-monitor", "container", "container.jdisc.config")));
        assertTrue(configNames.contains(new ConfigKey<>("specific", "container", "project")));
    }

    @Test
    public void testAppendIdsInNonRecursiveListing() {
        assertEquals(applications.appendOneLevelOfId("search/music", "search/music/qrservers/default/qr.0"), "search/music/qrservers");
        assertEquals(applications.appendOneLevelOfId("search", "search/music/qrservers/default/qr.0"), "search/music");
        assertEquals(applications.appendOneLevelOfId("search/music/qrservers/default/qr.0", "search/music/qrservers/default/qr.0"), "search/music/qrservers/default/qr.0");
        assertEquals(applications.appendOneLevelOfId("", "search/music/qrservers/default/qr.0"), "search");
    }

    @Test
    public void testRemoveApplication2of3Respond() throws InterruptedException {
        Curator curator = new MockCurator3ConfigServers();
        Thread t1 = setupWaiter(curator);
        notifyCompletion(curator, 2);
        t1.join();
    }

    @Test
    public void testRemoveApplicationAllRespond() throws InterruptedException {
        Curator curator = new MockCurator3ConfigServers();
        Thread t1 = setupWaiter(curator);
        notifyCompletion(curator, 3);
        t1.join();
    }

    private Thread setupWaiter(Curator curator) {
        Curator.CompletionWaiter waiter = RemoveApplicationWaiter.createAndInitialize(curator, createApplicationId(), "cfg1", Duration.ofSeconds(1));
        Thread t1 = new Thread(() -> {
            try {
                waiter.awaitCompletion(Duration.ofSeconds(120));
            } catch (CompletionTimeoutException e) {
                fail("Waiting failed due to timeout");
            }
        });
        t1.start();
        return t1;
    }

    private void notifyCompletion(Curator curator, int respondentCount) {
        IntStream.range(0, respondentCount)
                 .forEach(i -> RemoveApplicationWaiter.create(curator, createApplicationId(), "cfg" + i, Duration.ofSeconds(1))
                                                      .notifyCompletion());
    }

    private TenantApplications createZKAppRepo() {
        return createTenantApplications(tenantName, curator, configserverConfig, new MockReloadListener());
    }


    private static ApplicationId createApplicationId() {
        return createApplicationId("foo");
    }

    private static ApplicationId createApplicationId(String name) {
        return ApplicationId.from(tenantName.value(), name, "myinst");
    }

    private void writeApplicationData(ApplicationId applicationId, long sessionId) throws Exception {
        writeApplicationData(applicationId.serializedForm(), sessionId);
    }

    private void writeApplicationData(String applicationId, long sessionId) throws Exception {
        curatorFramework
                .create()
                .creatingParentsIfNeeded()
                .forPath(TenantRepository.getApplicationsPath(tenantName).append(applicationId).getAbsolute(),
                         Utf8.toAsciiBytes(sessionId));
    }

    private ModelFactoryRegistry createRegistry() {
        return new ModelFactoryRegistry(Arrays.asList(new TestModelFactory(vespaVersion),
                                                      new TestModelFactory(new Version(3, 2, 1))));
    }


    // For testing only
    private TenantApplications createTenantApplications(TenantName tenantName,
                       Curator curator,
                       ConfigserverConfig configserverConfig,
                       ReloadListener reloadListener) {
        return new TenantApplications(tenantName,
             curator,
             new StripedExecutor<>(new InThreadExecutorService()),
             new InThreadExecutorService(),
             Metrics.createTestMetrics(),
             reloadListener,
             configserverConfig,
             new HostRegistry(),
             new TenantFileSystemDirs(new ConfigServerDB(configserverConfig), tenantName),
             Clock.systemUTC());
    }

    private static class MockCurator3ConfigServers extends Curator {

        public MockCurator3ConfigServers() {
            super("host1:2181,host2:2181,host3:2181", "host1:2181,host2:2181,host3:2181", (retryPolicy) -> new MockCuratorFramework(true, false));
        }

    }

}
