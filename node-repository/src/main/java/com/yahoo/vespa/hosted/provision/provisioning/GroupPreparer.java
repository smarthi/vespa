// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.provisioning;

import com.yahoo.component.Version;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.NodeType;
import com.yahoo.config.provision.OutOfCapacityException;
import com.yahoo.transaction.Mutex;
import com.yahoo.vespa.hosted.provision.LockedNodeList;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeRepository;
import com.yahoo.vespa.hosted.provision.NodesAndHosts;
import com.yahoo.vespa.hosted.provision.node.Agent;
import com.yahoo.vespa.hosted.provision.provisioning.HostProvisioner.HostSharing;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Performs preparation of node activation changes for a single host group in an application.
 *
 * @author bratseth
 */
public class GroupPreparer {

    private static final Mutex PROBE_LOCK = () -> {};

    private final NodeRepository nodeRepository;
    private final Optional<HostProvisioner> hostProvisioner;

    /**
     * Contains list of prepared nodes and the NodesAndHost object to use for next prepare call.
     */
    public static class PrepareResult {
        public final List<Node> prepared;
        public final NodesAndHosts<LockedNodeList> allNodesAndHosts;
        PrepareResult(List<Node> prepared, NodesAndHosts<LockedNodeList> allNodesAndHosts) {
            this.prepared = prepared;
            this.allNodesAndHosts = allNodesAndHosts;
        }
    }

    public GroupPreparer(NodeRepository nodeRepository, Optional<HostProvisioner> hostProvisioner) {
        this.nodeRepository = nodeRepository;
        this.hostProvisioner = hostProvisioner;
    }

    /**
     * Ensure sufficient nodes are reserved or active for the given application, group and cluster
     *
     * @param application        the application we are allocating to
     * @param cluster            the cluster and group we are allocating to
     * @param requestedNodes     a specification of the requested nodes
     * @param surplusActiveNodes currently active nodes which are available to be assigned to this group.
     *                           This method will remove from this list if it finds it needs additional nodes
     * @param indices            the next available node indices for this cluster.
     *                           This method will consume these when it allocates new nodes to the cluster.
     * @param allNodesAndHosts   list of all nodes and hosts. Use createNodesAndHostUnlocked to create param for
     *                           first invocation. Then use previous PrepareResult.allNodesAndHosts for the following.
     * @return the list of nodes this cluster group will have allocated if activated, and
     */
    // Note: This operation may make persisted changes to the set of reserved and inactive nodes,
    // but it may not change the set of active nodes, as the active nodes must stay in sync with the
    // active config model which is changed on activate
    public PrepareResult prepare(ApplicationId application, ClusterSpec cluster, NodeSpec requestedNodes,
                                 List<Node> surplusActiveNodes, NodeIndices indices, int wantedGroups,
                                 NodesAndHosts<LockedNodeList> allNodesAndHosts) {
        // Try preparing in memory without global unallocated lock. Most of the time there should be no changes and we
        // can return nodes previously allocated.
        NodeAllocation probeAllocation = prepareAllocation(application, cluster, requestedNodes, surplusActiveNodes,
                                                           indices::probeNext, wantedGroups, allNodesAndHosts);
        if (probeAllocation.fulfilledAndNoChanges()) {
            List<Node> acceptedNodes = probeAllocation.finalNodes();
            surplusActiveNodes.removeAll(acceptedNodes);
            indices.commitProbe();
            return new PrepareResult(acceptedNodes, allNodesAndHosts);
        } else {
            // There were some changes, so re-do the allocation with locks
            indices.resetProbe();
            List<Node> prepared = prepareWithLocks(application, cluster, requestedNodes, surplusActiveNodes, indices, wantedGroups);
            return new PrepareResult(prepared, createNodesAndHostUnlocked());
        }
    }

    // Use this to create allNodesAndHosts param to prepare method for first invocation of prepare
    public NodesAndHosts<LockedNodeList> createNodesAndHostUnlocked() { return NodesAndHosts.create(nodeRepository.nodes().list(PROBE_LOCK)); }

