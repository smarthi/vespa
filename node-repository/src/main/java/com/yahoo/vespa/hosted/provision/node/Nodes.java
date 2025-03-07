// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.node;

import com.yahoo.collections.ListMap;
import com.yahoo.component.Version;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ApplicationTransaction;
import com.yahoo.config.provision.Flavor;
import com.yahoo.config.provision.NodeType;
import com.yahoo.config.provision.Zone;
import com.yahoo.transaction.Mutex;
import com.yahoo.transaction.NestedTransaction;
import com.yahoo.vespa.applicationmodel.HostName;
import com.yahoo.vespa.hosted.provision.LockedNodeList;
import com.yahoo.vespa.hosted.provision.NoSuchNodeException;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeList;
import com.yahoo.vespa.hosted.provision.NodeMutex;
import com.yahoo.vespa.hosted.provision.maintenance.NodeFailer;
import com.yahoo.vespa.hosted.provision.node.filter.NodeFilter;
import com.yahoo.vespa.hosted.provision.persistence.CuratorDatabaseClient;
import com.yahoo.vespa.orchestrator.HostNameNotFoundException;
import com.yahoo.vespa.orchestrator.Orchestrator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The nodes in the node repo and their state transitions
 *
 * @author bratseth
 */
// Node state transitions:
// 1) (new) | deprovisioned - > provisioned -> (dirty ->) ready -> reserved -> active -> inactive -> dirty -> ready
// 2) inactive -> reserved | parked
// 3) reserved -> dirty
// 4) * -> failed | parked -> (breakfixed) -> dirty | active | deprovisioned
// 5) deprovisioned -> (forgotten)
// Nodes have an application assigned when in states reserved, active and inactive.
// Nodes might have an application assigned in dirty.
public class Nodes {

    private static final Logger log = Logger.getLogger(Nodes.class.getName());

    private final CuratorDatabaseClient db;
    private final Zone zone;
    private final Clock clock;
    private final Orchestrator orchestrator;

    public Nodes(CuratorDatabaseClient db, Zone zone, Clock clock, Orchestrator orchestrator) {
        this.zone = zone;
        this.clock = clock;
        this.db = db;
        this.orchestrator = orchestrator;
    }

    /** Read and write all nodes to make sure they are stored in the latest version of the serialized format */
    public void rewrite() {
        Instant start = clock.instant();
        int nodesWritten = 0;
        for (Node.State state : Node.State.values()) {
            List<Node> nodes = db.readNodes(state);
            // TODO(mpolden): This should take the lock before writing
            db.writeTo(state, nodes, Agent.system, Optional.empty());
            nodesWritten += nodes.size();
        }
        Instant end = clock.instant();
        log.log(Level.INFO, String.format("Rewrote %d nodes in %s", nodesWritten, Duration.between(start, end)));
    }

    // ---------------- Query API ----------------------------------------------------------------

    /**
     * Finds and returns the node with the hostname in any of the given states, or empty if not found
     *
     * @param hostname the full host name of the node
     * @param inState the states the node may be in. If no states are given, it will be returned from any state
     * @return the node, or empty if it was not found in any of the given states
     */
    public Optional<Node> node(String hostname, Node.State... inState) {
        return db.readNode(hostname, inState);
    }

    /**
     * Returns a list of nodes in this repository in any of the given states
     *
     * @param inState the states to return nodes from. If no states are given, all nodes of the given type are returned
     */
    public NodeList list(Node.State... inState) {
        return NodeList.copyOf(db.readNodes(inState));
    }

    /** Returns a locked list of all nodes in this repository */
    public LockedNodeList list(Mutex lock) {
        return new LockedNodeList(list().asList(), lock);
    }

    /**
     * Returns whether the zone managed by this node repository seems to be working.
     * If too many nodes are not responding, there is probably some zone-wide issue
     * and we should probably refrain from making changes to it.
     */
    public boolean isWorking() {
        NodeList activeNodes = list(Node.State.active);
        if (activeNodes.size() <= 5) return true; // Not enough data to decide
        NodeList downNodes = activeNodes.down();
        return ! ( (double)downNodes.size() / (double)activeNodes.size() > 0.2 );
    }

    // ----------------- Node lifecycle -----------------------------------------------------------

