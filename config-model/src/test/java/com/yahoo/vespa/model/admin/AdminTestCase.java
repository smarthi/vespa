// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.admin;

import com.yahoo.cloud.config.SentinelConfig;
import com.yahoo.cloud.config.SlobroksConfig;
import com.yahoo.cloud.config.SlobroksConfig.Slobrok;
import com.yahoo.cloud.config.log.LogdConfig;
import com.yahoo.config.model.ApplicationConfigProducerRoot;
import com.yahoo.config.model.deploy.DeployState;
import com.yahoo.config.model.deploy.TestProperties;
import com.yahoo.config.model.test.TestDriver;
import com.yahoo.config.model.test.TestRoot;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.RegionName;
import com.yahoo.config.provision.Zone;
import com.yahoo.container.StatisticsConfig;
import com.yahoo.container.jdisc.config.HealthMonitorConfig;
import com.yahoo.net.HostName;
import com.yahoo.vespa.config.core.StateserverConfig;
import com.yahoo.vespa.model.Service;
import com.yahoo.vespa.model.VespaModel;
import com.yahoo.vespa.model.container.ApplicationContainerCluster;
import com.yahoo.vespa.model.container.component.Component;
import com.yahoo.vespa.model.container.component.StatisticsComponent;
import com.yahoo.vespa.model.test.utils.VespaModelCreatorWithFilePkg;
import com.yahoo.vespa.model.test.utils.VespaModelCreatorWithMockPkg;
import org.junit.Test;

import java.util.Set;

import static com.yahoo.config.model.api.container.ContainerServiceType.METRICS_PROXY_CONTAINER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;


public class AdminTestCase {

    private static final String TESTDIR = "src/test/cfg/admin/";

    private VespaModel getVespaModel(String configPath) {
        return new VespaModelCreatorWithFilePkg(configPath).create();
    }

    /**
     * Test that version 2.0 of adminconfig works as expected.
     */
    @Test
    public void testAdmin20() {
        VespaModel vespaModel = getVespaModel(TESTDIR + "adminconfig20");

        // Verify that the admin plugin has been loaded (always loads routing).
        assertEquals(2, vespaModel.configModelRepo().asMap().size());

        ApplicationConfigProducerRoot root = vespaModel.getVespa();
        assertNotNull(root);

        // Verify configIds
        Set<String> configIds = vespaModel.getConfigIds();
        String localhost = HostName.getLocalhost();
        String localhostConfigId = "hosts/" + localhost;
        assertTrue(configIds.contains(localhostConfigId));
        assertTrue(configIds.contains("admin/logserver"));
        assertTrue(configIds.contains("admin/configservers/configserver.0"));
        assertTrue(configIds.contains("admin/slobrok.0"));
        assertTrue(configIds.contains("admin/slobrok.1"));
        assertFalse(configIds.contains("admin/slobrok.2"));
        assertTrue(configIds.contains("admin"));

        // Confirm 2 slobroks in config
        SlobroksConfig.Builder sb = new SlobroksConfig.Builder();
        vespaModel.getConfig(sb, "admin/slobrok.0");
        SlobroksConfig sc = new SlobroksConfig(sb);
        assertEquals(sc.slobrok().size(), 2);
        boolean localHostOK = false;
        for (Slobrok s : sc.slobrok()) {
            if (s.connectionspec().matches(".*" + localhost + ".*")) localHostOK = true;
        }
        assertTrue(localHostOK);

        StateserverConfig.Builder ssb = new StateserverConfig.Builder();
        vespaModel.getConfig(ssb, "admin/slobrok.0");
        assertEquals(19100, new StateserverConfig(ssb).httpport());

        vespaModel.getConfig(ssb, "admin/slobrok.1");
        assertEquals(19102, new StateserverConfig(ssb).httpport());

        LogdConfig.Builder lb = new LogdConfig.Builder();
        vespaModel.getConfig(lb, "admin/slobrok.0");
        LogdConfig lc = new LogdConfig(lb);
        assertEquals(lc.logserver().host(), localhost);

        Service logserver = vespaModel.getService("admin/logserver").get();
        assertEquals(logserver.getRelativePort(0), lc.logserver().rpcport());

        // Verify services in the sentinel config
        SentinelConfig.Builder b = new SentinelConfig.Builder();
        vespaModel.getConfig(b, localhostConfigId);
        SentinelConfig sentinelConfig = new SentinelConfig(b);
        assertEquals(5, sentinelConfig.service().size());
        assertEquals("logserver", sentinelConfig.service(0).name());
        assertEquals("slobrok", sentinelConfig.service(1).name());
        assertEquals("slobrok2", sentinelConfig.service(2).name());
        assertEquals(METRICS_PROXY_CONTAINER.serviceName, sentinelConfig.service(3).name());
        assertEquals("logd", sentinelConfig.service(4).name());
    }

