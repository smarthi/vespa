// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.node.admin.integration;

import com.yahoo.config.provision.DockerImage;
import com.yahoo.config.provision.HostName;
import com.yahoo.config.provision.NodeType;
import com.yahoo.vespa.flags.InMemoryFlagSource;
import com.yahoo.vespa.hosted.node.admin.configserver.noderepository.NodeSpec;
import com.yahoo.vespa.hosted.node.admin.configserver.orchestrator.Orchestrator;
import com.yahoo.vespa.hosted.node.admin.container.CGroupV2;
import com.yahoo.vespa.hosted.node.admin.container.ContainerEngineMock;
import com.yahoo.vespa.hosted.node.admin.container.ContainerName;
import com.yahoo.vespa.hosted.node.admin.container.ContainerOperations;
import com.yahoo.vespa.hosted.node.admin.container.RegistryCredentials;
import com.yahoo.vespa.hosted.node.admin.container.metrics.Metrics;
import com.yahoo.vespa.hosted.node.admin.maintenance.StorageMaintainer;
import com.yahoo.vespa.hosted.node.admin.maintenance.servicedump.VespaServiceDumper;
import com.yahoo.vespa.hosted.node.admin.nodeadmin.NodeAdminImpl;
import com.yahoo.vespa.hosted.node.admin.nodeadmin.NodeAdminStateUpdater;
import com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgentContext;
import com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgentContextFactory;
import com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgentContextImpl;
import com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgentFactory;
import com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgentImpl;
import com.yahoo.vespa.hosted.node.admin.task.util.network.IPAddressesMock;
import com.yahoo.vespa.test.file.TestFileSystem;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.nio.file.FileSystem;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.when;

/**
 * @author musum
 */
// Need to deconstruct nodeAdminStateUpdater
public class ContainerTester implements AutoCloseable {

    private static final Logger log = Logger.getLogger(ContainerTester.class.getName());
    private static final Duration INTERVAL = Duration.ofMillis(10);
    static final HostName HOST_HOSTNAME = HostName.from("host.test.yahoo.com");

    private final Thread loopThread;

    private final ContainerEngineMock containerEngine = new ContainerEngineMock();
    private final FileSystem fileSystem = TestFileSystem.create();
    final ContainerOperations containerOperations = spy(new ContainerOperations(containerEngine, new CGroupV2(fileSystem), fileSystem));
    final NodeRepoMock nodeRepository = spy(new NodeRepoMock());
    final Orchestrator orchestrator = mock(Orchestrator.class);
    final StorageMaintainer storageMaintainer = mock(StorageMaintainer.class);
    final InOrder inOrder = Mockito.inOrder(containerOperations, nodeRepository, orchestrator, storageMaintainer);
    final InMemoryFlagSource flagSource = new InMemoryFlagSource();

    final NodeAdminStateUpdater nodeAdminStateUpdater;
    final NodeAdminImpl nodeAdmin;

    private boolean terminated = false;
    private volatile NodeAdminStateUpdater.State wantedState = NodeAdminStateUpdater.State.RESUMED;


    ContainerTester(List<DockerImage> images) {
        images.forEach(image -> containerEngine.pullImage(null, image, RegistryCredentials.none));
        when(storageMaintainer.diskUsageFor(any())).thenReturn(Optional.empty());

        IPAddressesMock ipAddresses = new IPAddressesMock();
        ipAddresses.addAddress(HOST_HOSTNAME.value(), "1.1.1.1");
        ipAddresses.addAddress(HOST_HOSTNAME.value(), "f000::");
        for (int i = 1; i < 4; i++) ipAddresses.addAddress("host" + i + ".test.yahoo.com", "f000::" + i);

        NodeSpec hostSpec = NodeSpec.Builder.testSpec(HOST_HOSTNAME.value()).type(NodeType.host).build();
        nodeRepository.updateNodeSpec(hostSpec);

        Clock clock = Clock.systemUTC();
        Metrics metrics = new Metrics();
        FileSystem fileSystem = TestFileSystem.create();

        NodeAgentFactory nodeAgentFactory = (contextSupplier, nodeContext) -> new NodeAgentImpl(
                contextSupplier, nodeRepository, orchestrator, containerOperations, () -> RegistryCredentials.none,
                storageMaintainer, flagSource,
                Collections.emptyList(), Optional.empty(), Optional.empty(), clock, Duration.ofSeconds(-1),
                VespaServiceDumper.DUMMY_INSTANCE);
        nodeAdmin = new NodeAdminImpl(nodeAgentFactory, metrics, clock, Duration.ofMillis(10), Duration.ZERO);
        NodeAgentContextFactory nodeAgentContextFactory = (nodeSpec, acl) ->
                NodeAgentContextImpl.builder(nodeSpec).acl(acl).fileSystem(fileSystem).build();
        nodeAdminStateUpdater = new NodeAdminStateUpdater(nodeAgentContextFactory, nodeRepository, orchestrator,
                nodeAdmin, HOST_HOSTNAME);

        loopThread = new Thread(() -> {
            nodeAdminStateUpdater.start();

            while (! terminated) {
                try {
                    nodeAdminStateUpdater.converge(wantedState);
                } catch (RuntimeException e) {
                    log.info(e.getMessage());
                }
                try {
                    Thread.sleep(INTERVAL.toMillis());
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
        loopThread.start();
    }

    /** Adds a node to node-repository mock that is running on this host */
    void addChildNodeRepositoryNode(NodeSpec nodeSpec) {
        if (nodeSpec.wantedDockerImage().isPresent()) {
            if (!containerEngine.hasImage(null, nodeSpec.wantedDockerImage().get())) {
                throw new IllegalArgumentException("Want to use image " + nodeSpec.wantedDockerImage().get() +
                                                   ", but that image does not exist in the container engine");
            }
        }
        nodeRepository.updateNodeSpec(new NodeSpec.Builder(nodeSpec)
                .parentHostname(HOST_HOSTNAME.value())
                .build());
    }

    void setWantedState(NodeAdminStateUpdater.State wantedState) {
        this.wantedState = wantedState;
    }

    <T> T inOrder(T t) {
        // Should probably wait for one NodeAdminStateUpdater tick & one node agent tick
        // instead of waiting up to a timeout, but there are currently no guarantees on the node
        // agent ticks.
        return inOrder.verify(t, timeout(60000));
    }

    public static NodeAgentContext containerMatcher(ContainerName containerName) {
        return argThat((ctx) -> ctx.containerName().equals(containerName));
    }

    @Override
    public void close() {
        // First, stop NodeAdmin and all the NodeAgents
        nodeAdminStateUpdater.stop();

        terminated = true;
        do {
            try {
                loopThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        } while (loopThread.isAlive());
    }

}