    /** Adds a list of newly created reserved nodes to the node repository */
    public List<Node> addReservedNodes(LockedNodeList nodes) {
        for (Node node : nodes) {
            if ( node.flavor().getType() != Flavor.Type.DOCKER_CONTAINER)
                illegal("Cannot add " + node + ": This is not a child node");
            if (node.allocation().isEmpty())
                illegal("Cannot add " + node + ": Child nodes need to be allocated");
            Optional<Node> existing = node(node.hostname());
            if (existing.isPresent())
                illegal("Cannot add " + node + ": A node with this name already exists (" +
                        existing.get() + ", " + existing.get().history() + "). Node to be added: " +
                        node + ", " + node.history());
        }
        return db.addNodesInState(nodes.asList(), Node.State.reserved, Agent.system);
    }

    /**
     * Adds a list of (newly created) nodes to the node repository as provisioned nodes.
     * If any of the nodes already exists in the deprovisioned state, the new node will be merged
     * with the history of that node.
     */
    public List<Node> addNodes(List<Node> nodes, Agent agent) {
        try (Mutex lock = lockUnallocated()) {
            List<Node> nodesToAdd =  new ArrayList<>();
            List<Node> nodesToRemove = new ArrayList<>();
            for (int i = 0; i < nodes.size(); i++) {
                var node = nodes.get(i);

                // Check for duplicates
                for (int j = 0; j < i; j++) {
                    if (node.equals(nodes.get(j)))
                        illegal("Cannot add nodes: " + node + " is duplicated in the argument list");
                }

                Optional<Node> existing = node(node.hostname());
                if (existing.isPresent()) {
                    if (existing.get().state() != Node.State.deprovisioned)
                        illegal("Cannot add " + node + ": A node with this name already exists");
                    node = node.with(existing.get().history());
                    node = node.with(existing.get().reports());
                    node = node.with(node.status().withFailCount(existing.get().status().failCount()));
                    if (existing.get().status().firmwareVerifiedAt().isPresent())
                        node = node.with(node.status().withFirmwareVerifiedAt(existing.get().status().firmwareVerifiedAt().get()));
                    // Preserve wantToRebuild/wantToRetire when rebuilding as the fields shouldn't be cleared until the
                    // host is readied (i.e. we know it is up and rebuild completed)
                    boolean rebuilding = existing.get().status().wantToRebuild();
                    if (rebuilding) {
                        node = node.with(node.status().withWantToRetire(existing.get().status().wantToRetire(),
                                                                        false,
                                                                        rebuilding));
                    }
                    nodesToRemove.add(existing.get());
                }

                nodesToAdd.add(node);
            }
            NestedTransaction transaction = new NestedTransaction();
            List<Node> resultingNodes = db.addNodesInState(IP.Config.verify(nodesToAdd, list(lock)), Node.State.provisioned, agent, transaction);
            db.removeNodes(nodesToRemove, transaction);
            transaction.commit();
            return resultingNodes;
        }
    }

    /** Sets a list of nodes ready and returns the nodes in the ready state */
    public List<Node> setReady(List<Node> nodes, Agent agent, String reason) {
        try (Mutex lock = lockUnallocated()) {
            List<Node> nodesWithResetFields = nodes.stream()
                                                   .map(node -> {
                                                       if (node.state() != Node.State.provisioned && node.state() != Node.State.dirty)
                                                           illegal("Can not set " + node + " ready. It is not provisioned or dirty.");
                                                       return node.withWantToRetire(false,
                                                                                    false,
                                                                                    false,
                                                                                    Agent.system,
                                                                                    clock.instant());
                                                   })
                                                   .collect(Collectors.toList());
            return db.writeTo(Node.State.ready, nodesWithResetFields, agent, Optional.of(reason));
        }
    }

    public Node setReady(String hostname, Agent agent, String reason) {
        Node nodeToReady = requireNode(hostname);
        if (nodeToReady.state() == Node.State.ready) return nodeToReady;
        return setReady(List.of(nodeToReady), agent, reason).get(0);
    }

    /** Reserve nodes. This method does <b>not</b> lock the node repository */
    public List<Node> reserve(List<Node> nodes) {
        return db.writeTo(Node.State.reserved, nodes, Agent.application, Optional.empty());
    }

    /** Activate nodes. This method does <b>not</b> lock the node repository */
    public List<Node> activate(List<Node> nodes, NestedTransaction transaction) {
        return db.writeTo(Node.State.active, nodes, Agent.application, Optional.empty(), transaction);
    }