    /**
     * Test that a very simple config with only adminserver tag creates
     * adminserver, logserver, configserver and slobroks
     */
    @Test
    public void testOnlyAdminserver() {
        VespaModel vespaModel = getVespaModel(TESTDIR + "simpleadminconfig20");

        // Verify that the admin plugin has been loaded (always loads routing).
        assertEquals(2, vespaModel.configModelRepo().asMap().size());

        ApplicationConfigProducerRoot root = vespaModel.getVespa();
        assertNotNull(root);

        // Verify configIds
        Set<String> configIds = vespaModel.getConfigIds();
        String localhost = HostName.getLocalhost();
        String localhostConfigId = "hosts/" + localhost;
        assertTrue(configIds.contains(localhostConfigId));
        assertTrue(configIds.contains("admin/logserver"));
        assertTrue(configIds.contains("admin/configservers/configserver.0"));
        assertTrue(configIds.contains("admin/slobrok.0"));
        assertFalse(configIds.contains("admin/slobrok.1"));

        // Verify services in the sentinel config
        SentinelConfig.Builder b = new SentinelConfig.Builder();
        vespaModel.getConfig(b, localhostConfigId);
        SentinelConfig sentinelConfig = new SentinelConfig(b);
        assertEquals(4, sentinelConfig.service().size());
        assertEquals("logserver", sentinelConfig.service(0).name());
        assertEquals("slobrok", sentinelConfig.service(1).name());
        assertEquals(METRICS_PROXY_CONTAINER.serviceName, sentinelConfig.service(2).name());
        assertEquals("logd", sentinelConfig.service(3).name());
        assertEquals(-1, sentinelConfig.service(0).affinity().cpuSocket());
        assertTrue(sentinelConfig.service(0).preShutdownCommand().isEmpty());

        // Confirm slobrok config
        SlobroksConfig.Builder sb = new SlobroksConfig.Builder();
        vespaModel.getConfig(sb, "admin");
        SlobroksConfig sc = new SlobroksConfig(sb);
        assertEquals(sc.slobrok().size(), 1);
        assertTrue(sc.slobrok().get(0).connectionspec().matches(".*" + localhost + ".*"));
    }

    @Test
    public void testTenantAndAppInSentinelConfig() {
        DeployState state = new DeployState.Builder()
                .zone(new Zone(Environment.dev, RegionName.from("baz")))
                .properties(new TestProperties()
                        .setApplicationId(new ApplicationId.Builder()
                                .tenant("quux")
                                .applicationName("foo")
                                .instanceName("bim")
                                .build()))
                .build();
        TestRoot root = new TestDriver().buildModel(state);
        String localhost = HostName.getLocalhost();
        SentinelConfig config = root.getConfig(SentinelConfig.class, "hosts/" + localhost);
        assertEquals("quux", config.application().tenant());
        assertEquals("foo", config.application().name());
        assertEquals("dev", config.application().environment());
        assertEquals("baz", config.application().region());
        assertEquals("bim", config.application().instance());
    }

    @Test
    public void testMultipleConfigServers() {
        VespaModel vespaModel = getVespaModel(TESTDIR + "multipleconfigservers");

        // Verify that the admin plugin has been loaded (always loads routing).
        assertEquals(2, vespaModel.configModelRepo().asMap().size());
        ApplicationConfigProducerRoot root = vespaModel.getVespa();
        assertNotNull(root);

        Admin admin = vespaModel.getAdmin();
        assertNotNull(admin);

        // Verify configIds
        Set<String> configIds = vespaModel.getConfigIds();
        String localhost = HostName.getLocalhost();
        String localhostConfigId = "hosts/" + localhost;
        assertTrue(configIds.contains(localhostConfigId));
        assertTrue(configIds.contains("admin/logserver"));
        assertTrue(configIds.contains("admin/configservers/configserver.0"));
        assertTrue(configIds.contains("admin/configservers/configserver.1"));

        assertEquals(2, admin.getConfigservers().size());

        // Default configserver is the first one in the list and should have the default ports too
        Configserver server1 = admin.getConfigservers().get(0);
        assertEquals(admin.getConfigserver(), server1);
        assertEquals(2, server1.getPortCount());
        assertEquals(19070, server1.getRelativePort(0));
        assertEquals(19071, server1.getRelativePort(1));


        // Second configserver should be on second host but have the same port number
        Configserver server2 = admin.getConfigservers().get(1);

        assertNotSame(server1, server2);
        assertNotSame(server1.getHostName(), server2.getHostName());

        assertEquals(2, server2.getPortCount());
        assertEquals(19070, server2.getRelativePort(0));
        assertEquals(19071, server2.getRelativePort(1));
    }