    /// Note that this will write to the node repo.
    private List<Node> prepareWithLocks(ApplicationId application, ClusterSpec cluster, NodeSpec requestedNodes,
                                        List<Node> surplusActiveNodes, NodeIndices indices, int wantedGroups) {
        try (Mutex lock = nodeRepository.nodes().lock(application);
             Mutex allocationLock = nodeRepository.nodes().lockUnallocated()) {
            NodesAndHosts<LockedNodeList> allNodesAndHosts = NodesAndHosts.create(nodeRepository.nodes().list(allocationLock));
            NodeAllocation allocation = prepareAllocation(application, cluster, requestedNodes, surplusActiveNodes,
                                                          indices::next, wantedGroups, allNodesAndHosts);
            NodeType hostType = allocation.nodeType().hostType();
            if (canProvisionDynamically(hostType)) {
                HostSharing sharing = hostSharing(requestedNodes, hostType);
                Version osVersion = nodeRepository.osVersions().targetFor(hostType).orElse(Version.emptyVersion);
                List<ProvisionedHost> provisionedHosts = allocation.hostDeficit()
                                                                   .map(deficit -> hostProvisioner.get().provisionHosts(allocation.provisionIndices(deficit.count()),
                                                                                                                        hostType,
                                                                                                                        deficit.resources(),
                                                                                                                        application, osVersion,
                                                                                                                        sharing,
                                                                                                                        Optional.of(cluster.type())))
                                                                   .orElseGet(List::of);

                // At this point we have started provisioning of the hosts, the first priority is to make sure that
                // the returned hosts are added to the node-repo so that they are tracked by the provision maintainers
                List<Node> hosts = provisionedHosts.stream()
                                                   .map(ProvisionedHost::generateHost)
                                                   .collect(Collectors.toList());
                nodeRepository.nodes().addNodes(hosts, Agent.application);

                // Offer the nodes on the newly provisioned hosts, this should be enough to cover the deficit
                List<NodeCandidate> candidates = provisionedHosts.stream()
                                                                 .map(host -> NodeCandidate.createNewExclusiveChild(host.generateNode(),
                                                                                                                    host.generateHost()))
                                                                 .collect(Collectors.toList());
                allocation.offer(candidates);
            }

            if (! allocation.fulfilled() && requestedNodes.canFail())
                throw new OutOfCapacityException((cluster.group().isPresent() ? "Out of capacity on " + cluster.group().get() :"") +
                                                 allocation.outOfCapacityDetails());

            // Carry out and return allocation
            nodeRepository.nodes().reserve(allocation.reservableNodes());
            nodeRepository.nodes().addReservedNodes(new LockedNodeList(allocation.newNodes(), allocationLock));
            List<Node> acceptedNodes = allocation.finalNodes();
            surplusActiveNodes.removeAll(acceptedNodes);
            return acceptedNodes;
        }
    }

    private NodeAllocation prepareAllocation(ApplicationId application, ClusterSpec cluster, NodeSpec requestedNodes,
                                             List<Node> surplusActiveNodes, Supplier<Integer> nextIndex, int wantedGroups,
                                             NodesAndHosts<LockedNodeList> allNodesAndHosts) {

        NodeAllocation allocation = new NodeAllocation(allNodesAndHosts, application, cluster, requestedNodes, nextIndex, nodeRepository);
        NodePrioritizer prioritizer = new NodePrioritizer(allNodesAndHosts,
                                                          application,
                                                          cluster,
                                                          requestedNodes,
                                                          wantedGroups,
                                                          nodeRepository.zone().getCloud().dynamicProvisioning(),
                                                          nodeRepository.nameResolver(),
                                                          nodeRepository.nodes(),
                                                          nodeRepository.resourcesCalculator(),
                                                          nodeRepository.spareCount());
        allocation.offer(prioritizer.collect(surplusActiveNodes));
        return allocation;
    }

    private boolean canProvisionDynamically(NodeType hostType) {
        return nodeRepository.zone().getCloud().dynamicProvisioning() &&
               (hostType == NodeType.host || hostType.isConfigServerHostLike());
    }

    private static HostSharing hostSharing(NodeSpec spec, NodeType hostType) {
        HostSharing sharing = spec.isExclusive() ? HostSharing.exclusive : HostSharing.any;
        if (!hostType.isSharable() && sharing != HostSharing.any) {
            throw new IllegalArgumentException(hostType + " does not support sharing requirement");
        }
        return sharing;
    }

}