    /**
     * Sets a list of nodes to have their allocation removable (active to inactive) in the node repository.
     *
     * @param application the application the nodes belong to
     * @param nodes the nodes to make removable. These nodes MUST be in the active state.
     */
    public void setRemovable(ApplicationId application, List<Node> nodes) {
        try (Mutex lock = lock(application)) {
            List<Node> removableNodes = nodes.stream()
                                             .map(node -> node.with(node.allocation().get().removable(true)))
                                             .collect(Collectors.toList());
            write(removableNodes, lock);
        }
    }

    /**
     * Deactivates these nodes in a transaction and returns the nodes in the new state which will hold if the
     * transaction commits.
     */
    public List<Node> deactivate(List<Node> nodes, ApplicationTransaction transaction) {
        if ( ! zone.environment().isProduction() || zone.system().isCd())
            return deallocate(nodes, Agent.application, "Deactivated by application", transaction.nested());

        var stateless = NodeList.copyOf(nodes).stateless();
        var stateful  = NodeList.copyOf(nodes).stateful();
        List<Node> written = new ArrayList<>();
        written.addAll(deallocate(stateless.asList(), Agent.application, "Deactivated by application", transaction.nested()));
        written.addAll(db.writeTo(Node.State.inactive, stateful.asList(), Agent.application, Optional.empty(), transaction.nested()));
        return written;
    }

    /**
     * Fails these nodes in a transaction and returns the nodes in the new state which will hold if the
     * transaction commits.
     */
    public List<Node> fail(List<Node> nodes, ApplicationTransaction transaction) {
        return fail(nodes, Agent.application, "Failed by application", transaction.nested());
    }

    public List<Node> fail(List<Node> nodes, Agent agent, String reason) {
        NestedTransaction transaction = new NestedTransaction();
        nodes = fail(nodes, agent, reason, transaction);
        transaction.commit();
        return nodes;
    }

    private List<Node> fail(List<Node> nodes, Agent agent, String reason, NestedTransaction transaction) {
        nodes = nodes.stream()
                     .map(n -> n.withWantToFail(false, agent, clock.instant()))
                     .collect(Collectors.toList());
        return db.writeTo(Node.State.failed, nodes, agent, Optional.of(reason), transaction);
    }

    /** Move nodes to the dirty state */
    public List<Node> deallocate(List<Node> nodes, Agent agent, String reason) {
        return performOn(NodeList.copyOf(nodes), (node, lock) -> deallocate(node, agent, reason));
    }

    public List<Node> deallocateRecursively(String hostname, Agent agent, String reason) {
        Node nodeToDirty = node(hostname).orElseThrow(() ->
                                                                 new IllegalArgumentException("Could not deallocate " + hostname + ": Node not found"));

        List<Node> nodesToDirty =
                (nodeToDirty.type().isHost() ?
                 Stream.concat(list().childrenOf(hostname).asList().stream(), Stream.of(nodeToDirty)) :
                 Stream.of(nodeToDirty))
                        .filter(node -> node.state() != Node.State.dirty)
                        .collect(Collectors.toList());

        List<String> hostnamesNotAllowedToDirty = nodesToDirty.stream()
                                                              .filter(node -> node.state() != Node.State.provisioned)
                                                              .filter(node -> node.state() != Node.State.failed)
                                                              .filter(node -> node.state() != Node.State.parked)
                                                              .filter(node -> node.state() != Node.State.breakfixed)
                                                              .map(Node::hostname)
                                                              .collect(Collectors.toList());
        if ( ! hostnamesNotAllowedToDirty.isEmpty())
            illegal("Could not deallocate " + nodeToDirty + ": " +
                    hostnamesNotAllowedToDirty + " are not in states [provisioned, failed, parked, breakfixed]");

        return nodesToDirty.stream().map(node -> deallocate(node, agent, reason)).collect(Collectors.toList());
    }

    /**
     * Set a node dirty  or parked, allowed if it is in the provisioned, inactive, failed or parked state.
     * Use this to clean newly provisioned nodes or to recycle failed nodes which have been repaired or put on hold.
     */
    public Node deallocate(Node node, Agent agent, String reason) {
        NestedTransaction transaction = new NestedTransaction();
        Node deallocated = deallocate(node, agent, reason, transaction);
        transaction.commit();
        return deallocated;
    }