    @Test
    public void testContainerMetricsSnapshotInterval() {
        VespaModel vespaModel = getVespaModel(TESTDIR + "metricconfig");

        ApplicationContainerCluster qrCluster = vespaModel.getContainerClusters().get("container");
        HealthMonitorConfig.Builder builder = new HealthMonitorConfig.Builder();
        qrCluster.getConfig(builder);
        HealthMonitorConfig qrClusterConfig = new HealthMonitorConfig(builder);
        assertEquals(60, (int) qrClusterConfig.snapshot_interval());

        StatisticsComponent stat = null;
        for (Component component : qrCluster.getAllComponents()) {
            if (component.getClassId().getName().contains("com.yahoo.statistics.StatisticsImpl")) {
                stat = (StatisticsComponent) component;
                break;
            }
        }
        assertNotNull(stat);
        StatisticsConfig.Builder sb = new StatisticsConfig.Builder();
        stat.getConfig(sb);
        StatisticsConfig sc = new StatisticsConfig(sb);
        assertEquals(60, (int) sc.collectionintervalsec());
        assertEquals(60, (int) sc.loggingintervalsec());
    }

    @Test
    public void testStatisticsConfig() {
        StatisticsComponent stat = new StatisticsComponent();
        StatisticsConfig.Builder sb = new StatisticsConfig.Builder();
        stat.getConfig(sb);
        StatisticsConfig sc = new StatisticsConfig(sb);
        assertEquals(sc.collectionintervalsec(), 300, 0.1);
        assertEquals(sc.loggingintervalsec(), 300, 0.1);
        assertEquals(sc.values(0).operations(0).name(), StatisticsConfig.Values.Operations.Name.REGULAR);
        assertEquals(sc.values(0).operations(0).arguments(0).key(), "limits");
        assertEquals(sc.values(0).operations(0).arguments(0).value(), "25,50,100,500");
    }

    @Test
    public void testLogForwarding() {
        String hosts = "<hosts>"
                + "  <host name=\"myhost0\">"
                + "    <alias>node0</alias>"
                + "  </host>"
                + "</hosts>";

        String services = "<services>" +
                "  <admin version='2.0'>" +
                "    <adminserver hostalias='node0' />" +
                "    <logforwarding>" +
                "      <splunk deployment-server='foo:123' client-name='foocli' phone-home-interval='900' />" +
                "    </logforwarding>" +
                "  </admin>" +
                "</services>";

        VespaModel vespaModel = new VespaModelCreatorWithMockPkg(hosts, services).create();

        Set<String> configIds = vespaModel.getConfigIds();
        // 1 logforwarder on each host
        assertTrue(configIds.toString(), configIds.contains("hosts/myhost0/logforwarder"));
    }

    @Test
    public void testDisableFileDistributorForAllApps() {
        DeployState state = new DeployState.Builder()
                .zone(new Zone(Environment.dev, RegionName.from("baz")))
                .properties(
                        new TestProperties().
                                setApplicationId(new ApplicationId.Builder().
                                        tenant("quux").
                                        applicationName("foo").instanceName("bim")
                                                      .build()))
                .build();
        TestRoot root = new TestDriver().buildModel(state);
        String localhost = HostName.getLocalhost();
        SentinelConfig sentinelConfig = root.getConfig(SentinelConfig.class, "hosts/" + localhost);
        assertEquals(4, sentinelConfig.service().size());
        assertEquals("logserver", sentinelConfig.service(0).name());
        assertEquals("slobrok", sentinelConfig.service(1).name());
        assertEquals(METRICS_PROXY_CONTAINER.serviceName, sentinelConfig.service(2).name());
        assertEquals("logd", sentinelConfig.service(3).name());
    }

}