    public List<Node> deallocate(List<Node> nodes, Agent agent, String reason, NestedTransaction transaction) {
        return nodes.stream().map(node -> deallocate(node, agent, reason, transaction)).collect(Collectors.toList());
    }

    public Node deallocate(Node node, Agent agent, String reason, NestedTransaction transaction) {
        if (parkOnDeallocationOf(node, agent)) {
            return park(node.hostname(), false, agent, reason, transaction);
        } else {
            return db.writeTo(Node.State.dirty, List.of(node), agent, Optional.of(reason), transaction).get(0);
        }
    }

    /**
     * Fails this node and returns it in its new state.
     *
     * @return the node in its new state
     * @throws NoSuchNodeException if the node is not found
     */
    public Node fail(String hostname, Agent agent, String reason) {
        return fail(hostname, true, agent, reason);
    }

    public Node fail(String hostname, boolean keepAllocation, Agent agent, String reason) {
        return move(hostname, Node.State.failed, agent, keepAllocation, Optional.of(reason));
    }

    /**
     * Fails all the nodes that are children of hostname before finally failing the hostname itself.
     * Non-active nodes are failed immediately, while active nodes are marked as wantToFail.
     * The host is failed if it has no active nodes and marked wantToFail if it has.
     *
     * @return all the nodes that were changed by this request
     */
    public List<Node> failOrMarkRecursively(String hostname, Agent agent, String reason) {
        NodeList children = list().childrenOf(hostname);
        List<Node> changed = performOn(children, (node, lock) -> failOrMark(node, agent, reason, lock));

        if (children.state(Node.State.active).isEmpty())
            changed.add(move(hostname, Node.State.failed, agent, true, Optional.of(reason)));
        else
            changed.addAll(performOn(NodeList.of(node(hostname).orElseThrow()), (node, lock) -> failOrMark(node, agent, reason, lock)));

        return changed;
    }

    private Node failOrMark(Node node, Agent agent, String reason, Mutex lock) {
        if (node.state() == Node.State.active) {
            node = node.withWantToFail(true, agent, clock.instant());
            write(node, lock);
            return node;
        } else {
            return move(node.hostname(), Node.State.failed, agent, true, Optional.of(reason));
        }
    }

    /**
     * Parks this node and returns it in its new state.
     *
     * @return the node in its new state
     * @throws NoSuchNodeException if the node is not found
     */
    public Node park(String hostname, boolean keepAllocation, Agent agent, String reason) {
        NestedTransaction transaction = new NestedTransaction();
        Node parked = park(hostname, keepAllocation, agent, reason, transaction);
        transaction.commit();
        return parked;
    }

    private Node park(String hostname, boolean keepAllocation, Agent agent, String reason, NestedTransaction transaction) {
        return move(hostname, Node.State.parked, agent, keepAllocation, Optional.of(reason), transaction);
    }

    /**
     * Parks all the nodes that are children of hostname before finally parking the hostname itself.
     *
     * @return List of all the parked nodes in their new state
     */
    public List<Node> parkRecursively(String hostname, Agent agent, String reason) {
        return moveRecursively(hostname, Node.State.parked, agent, Optional.of(reason));
    }

    /**
     * Moves a previously failed or parked node back to the active state.
     *
     * @return the node in its new state
     * @throws NoSuchNodeException if the node is not found
     */
    public Node reactivate(String hostname, Agent agent, String reason) {
        return move(hostname, Node.State.active, agent, true, Optional.of(reason));
    }

    /**
     * Moves a host to breakfixed state, removing any children.
     */
    public List<Node> breakfixRecursively(String hostname, Agent agent, String reason) {
        Node node = requireNode(hostname);
        try (Mutex lock = lockUnallocated()) {
            requireBreakfixable(node);
            NestedTransaction transaction = new NestedTransaction();
            List<Node> removed = removeChildren(node, false, transaction);
            removed.add(move(node.hostname(), Node.State.breakfixed, agent, true, Optional.of(reason), transaction));
            transaction.commit();
            return removed;
        }
    }

    private List<Node> moveRecursively(String hostname, Node.State toState, Agent agent, Optional<String> reason) {
        NestedTransaction transaction = new NestedTransaction();
        List<Node> moved = list().childrenOf(hostname).asList().stream()
                                 .map(child -> move(child.hostname(), toState, agent, true, reason, transaction))
                                 .collect(Collectors.toList());
        moved.add(move(hostname, toState, agent, true, reason, transaction));
        transaction.commit();
        return moved;
    }

    /** Move a node to given state */
    private Node move(String hostname, Node.State toState, Agent agent, boolean keepAllocation, Optional<String> reason) {
        NestedTransaction transaction = new NestedTransaction();
        Node moved = move(hostname, toState, agent, keepAllocation, reason, transaction);
        transaction.commit();
        return moved;
    }

    /** Move a node to given state as part of a transaction */
    private Node move(String hostname, Node.State toState, Agent agent, boolean keepAllocation, Optional<String> reason, NestedTransaction transaction) {
        // TODO: Work out a safe lock acquisition strategy for moves. Lock is only held while adding operations to
        //       transaction, but lock must also be held while committing
        try (NodeMutex lock = lockAndGetRequired(hostname)) {
            Node node = lock.node();
            if (toState == Node.State.active) {
                if (node.allocation().isEmpty()) illegal("Could not set " + node + " active: It has no allocation");
                if (!keepAllocation) illegal("Could not set " + node + " active: Requested to discard allocation");
                for (Node currentActive : list(Node.State.active).owner(node.allocation().get().owner())) {
                    if (node.allocation().get().membership().cluster().equals(currentActive.allocation().get().membership().cluster())
                        && node.allocation().get().membership().index() == currentActive.allocation().get().membership().index())
                        illegal("Could not set " + node + " active: Same cluster and index as " + currentActive);
                }
            }
            if (!keepAllocation && node.allocation().isPresent()) {
                node = node.withoutAllocation();
            }
            if (toState == Node.State.deprovisioned) {
                node = node.with(IP.Config.EMPTY);
            }
            return db.writeTo(toState, List.of(node), agent, reason, transaction).get(0);
        }
    }

    /*
     * This method is used by the REST API to handle readying nodes for new allocations. For Linux
     * containers this will remove the node from node repository, otherwise the node will be moved to state ready.
     */
    public Node markNodeAvailableForNewAllocation(String hostname, Agent agent, String reason) {
        Node node = requireNode(hostname);
        if (node.flavor().getType() == Flavor.Type.DOCKER_CONTAINER && node.type() == NodeType.tenant) {
            if (node.state() != Node.State.dirty)
                illegal("Cannot make " + node  + " available for new allocation as it is not in state [dirty]");
            return removeRecursively(node, true).get(0);
        }

        if (node.state() == Node.State.ready) return node;

        Node parentHost = node.parentHostname().flatMap(this::node).orElse(node);
        List<String> failureReasons = NodeFailer.reasonsToFailHost(parentHost);
        if ( ! failureReasons.isEmpty())
            illegal(node + " cannot be readied because it has hard failures: " + failureReasons);

        return setReady(List.of(node), agent, reason).get(0);
    }

    /**
     * Removes all the nodes that are children of hostname before finally removing the hostname itself.
     *
     * @return a List of all the nodes that have been removed or (for hosts) deprovisioned
     */
    public List<Node> removeRecursively(String hostname) {
        Node node = requireNode(hostname);
        return removeRecursively(node, false);
    }

    public List<Node> removeRecursively(Node node, boolean force) {
        try (Mutex lock = lockUnallocated()) {
            requireRemovable(node, false, force);
            NestedTransaction transaction = new NestedTransaction();
            final List<Node> removed;
            if (!node.type().isHost()) {
                removed = List.of(node);
                db.removeNodes(removed, transaction);
            } else {
                removed = removeChildren(node, force, transaction);
                if (zone.getCloud().dynamicProvisioning()) {
                    db.removeNodes(List.of(node), transaction);
                } else {
                    move(node.hostname(), Node.State.deprovisioned, Agent.system, false, Optional.empty(), transaction);
                }
                removed.add(node);
            }
            transaction.commit();
            return removed;
        }
    }

    /** Forgets a deprovisioned node. This removes all traces of the node in the node repository. */
    public void forget(Node node) {
        if (node.state() != Node.State.deprovisioned)
            throw new IllegalArgumentException(node + " must be deprovisioned before it can be forgotten");
        if (node.status().wantToRebuild())
            throw new IllegalArgumentException(node + " is rebuilding and cannot be forgotten");
        NestedTransaction transaction = new NestedTransaction();
        db.removeNodes(List.of(node), transaction);
        transaction.commit();
    }

    private List<Node> removeChildren(Node node, boolean force, NestedTransaction transaction) {
        List<Node> children = list().childrenOf(node).asList();
        children.forEach(child -> requireRemovable(child, true, force));
        db.removeNodes(children, transaction);
        return new ArrayList<>(children);
    }

    /**
     * Throws if the given node cannot be removed. Removal is allowed if:
     *  - Tenant node:
     *    - non-recursively: node is unallocated
     *    - recursively: node is unallocated or node is in failed|parked
     *  - Host node: iff in state provisioned|failed|parked
     *  - Child node:
     *    - non-recursively: node in state ready
     *    - recursively: child is in state provisioned|failed|parked|dirty|ready
     */
    private void requireRemovable(Node node, boolean removingRecursively, boolean force) {
        if (force) return;

        if (node.type() == NodeType.tenant && node.allocation().isPresent()) {
            EnumSet<Node.State> removableStates = EnumSet.of(Node.State.failed, Node.State.parked);
            if (!removingRecursively || !removableStates.contains(node.state()))
                illegal(node + " is currently allocated and cannot be removed while in " + node.state());
        }

        final Set<Node.State> removableStates;
        if (node.type().isHost()) {
            removableStates = EnumSet.of(Node.State.provisioned, Node.State.failed, Node.State.parked);
        } else {
            removableStates = removingRecursively
                    ? EnumSet.of(Node.State.provisioned, Node.State.failed, Node.State.parked, Node.State.dirty, Node.State.ready)
                    // When not removing recursively, we can only remove children in state ready
                    : EnumSet.of(Node.State.ready);
        }
        if (!removableStates.contains(node.state()))
            illegal(node + " can not be removed while in " + node.state());
    }

    /**
     * Throws if given node cannot be breakfixed.
     * Breakfix is allowed if the following is true:
     *  - Node is tenant host
     *  - Node is in zone without dynamic provisioning
     *  - Node is in parked or failed state
     */
    private void requireBreakfixable(Node node) {
        if (zone.getCloud().dynamicProvisioning()) {
            illegal("Can not breakfix in zone: " + zone);
        }

        if (node.type() != NodeType.host) {
            illegal(node + " can not be breakfixed as it is not a tenant host");
        }

        Set<Node.State> legalStates = EnumSet.of(Node.State.failed, Node.State.parked);
        if (! legalStates.contains(node.state())) {
            illegal(node + " can not be removed as it is not in the states " + legalStates);
        }
    }

    /**
     * Increases the restart generation of the active nodes matching given filter.
     *
     * @return the nodes in their new state
     */
    public List<Node> restartActive(Predicate<Node> filter) {
        return restart(NodeFilter.in(Set.of(Node.State.active)).and(filter));
    }

    /**
     * Increases the restart generation of the any nodes matching given filter.
     *
     * @return the nodes in their new state
     */
    public List<Node> restart(Predicate<Node> filter) {
        return performOn(filter, (node, lock) -> write(node.withRestart(node.allocation().get().restartGeneration().withIncreasedWanted()),
                                                       lock));
    }

    /**
     * Increases the reboot generation of the nodes matching the filter.
     *
     * @return the nodes in their new state
     */
    public List<Node> reboot(Predicate<Node> filter) {
        return performOn(filter, (node, lock) -> write(node.withReboot(node.status().reboot().withIncreasedWanted()), lock));
    }

    /**
     * Set target OS version of all nodes matching given filter.
     *
     * @return the nodes in their new state
     */
    public List<Node> upgradeOs(Predicate<Node> filter, Optional<Version> version) {
        return performOn(filter, (node, lock) -> {
            var newStatus = node.status().withOsVersion(node.status().osVersion().withWanted(version));
            return write(node.with(newStatus), lock);
        });
    }

    /** Retire nodes matching given filter */
    public List<Node> retire(Predicate<Node> filter, Agent agent, Instant instant) {
        return performOn(filter, (node, lock) -> write(node.withWantToRetire(true, agent, instant), lock));
    }

    /** Retire and deprovision given host and all of its children */
    public List<Node> deprovision(String hostname, Agent agent, Instant instant) {
        return decommission(hostname, DecommissionOperation.deprovision, agent, instant);
    }

    /** Retire and rebuild given host and all of its children */
    public List<Node> rebuild(String hostname, Agent agent, Instant instant) {
        return decommission(hostname, DecommissionOperation.rebuild, agent, instant);
    }

    private List<Node> decommission(String hostname, DecommissionOperation op, Agent agent, Instant instant) {
        Optional<NodeMutex> nodeMutex = lockAndGet(hostname);
        if (nodeMutex.isEmpty()) return List.of();
        Node host = nodeMutex.get().node();
        if (!host.type().isHost()) throw new IllegalArgumentException("Cannot " + op + " non-host " + host);
        List<Node> result;
        boolean wantToDeprovision = op == DecommissionOperation.deprovision;
        boolean wantToRebuild = op == DecommissionOperation.rebuild;
        try (NodeMutex lock = nodeMutex.get(); Mutex allocationLock = lockUnallocated()) {
            // This takes allocationLock to prevent any further allocation of nodes on this host
            host = lock.node();
            result = performOn(list(allocationLock).childrenOf(host), (node, nodeLock) -> {
                Node newNode = node.withWantToRetire(true, wantToDeprovision, wantToRebuild, agent, instant);
                return write(newNode, nodeLock);
            });
            Node newHost = host.withWantToRetire(true, wantToDeprovision, wantToRebuild, agent, instant);
            result.add(write(newHost, lock));
        }
        return result;
    }

    /**
     * Writes this node after it has changed some internal state but NOT changed its state field.
     * This does NOT lock the node repository implicitly, but callers are expected to already hold the lock.
     *
     * @param lock already acquired lock
     * @return the written node for convenience
     */
    public Node write(Node node, Mutex lock) { return write(List.of(node), lock).get(0); }

    /**
     * Writes these nodes after they have changed some internal state but NOT changed their state field.
     * This does NOT lock the node repository implicitly, but callers are expected to already hold the lock.
     *
     * @param lock already acquired lock
     * @return the written nodes for convenience
     */
    public List<Node> write(List<Node> nodes, @SuppressWarnings("unused") Mutex lock) {
        return db.writeTo(nodes, Agent.system, Optional.empty());
    }

    private List<Node> performOn(Predicate<Node> filter, BiFunction<Node, Mutex, Node> action) {
        return performOn(list().matching(filter), action);
    }

    /**
     * Performs an operation requiring locking on all nodes matching some filter.
     *
     * @param action the action to perform
     * @return the set of nodes on which the action was performed, as they became as a result of the operation
     */
    private List<Node> performOn(NodeList nodes, BiFunction<Node, Mutex, Node> action) {
        List<Node> unallocatedNodes = new ArrayList<>();
        ListMap<ApplicationId, Node> allocatedNodes = new ListMap<>();

        // Group matching nodes by the lock needed
        for (Node node : nodes) {
            if (node.allocation().isPresent())
                allocatedNodes.put(node.allocation().get().owner(), node);
            else
                unallocatedNodes.add(node);
        }

        // perform operation while holding locks
        List<Node> resultingNodes = new ArrayList<>();
        try (Mutex lock = lockUnallocated()) {
            for (Node node : unallocatedNodes) {
                Optional<Node> currentNode = db.readNode(node.hostname()); // Re-read while holding lock
                if (currentNode.isEmpty()) continue;
                resultingNodes.add(action.apply(currentNode.get(), lock));
            }
        }
        for (Map.Entry<ApplicationId, List<Node>> applicationNodes : allocatedNodes.entrySet()) {
            try (Mutex lock = lock(applicationNodes.getKey())) {
                for (Node node : applicationNodes.getValue()) {
                    Optional<Node> currentNode = db.readNode(node.hostname());  // Re-read while holding lock
                    if (currentNode.isEmpty()) continue;
                    resultingNodes.add(action.apply(currentNode.get(), lock));
                }
            }
        }
        return resultingNodes;
    }

    public boolean canAllocateTenantNodeTo(Node host) {
        return canAllocateTenantNodeTo(host, zone.getCloud().dynamicProvisioning());
    }

    public boolean canAllocateTenantNodeTo(Node host, boolean dynamicProvisioning) {
        if ( ! host.type().canRun(NodeType.tenant)) return false;
        if (host.status().wantToRetire()) return false;
        if (host.allocation().map(alloc -> alloc.membership().retired()).orElse(false)) return false;
        if (suspended(host)) return false;

        if (dynamicProvisioning)
            return EnumSet.of(Node.State.active, Node.State.ready, Node.State.provisioned).contains(host.state());
        else
            return host.state() == Node.State.active;
    }

    public boolean suspended(Node node) {
        try {
            return orchestrator.getNodeStatus(new HostName(node.hostname())).isSuspended();
        } catch (HostNameNotFoundException e) {
            // Treat it as not suspended
            return false;
        }
    }

    /** Create a lock which provides exclusive rights to making changes to the given application */
    // TODO: Move to Applications
    public Mutex lock(ApplicationId application) {
        return db.lock(application);
    }

    /** Create a lock with a timeout which provides exclusive rights to making changes to the given application */
    public Mutex lock(ApplicationId application, Duration timeout) {
        return db.lock(application, timeout);
    }

    /** Create a lock which provides exclusive rights to modifying unallocated nodes */
    public Mutex lockUnallocated() { return db.lockInactive(); }

    /** Returns the unallocated/application lock, and the node acquired under that lock. */
    public Optional<NodeMutex> lockAndGet(Node node) {
        Node staleNode = node;

        final int maxRetries = 4;
        for (int i = 0; i < maxRetries; ++i) {
            Mutex lockToClose = lock(staleNode);
            try {
                // As an optimization we first try finding the node in the same state
                Optional<Node> freshNode = node(staleNode.hostname(), staleNode.state());
                if (freshNode.isEmpty()) {
                    freshNode = node(staleNode.hostname());
                    if (freshNode.isEmpty()) {
                        return Optional.empty();
                    }
                }

                if (Objects.equals(freshNode.get().allocation().map(Allocation::owner),
                                   staleNode.allocation().map(Allocation::owner))) {
                    NodeMutex nodeMutex = new NodeMutex(freshNode.get(), lockToClose);
                    lockToClose = null;
                    return Optional.of(nodeMutex);
                }

                // The wrong lock was held when the fresh node was fetched, so try again
                staleNode = freshNode.get();
            } finally {
                if (lockToClose != null) lockToClose.close();
            }
        }

        throw new IllegalStateException("Giving up (after " + maxRetries + " attempts) " +
                                        "fetching an up to date node under lock: " + node.hostname());
    }

    /** Returns the unallocated/application lock, and the node acquired under that lock. */
    public Optional<NodeMutex> lockAndGet(String hostname) {
        return node(hostname).flatMap(this::lockAndGet);
    }

    /** Returns the unallocated/application lock, and the node acquired under that lock. */
    public NodeMutex lockAndGetRequired(Node node) {
        return lockAndGet(node).orElseThrow(() -> new NoSuchNodeException("No node with hostname '" + node.hostname() + "'"));
    }

    /** Returns the unallocated/application lock, and the node acquired under that lock. */
    public NodeMutex lockAndGetRequired(String hostname) {
        return lockAndGet(hostname).orElseThrow(() -> new NoSuchNodeException("No node with hostname '" + hostname + "'"));
    }

    private Mutex lock(Node node) {
        return node.allocation().isPresent() ? lock(node.allocation().get().owner()) : lockUnallocated();
    }

    private Node requireNode(String hostname) {
        return node(hostname).orElseThrow(() -> new NoSuchNodeException("No node with hostname '" + hostname + "'"));
    }

    private void illegal(String message) {
        throw new IllegalArgumentException(message);
    }

    /** Returns whether node should be parked when deallocated by given agent */
    private static boolean parkOnDeallocationOf(Node node, Agent agent) {
        if (node.state() == Node.State.parked) return false;
        if (agent == Agent.operator) return false;
        if (!node.type().isHost() && node.status().wantToDeprovision()) return false;
        boolean retirementRequestedByOperator = node.status().wantToRetire() &&
                                                node.history().event(History.Event.Type.wantToRetire)
                                                    .map(History.Event::agent)
                                                    .map(a -> a == Agent.operator)
                                                    .orElse(false);
        return node.status().wantToDeprovision() ||
               node.status().wantToRebuild() ||
               retirementRequestedByOperator;
    }

    /** The different ways a host can be decommissioned */
    private enum DecommissionOperation {
        deprovision,
        rebuild,
    }

}
