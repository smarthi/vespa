// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.deployment;

import com.yahoo.component.Version;
import com.yahoo.config.application.api.DeploymentInstanceSpec;
import com.yahoo.config.application.api.DeploymentSpec;
import com.yahoo.config.application.api.Notifications;
import com.yahoo.config.application.api.Notifications.When;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.AthenzDomain;
import com.yahoo.config.provision.AthenzService;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.HostName;
import com.yahoo.config.provision.NodeResources;
import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.zone.RoutingMethod;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.log.LogLevel;
import com.yahoo.security.KeyAlgorithm;
import com.yahoo.security.KeyUtils;
import com.yahoo.security.SignatureAlgorithm;
import com.yahoo.security.X509CertificateBuilder;
import com.yahoo.security.X509CertificateUtils;
import com.yahoo.text.Text;
import com.yahoo.vespa.hosted.controller.Application;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.Instance;
import com.yahoo.vespa.hosted.controller.api.identifiers.DeploymentId;
import com.yahoo.vespa.hosted.controller.api.integration.LogEntry;
import com.yahoo.vespa.hosted.controller.api.integration.certificates.EndpointCertificateException;
import com.yahoo.vespa.hosted.controller.api.integration.configserver.ConfigServerException;
import com.yahoo.vespa.hosted.controller.api.integration.configserver.Node;
import com.yahoo.vespa.hosted.controller.api.integration.configserver.NodeFilter;
import com.yahoo.vespa.hosted.controller.api.integration.configserver.PrepareResponse;
import com.yahoo.vespa.hosted.controller.api.integration.configserver.ServiceConvergence;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.ApplicationVersion;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.RunId;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.TesterCloud;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.TesterId;
import com.yahoo.vespa.hosted.controller.api.integration.organization.DeploymentFailureMails;
import com.yahoo.vespa.hosted.controller.api.integration.organization.Mail;
import com.yahoo.vespa.hosted.controller.application.ActivateResult;
import com.yahoo.vespa.hosted.controller.application.Deployment;
import com.yahoo.vespa.hosted.controller.application.Endpoint;
import com.yahoo.vespa.hosted.controller.application.TenantAndApplicationId;
import com.yahoo.vespa.hosted.controller.application.pkg.ApplicationPackage;
import com.yahoo.vespa.hosted.controller.config.ControllerConfig;
import com.yahoo.vespa.hosted.controller.maintenance.JobRunner;
import com.yahoo.vespa.hosted.controller.notification.Notification;
import com.yahoo.vespa.hosted.controller.notification.NotificationSource;
import com.yahoo.vespa.hosted.controller.routing.RoutingPolicy;
import com.yahoo.vespa.hosted.controller.routing.context.DeploymentRoutingContext;
import com.yahoo.yolean.Exceptions;

import javax.security.auth.x500.X500Principal;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.yahoo.config.application.api.Notifications.Role.author;
import static com.yahoo.config.application.api.Notifications.When.failing;
import static com.yahoo.config.application.api.Notifications.When.failingCommit;
import static com.yahoo.vespa.hosted.controller.api.integration.configserver.Node.State.active;
import static com.yahoo.vespa.hosted.controller.api.integration.configserver.Node.State.reserved;
import static com.yahoo.vespa.hosted.controller.deployment.RunStatus.deploymentFailed;
import static com.yahoo.vespa.hosted.controller.deployment.RunStatus.error;
import static com.yahoo.vespa.hosted.controller.deployment.RunStatus.installationFailed;
import static com.yahoo.vespa.hosted.controller.deployment.RunStatus.outOfCapacity;
import static com.yahoo.vespa.hosted.controller.deployment.RunStatus.reset;
import static com.yahoo.vespa.hosted.controller.deployment.RunStatus.running;
import static com.yahoo.vespa.hosted.controller.deployment.RunStatus.testFailure;
import static com.yahoo.vespa.hosted.controller.deployment.Step.Status.succeeded;
import static com.yahoo.vespa.hosted.controller.deployment.Step.copyVespaLogs;
import static com.yahoo.vespa.hosted.controller.deployment.Step.deactivateReal;
import static com.yahoo.vespa.hosted.controller.deployment.Step.deactivateTester;
import static com.yahoo.vespa.hosted.controller.deployment.Step.deployInitialReal;
import static com.yahoo.vespa.hosted.controller.deployment.Step.deployReal;
import static com.yahoo.vespa.hosted.controller.deployment.Step.deployTester;
import static com.yahoo.vespa.hosted.controller.deployment.Step.installTester;
import static com.yahoo.vespa.hosted.controller.deployment.Step.report;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

/**
 * Runs steps of a deployment job against its provided controller.
 *
 * A dual-purpose logger is set up for each step run here:
 *   1. all messages are logged to a buffer which is stored in an external log storage at the end of execution, and
 *   2. all messages are also logged through the usual logging framework; by default, any messages of level
 *      {@code Level.INFO} or higher end up in the Vespa log, and all messages may be sent there by means of log-control.
 *
 * @author jonmv
 */
public class InternalStepRunner implements StepRunner {

    private static final Logger logger = Logger.getLogger(InternalStepRunner.class.getName());

    static final NodeResources DEFAULT_TESTER_RESOURCES =
            new NodeResources(1, 4, 50, 0.3, NodeResources.DiskSpeed.any);
    // Must match exactly the advertised resources of an AWS instance type. Also consider that the container
    // will have ~1.8 GB less memory than equivalent resources in AWS (VESPA-16259).
    static final NodeResources DEFAULT_TESTER_RESOURCES_AWS =
            new NodeResources(2, 8, 50, 0.3, NodeResources.DiskSpeed.any);

    private final Controller controller;
    private final TestConfigSerializer testConfigSerializer;
    private final DeploymentFailureMails mails;
    private final Timeouts timeouts;

    public InternalStepRunner(Controller controller) {
        this.controller = controller;
        this.testConfigSerializer = new TestConfigSerializer(controller.system());
        this.mails = new DeploymentFailureMails(controller.zoneRegistry());
        this.timeouts = Timeouts.of(controller.system());
    }

    @Override
    public Optional<RunStatus> run(LockedStep step, RunId id) {
        DualLogger logger = new DualLogger(id, step.get());
        try {
            switch (step.get()) {
                case deployTester: return deployTester(id, logger);
                case deployInitialReal: return deployInitialReal(id, logger);
                case installInitialReal: return installInitialReal(id, logger);
                case deployReal: return deployReal(id, logger);
                case installTester: return installTester(id, logger);
                case installReal: return installReal(id, logger);
                case startStagingSetup: return startTests(id, true, logger);
                case endStagingSetup:
                case endTests: return endTests(id, logger);
                case startTests: return startTests(id, false, logger);
                case copyVespaLogs: return copyVespaLogs(id, logger);
                case deactivateReal: return deactivateReal(id, logger);
                case deactivateTester: return deactivateTester(id, logger);
                case report: return report(id, logger);
                default: throw new AssertionError("Unknown step '" + step + "'!");
            }
        }
        catch (UncheckedIOException e) {
            logger.logWithInternalException(INFO, "IO exception running " + id + ": " + Exceptions.toMessageString(e), e);
            return Optional.empty();
        }
        catch (RuntimeException e) {
            logger.log(WARNING, "Unexpected exception running " + id, e);
            if (step.get().alwaysRun()) {
                logger.log("Will keep trying, as this is a cleanup step.");
                return Optional.empty();
            }
            return Optional.of(error);
        }
    }

    private Optional<RunStatus> deployInitialReal(RunId id, DualLogger logger) {
        Versions versions = controller.jobController().run(id).get().versions();
        logger.log("Deploying platform version " +
                   versions.sourcePlatform().orElse(versions.targetPlatform()) +
                   " and application version " +
                   versions.sourceApplication().orElse(versions.targetApplication()).id() + " ...");
        return deployReal(id, true, logger);
    }

    private Optional<RunStatus> deployReal(RunId id, DualLogger logger) {
        Versions versions = controller.jobController().run(id).get().versions();
        logger.log("Deploying platform version " + versions.targetPlatform() +
                   " and application version " + versions.targetApplication().id() + " ...");
        return deployReal(id, false, logger);
    }

    private Optional<RunStatus> deployReal(RunId id, boolean setTheStage, DualLogger logger) {
        Optional<X509Certificate> testerCertificate = controller.jobController().run(id).get().testerCertificate();
        return deploy(() -> controller.applications().deploy(id.job(), setTheStage),
                      controller.jobController().run(id).get()
                                .stepInfo(setTheStage ? deployInitialReal : deployReal).get()
                                .startTime().get(),
                      logger)
                .filter(result -> {
                    // If no tester cert, or deployment failed, propagate original result.
                    if ( ! useTesterCertificate(id) || result != running)
                        return true;
                    // If tester cert, ensure real is deployed with the tester cert whose key was successfully deployed.
                    return    controller.jobController().run(id).get().stepStatus(deployTester).get() == succeeded
                           && testerCertificate.equals(controller.jobController().run(id).get().testerCertificate());
                });
    }

    private Optional<RunStatus> deployTester(RunId id, DualLogger logger) {
        Version platform = testerPlatformVersion(id);
        logger.log("Deploying the tester container on platform " + platform + " ...");
        return deploy(() -> controller.applications().deployTester(id.tester(),
                                                                   testerPackage(id),
                                                                   id.type().zone(controller.system()),
                                                                   platform),
                      controller.jobController().run(id).get()
                                .stepInfo(deployTester).get()
                                .startTime().get(),
                      logger);
    }

    @SuppressWarnings("deprecation")
    private Optional<RunStatus> deploy(Supplier<ActivateResult> deployment, Instant startTime, DualLogger logger) {
        try {
            PrepareResponse prepareResponse = deployment.get().prepareResponse();
            if (prepareResponse.log != null)
                logger.logAll(prepareResponse.log.stream()
                                                 .map(entry -> new LogEntry(0, // Sequenced by BufferedLogStore.
                                                                            Instant.ofEpochMilli(entry.time),
                                                                            LogEntry.typeOf(LogLevel.parse(entry.level)),
                                                                            entry.message))
                                                 .collect(toList()));

            logger.log("Deployment successful.");
            if (prepareResponse.message != null)
                logger.log(prepareResponse.message);

            return Optional.of(running);
        }
        catch (ConfigServerException e) {
            // Retry certain failures for up to one hour.
            Optional<RunStatus> result = startTime.isBefore(controller.clock().instant().minus(Duration.ofHours(1)))
                                         ? Optional.of(deploymentFailed) : Optional.empty();
            switch (e.code()) {
                case CERTIFICATE_NOT_READY:
                    logger.log("No valid CA signed certificate for app available to config server");
                    if (startTime.plus(timeouts.endpointCertificate()).isBefore(controller.clock().instant())) {
                        logger.log(WARNING, "CA signed certificate for app not available to config server within " + timeouts.endpointCertificate());
                        return Optional.of(RunStatus.endpointCertificateTimeout);
                    }
                    return result;
                case ACTIVATION_CONFLICT:
                case APPLICATION_LOCK_FAILURE:
                case CONFIG_NOT_CONVERGED:
                    logger.log("Deployment failed with possibly transient error " + e.code() +
                               ", will retry: " + e.getMessage());
                    return result;
                case LOAD_BALANCER_NOT_READY:
                case PARENT_HOST_NOT_READY:
                    logger.log(e.message()); // Consider splitting these messages in summary and details, on config server.
                    return result;
                case OUT_OF_CAPACITY:
                    logger.log(e.message());
                    return controller.system().isCd() && startTime.plus(timeouts.capacity()).isAfter(controller.clock().instant())
                           ? result
                           : Optional.of(outOfCapacity);
                case INVALID_APPLICATION_PACKAGE:
                case BAD_REQUEST:
                    logger.log(WARNING, e.getMessage());
                    return Optional.of(deploymentFailed);
            }

            throw e;
        }
        catch (EndpointCertificateException e) {
            switch (e.type()) {
                case CERT_NOT_AVAILABLE:
                    // Same as CERTIFICATE_NOT_READY above, only from the controller
                    logger.log("Validating CA signed certificate requested for app: not yet available");
                    if (startTime.plus(timeouts.endpointCertificate()).isBefore(controller.clock().instant())) {
                        logger.log(WARNING, "CA signed certificate for app not available within " +
                                   timeouts.endpointCertificate() + ": " + Exceptions.toMessageString(e));
                        return Optional.of(RunStatus.endpointCertificateTimeout);
                    }
                    return Optional.empty();
                default:
                    throw e; // Should be surfaced / fail deployment
            }
        }
    }

    private Optional<RunStatus> installInitialReal(RunId id, DualLogger logger) {
        return installReal(id, true, logger);
    }

    private Optional<RunStatus> installReal(RunId id, DualLogger logger) {
        return installReal(id, false, logger);
    }

    private Optional<RunStatus> installReal(RunId id, boolean setTheStage, DualLogger logger) {
        Optional<Deployment> deployment = deployment(id.application(), id.type());
        if (deployment.isEmpty()) {
            logger.log("Deployment expired before installation was successful.");
            return Optional.of(installationFailed);
        }

        Versions versions = controller.jobController().run(id).get().versions();
        Version platform = setTheStage ? versions.sourcePlatform().orElse(versions.targetPlatform()) : versions.targetPlatform();

        Run run = controller.jobController().run(id).get();
        Optional<ServiceConvergence> services = controller.serviceRegistry().configServer().serviceConvergence(new DeploymentId(id.application(), id.type().zone(controller.system())),
                                                                                                               Optional.of(platform));
        if (services.isEmpty()) {
            logger.log("Config status not currently available -- will retry.");
            return Optional.empty();
        }
        List<Node> nodes = controller.serviceRegistry().configServer().nodeRepository().list(id.type().zone(controller.system()),
                                                                                             NodeFilter.all()
                                                                                                       .applications(id.application())
                                                                                                       .states(active));

        Set<HostName> parentHostnames = nodes.stream().map(node -> node.parentHostname().get()).collect(toSet());
        List<Node> parents = controller.serviceRegistry().configServer().nodeRepository().list(id.type().zone(controller.system()),
                                                                                               NodeFilter.all()
                                                                                                         .hostnames(parentHostnames));
        boolean firstTick = run.convergenceSummary().isEmpty();
        NodeList nodeList = NodeList.of(nodes, parents, services.get());
        ConvergenceSummary summary = nodeList.summary();
        if (firstTick) { // Run the first time (for each convergence step).
            logger.log("######## Details for all nodes ########");
            logger.log(nodeList.asList().stream()
                               .flatMap(node -> nodeDetails(node, true))
                               .collect(toList()));
        }
        else if ( ! summary.converged()) {
            logger.log("Waiting for convergence of " + summary.services() + " services across " + summary.nodes() + " nodes");
            if (summary.needPlatformUpgrade() > 0)
                logger.log(summary.upgradingPlatform() + "/" + summary.needPlatformUpgrade() + " nodes upgrading platform");
            if (summary.needReboot() > 0)
                logger.log(summary.rebooting() + "/" + summary.needReboot() + " nodes rebooting");
            if (summary.needRestart() > 0)
                logger.log(summary.restarting() + "/" + summary.needRestart() + " nodes restarting");
            if (summary.retiring() > 0)
                logger.log(summary.retiring() + " nodes retiring");
            if (summary.upgradingFirmware() > 0)
                logger.log(summary.upgradingFirmware() + " nodes upgrading firmware");
            if (summary.upgradingOs() > 0)
                logger.log(summary.upgradingOs() + " nodes upgrading OS");
            if (summary.needNewConfig() > 0)
                logger.log(summary.needNewConfig() + " application services upgrading");
        }
        if (summary.converged()) {
            controller.jobController().locked(id, lockedRun -> lockedRun.withSummary(null));
            if (endpointsAvailable(id.application(), id.type().zone(controller.system()), logger)) {
                if (containersAreUp(id.application(), id.type().zone(controller.system()), logger)) {
                    logger.log("Installation succeeded!");
                    return Optional.of(running);
                }
            }
            else if (timedOut(id, deployment.get(), timeouts.endpoint())) {
                logger.log(WARNING, "Endpoints failed to show up within " + timeouts.endpoint().toMinutes() + " minutes!");
                return Optional.of(error);
            }
        }

        String failureReason = null;

        NodeList suspendedTooLong = nodeList.isStateful()
                                            .suspendedSince(controller.clock().instant().minus(timeouts.statefulNodesDown()))
                                            .and(nodeList.not().isStateful()
                                                         .suspendedSince(controller.clock().instant().minus(timeouts.statelessNodesDown()))
                                            );
        if ( ! suspendedTooLong.isEmpty() && deployment.get().at().plus(timeouts.statelessNodesDown()).isBefore(controller.clock().instant())) {
            failureReason = "Some nodes have been suspended for more than the allowed threshold:\n" +
                            suspendedTooLong.asList().stream().map(node -> node.node().hostname().value()).collect(joining("\n"));
        }

        if (run.noNodesDownSince()
               .map(since -> since.isBefore(controller.clock().instant().minus(timeouts.noNodesDown())))
               .orElse(false)) {
            if (summary.needPlatformUpgrade() > 0 || summary.needReboot() > 0 || summary.needRestart() > 0)
                failureReason = "Timed out after waiting " + timeouts.noNodesDown().toMinutes() + " minutes for " +
                                "nodes to suspend. This is normal if the cluster is excessively busy. " +
                                "Nodes will continue to attempt suspension to progress installation independently of " +
                                "this run.";
            else
                failureReason = "Nodes not able to start with new application package.";
        }

        Duration timeout = JobRunner.jobTimeout.minusHours(1); // Time out before job dies.
        if (timedOut(id, deployment.get(), timeout)) {
            failureReason = "Installation failed to complete within " + timeout.toHours() + "hours!";
        }

        if (failureReason != null) {
            logger.log("######## Details for all nodes ########");
            logger.log(nodeList.asList().stream()
                               .flatMap(node -> nodeDetails(node, true))
                               .collect(toList()));
            logger.log("######## Details for nodes with pending changes ########");
            logger.log(nodeList.not().in(nodeList.not().needsNewConfig()
                                                 .not().needsPlatformUpgrade()
                                                 .not().needsReboot()
                                                 .not().needsRestart()
                                                 .not().needsFirmwareUpgrade()
                                                 .not().needsOsUpgrade())
                               .asList().stream()
                               .flatMap(node -> nodeDetails(node, true))
                               .collect(toList()));
            logger.log(INFO, failureReason);
            return Optional.of(installationFailed);
        }

        if ( ! firstTick)
            logger.log(FINE, nodeList.expectedDown().and(nodeList.needsNewConfig()).asList().stream()
                                     .distinct()
                                     .flatMap(node -> nodeDetails(node, false))
                                     .collect(toList()));

        controller.jobController().locked(id, lockedRun -> {
            Instant noNodesDownSince = nodeList.allowedDown().size() == 0 ? lockedRun.noNodesDownSince().orElse(controller.clock().instant()) : null;
            return lockedRun.noNodesDownSince(noNodesDownSince).withSummary(summary);
        });

        return Optional.empty();
    }

    private Version testerPlatformVersion(RunId id) {
        return application(id.application()).change().isPinned()
               ? controller.jobController().run(id).get().versions().targetPlatform()
               : controller.readSystemVersion();
    }

    private Optional<RunStatus> installTester(RunId id, DualLogger logger) {
        Run run = controller.jobController().run(id).get();
        Version platform = testerPlatformVersion(id);
        ZoneId zone = id.type().zone(controller.system());
        ApplicationId testerId = id.tester().id();

        Optional<ServiceConvergence> services = controller.serviceRegistry().configServer().serviceConvergence(new DeploymentId(testerId, zone),
                                                                                                               Optional.of(platform));
        if (services.isEmpty()) {
            logger.log("Config status not currently available -- will retry.");
            return run.stepInfo(installTester).get().startTime().get().isBefore(controller.clock().instant().minus(Duration.ofMinutes(5)))
                   ? Optional.of(error)
                   : Optional.empty();
        }
        List<Node> nodes = controller.serviceRegistry().configServer().nodeRepository().list(zone,
                                                                                             NodeFilter.all()
                                                                                                       .applications(testerId)
                                                                                                       .states(active, reserved));
        Set<HostName> parentHostnames = nodes.stream().map(node -> node.parentHostname().get()).collect(toSet());
        List<Node> parents = controller.serviceRegistry().configServer().nodeRepository().list(zone,
                                                                                               NodeFilter.all()
                                                                                                         .hostnames(parentHostnames));
        NodeList nodeList = NodeList.of(nodes, parents, services.get());
        logger.log(nodeList.asList().stream()
                           .flatMap(node -> nodeDetails(node, false))
                           .collect(toList()));

        if (nodeList.summary().converged() && testerContainersAreUp(testerId, zone, logger)) {
            logger.log("Tester container successfully installed!");
            return Optional.of(running);
        }

        if (run.stepInfo(installTester).get().startTime().get().plus(timeouts.tester()).isBefore(controller.clock().instant())) {
            logger.log(WARNING, "Installation of tester failed to complete within " + timeouts.tester().toMinutes() + " minutes!");
            return Optional.of(error);
        }

        return Optional.empty();
    }

    /** Returns true iff all calls to endpoint in the deployment give 100 consecutive 200 OK responses on /status.html. */
    private boolean containersAreUp(ApplicationId id, ZoneId zoneId, DualLogger logger) {
        var endpoints = controller.routing().readTestRunnerEndpointsOf(Set.of(new DeploymentId(id, zoneId)));
        if ( ! endpoints.containsKey(zoneId))
            return false;

        return endpoints.get(zoneId).parallelStream().allMatch(endpoint -> {
            boolean ready = controller.jobController().cloud().ready(endpoint.url());
            if (!ready) {
                logger.log("Failed to get 100 consecutive OKs from " + endpoint);
            }
            return ready;
        });
    }

    /** Returns true iff all containers in the tester deployment give 100 consecutive 200 OK responses on /status.html. */
    private boolean testerContainersAreUp(ApplicationId id, ZoneId zoneId, DualLogger logger) {
        DeploymentId deploymentId = new DeploymentId(id, zoneId);
        if (controller.jobController().cloud().testerReady(deploymentId)) {
            return true;
        } else {
            logger.log("Failed to get 100 consecutive OKs from tester container for " + deploymentId);
            return false;
        }
    }

    private boolean endpointsAvailable(ApplicationId id, ZoneId zone, DualLogger logger) {
        DeploymentId deployment = new DeploymentId(id, zone);
        Map<ZoneId, List<Endpoint>> endpoints = controller.routing().readTestRunnerEndpointsOf(Set.of(deployment));
        if ( ! endpoints.containsKey(zone)) {
            logger.log("Endpoints not yet ready.");
            return false;
        }
        for (var endpoint : endpoints.get(zone)) {
            HostName endpointName = HostName.from(endpoint.dnsName());
            var ipAddress = controller.jobController().cloud().resolveHostName(endpointName);
            if (ipAddress.isEmpty()) {
                logger.log(INFO, "DNS lookup yielded no IP address for '" + endpointName + "'.");
                return false;
            }
            DeploymentRoutingContext context = controller.routing().of(deployment);
            if (context.routingMethod() == RoutingMethod.exclusive)  {
                RoutingPolicy policy = context.routingPolicy(ClusterSpec.Id.from(endpoint.name()))
                                              .orElseThrow(() -> new IllegalStateException(endpoint + " has no matching policy"));

                var cNameValue = controller.jobController().cloud().resolveCname(endpointName);
                if ( ! cNameValue.map(policy.canonicalName()::equals).orElse(false)) {
                    logger.log(INFO, "CNAME '" + endpointName + "' points at " +
                                     cNameValue.map(name -> "'" + name + "'").orElse("nothing") +
                                     " but should point at load balancer '" + policy.canonicalName() + "'");
                    return false;
                }
                var loadBalancerAddress = controller.jobController().cloud().resolveHostName(policy.canonicalName());
                if ( ! loadBalancerAddress.equals(ipAddress)) {
                    logger.log(INFO, "IP address of CNAME '" + endpointName + "' (" + ipAddress.get() + ") and load balancer '" +
                                     policy.canonicalName() + "' (" + loadBalancerAddress.orElse("empty") + ") are not equal");
                    return false;
                }
            }
        }

        logEndpoints(endpoints, logger);
        return true;
    }

    private void logEndpoints(Map<ZoneId, List<Endpoint>> zoneEndpoints, DualLogger logger) {
        List<String> messages = new ArrayList<>();
        messages.add("Found endpoints:");
        zoneEndpoints.forEach((zone, endpoints) -> {
            messages.add("- " + zone);
            for (Endpoint endpoint : endpoints)
                messages.add(" |-- " + endpoint.url() + " (cluster '" + endpoint.name() + "')");
        });
        logger.log(messages);
    }

    private Stream<String> nodeDetails(NodeWithServices node, boolean printAllServices) {
        return Stream.concat(Stream.of(node.node().hostname() + ": " + humanize(node.node().serviceState()) + (node.node().suspendedSince().map(since -> " since " + since).orElse("")),
                                       "--- platform " + wantedPlatform(node.node()) + (node.needsPlatformUpgrade()
                                                                                        ? " <-- " + currentPlatform(node.node())
                                                                                        : "") +
                                       (node.needsOsUpgrade() && node.isAllowedDown()
                                        ? ", upgrading OS (" + node.parent().wantedOsVersion() + " <-- " + node.parent().currentOsVersion() + ")"
                                        : "") +
                                       (node.needsFirmwareUpgrade() && node.isAllowedDown()
                                        ? ", upgrading firmware"
                                        : "") +
                                       (node.needsRestart()
                                        ? ", restart pending (" + node.node().wantedRestartGeneration() + " <-- " + node.node().restartGeneration() + ")"
                                        : "") +
                                       (node.needsReboot()
                                        ? ", reboot pending (" + node.node().wantedRebootGeneration() + " <-- " + node.node().rebootGeneration() + ")"
                                        : "")),
                             node.services().stream()
                                 .filter(service -> printAllServices || node.needsNewConfig())
                                 .map(service -> "--- " + service.type() + " on port " + service.port() + (service.currentGeneration() == -1
                                                                                                           ? " has not started "
                                                                                                           : " has config generation " + service.currentGeneration() + ", wanted is " + node.wantedConfigGeneration())));
    }


    private String wantedPlatform(Node node) {
        return node.wantedDockerImage().repository() + ":" + node.wantedVersion();
    }

    private String currentPlatform(Node node) {
        String currentRepo = node.currentDockerImage().repository();
        String wantedRepo = node.wantedDockerImage().repository();
        return (currentRepo.equals(wantedRepo) ? "" : currentRepo + ":") + node.currentVersion();
    }

    private String humanize(Node.ServiceState state) {
        switch (state) {
            case allowedDown: return "allowed to be DOWN";
            case expectedUp: return "expected to be UP";
            case permanentlyDown: return "permanently DOWN";
            case unorchestrated: return "unorchestrated";
            default: return state.name();
        }
    }

    private Optional<RunStatus> startTests(RunId id, boolean isSetup, DualLogger logger) {
        Optional<Deployment> deployment = deployment(id.application(), id.type());
        if (deployment.isEmpty()) {
            logger.log(INFO, "Deployment expired before tests could start.");
            return Optional.of(error);
        }

        var deployments = controller.applications().requireInstance(id.application())
                                    .productionDeployments().keySet().stream()
                                    .map(zone -> new DeploymentId(id.application(), zone))
                                    .collect(Collectors.toSet());
        ZoneId zoneId = id.type().zone(controller.system());
        deployments.add(new DeploymentId(id.application(), zoneId));

        logger.log("Attempting to find endpoints ...");
        var endpoints = controller.routing().readTestRunnerEndpointsOf(deployments);
        if ( ! endpoints.containsKey(zoneId)) {
            logger.log(WARNING, "Endpoints for the deployment to test vanished again, while it was still active!");
            return Optional.of(error);
        }
        logEndpoints(endpoints, logger);

        if (!controller.jobController().cloud().testerReady(getTesterDeploymentId(id))) {
            logger.log(WARNING, "Tester container went bad!");
            return Optional.of(error);
        }

        logger.log("Starting tests ...");
        TesterCloud.Suite suite = TesterCloud.Suite.of(id.type(), isSetup);
        byte[] config = testConfigSerializer.configJson(id.application(),
                                                        id.type(),
                                                        true,
                                                        endpoints,
                                                        controller.applications().reachableContentClustersByZone(deployments));
        controller.jobController().cloud().startTests(getTesterDeploymentId(id), suite, config);
        return Optional.of(running);
    }

    private Optional<RunStatus> endTests(RunId id, DualLogger logger) {
        Optional<Deployment> deployment = deployment(id.application(), id.type());
        if (deployment.isEmpty()) {
            logger.log(INFO, "Deployment expired before tests could complete.");
            return Optional.of(error);
        }

        Optional<X509Certificate> testerCertificate = controller.jobController().run(id).get().testerCertificate();
        if (testerCertificate.isPresent()) {
            try {
                testerCertificate.get().checkValidity(Date.from(controller.clock().instant()));
            }
            catch (CertificateExpiredException | CertificateNotYetValidException e) {
                logger.log(WARNING, "Tester certificate expired before tests could complete.");
                return Optional.of(error);
            }
        }

        controller.jobController().updateTestLog(id);

        TesterCloud.Status testStatus = controller.jobController().cloud().getStatus(getTesterDeploymentId(id));
        switch (testStatus) {
            case NOT_STARTED:
                throw new IllegalStateException("Tester reports tests not started, even though they should have!");
            case RUNNING:
                return Optional.empty();
            case FAILURE:
                logger.log("Tests failed.");
                controller.jobController().updateTestReport(id);
                return Optional.of(testFailure);
            case INCONCLUSIVE:
                long sleepMinutes = Math.max(15, Math.min(120, Duration.between(deployment.get().at(), controller.clock().instant()).toMinutes() / 20));
                logger.log("Tests were inconclusive, and will run again in " + sleepMinutes + " minutes.");
                controller.jobController().locked(id, run -> run.sleepingUntil(controller.clock().instant().plusSeconds(60 * sleepMinutes)));
                return Optional.of(reset);
            case ERROR:
                logger.log(INFO, "Tester failed running its tests!");
                controller.jobController().updateTestReport(id);
                return Optional.of(error);
            case SUCCESS:
                logger.log("Tests completed successfully.");
                controller.jobController().updateTestReport(id);
                return Optional.of(running);
            default:
                throw new IllegalStateException("Unknown status '" + testStatus + "'!");
        }
    }

    private Optional<RunStatus> copyVespaLogs(RunId id, DualLogger logger) {
        if (deployment(id.application(), id.type()).isPresent())
            try {
                controller.jobController().updateVespaLog(id);
            }
            // Hitting a config server which doesn't have this particular app loaded causes a 404.
            catch (ConfigServerException e) {
                Instant doom = controller.jobController().run(id).get().stepInfo(copyVespaLogs).get().startTime().get()
                                         .plus(Duration.ofMinutes(3));
                if (e.code() == ConfigServerException.ErrorCode.NOT_FOUND && controller.clock().instant().isBefore(doom)) {
                    logger.log(INFO, "Found no logs, but will retry");
                    return Optional.empty();
                }
                else {
                    logger.log(INFO, "Failure getting vespa logs for " + id, e);
                    return Optional.of(error);
                }
            }
            catch (Exception e) {
                logger.log(INFO, "Failure getting vespa logs for " + id, e);
                return Optional.of(error);
            }
        return Optional.of(running);
    }

    private Optional<RunStatus> deactivateReal(RunId id, DualLogger logger) {
        try {
            logger.log("Deactivating deployment of " + id.application() + " in " + id.type().zone(controller.system()) + " ...");
            controller.applications().deactivate(id.application(), id.type().zone(controller.system()));
            return Optional.of(running);
        }
        catch (RuntimeException e) {
            logger.log(WARNING, "Failed deleting application " + id.application(), e);
            Instant startTime = controller.jobController().run(id).get().stepInfo(deactivateReal).get().startTime().get();
            return startTime.isBefore(controller.clock().instant().minus(Duration.ofHours(1)))
                   ? Optional.of(error)
                   : Optional.empty();
        }
    }

    private Optional<RunStatus> deactivateTester(RunId id, DualLogger logger) {
        try {
            logger.log("Deactivating tester of " + id.application() + " in " + id.type().zone(controller.system()) + " ...");
            controller.jobController().deactivateTester(id.tester(), id.type());
            return Optional.of(running);
        }
        catch (RuntimeException e) {
            logger.log(WARNING, "Failed deleting tester of " + id.application(), e);
            Instant startTime = controller.jobController().run(id).get().stepInfo(deactivateTester).get().startTime().get();
            return startTime.isBefore(controller.clock().instant().minus(Duration.ofHours(1)))
                   ? Optional.of(error)
                   : Optional.empty();
        }
    }

    private Optional<RunStatus> report(RunId id, DualLogger logger) {
        try {
            controller.jobController().active(id).ifPresent(run -> {
                if (run.status() == reset)
                    return;

                if (run.hasFailed())
                    sendEmailNotification(run, logger);

                updateConsoleNotification(run);
            });
        }
        catch (IllegalStateException e) {
            logger.log(INFO, "Job '" + id.type() + "' no longer supposed to run?", e);
            return Optional.of(error);
        }
        catch (RuntimeException e) {
            Instant start = controller.jobController().run(id).get().stepInfo(report).get().startTime().get();
            return (controller.clock().instant().isAfter(start.plusSeconds(180)))
                   ? Optional.empty()
                   : Optional.of(error);
        }
        return Optional.of(running);
    }

    /** Sends a mail with a notification of a failed run, if one should be sent. */
    private void sendEmailNotification(Run run, DualLogger logger) {
        if ( ! isNewFailure(run))
            return;

        Application application = controller.applications().requireApplication(TenantAndApplicationId.from(run.id().application()));
        Notifications notifications = application.deploymentSpec().requireInstance(run.id().application().instance()).notifications();
        boolean newCommit = application.require(run.id().application().instance()).change().application()
                                    .map(run.versions().targetApplication()::equals)
                                    .orElse(false);
        When when = newCommit ? failingCommit : failing;

        List<String> recipients = new ArrayList<>(notifications.emailAddressesFor(when));
        if (notifications.emailRolesFor(when).contains(author))
            run.versions().targetApplication().authorEmail().ifPresent(recipients::add);

        if (recipients.isEmpty())
            return;

        try {
            logger.log(INFO, "Sending failure notification to " + String.join(", ", recipients));
            mailOf(run, recipients).ifPresent(controller.serviceRegistry().mailer()::send);
        }
        catch (RuntimeException e) {
            logger.log(WARNING, "Exception trying to send mail for " + run.id(), e);
        }
    }

    private boolean isNewFailure(Run run) {
        return controller.jobController().lastCompleted(run.id().job())
                         .map(previous -> ! previous.hasFailed() || ! previous.versions().targetsMatch(run.versions()))
                         .orElse(true);
    }

    private void updateConsoleNotification(Run run) {
        NotificationSource source = NotificationSource.from(run.id());
        Consumer<String> updater = msg -> controller.notificationsDb().setNotification(source, Notification.Type.deployment, Notification.Level.error, msg);
        switch (run.status()) {
            case aborted: return; // wait and see how the next run goes.
            case running:
            case success:
                controller.notificationsDb().removeNotification(source, Notification.Type.deployment);
                return;
            case outOfCapacity:
                if ( ! run.id().type().environment().isTest()) updater.accept("lack of capacity. Please contact the Vespa team to request more!");
                return;
            case deploymentFailed:
                updater.accept("invalid application configuration, or timeout of other deployments of the same application");
                return;
            case installationFailed:
                updater.accept("nodes were not able to upgrade to the new configuration");
                return;
            case testFailure:
                updater.accept("one or more verification tests against the deployment failed");
                return;
            case error:
            case endpointCertificateTimeout:
                break;
            default:
                logger.log(WARNING, "Don't know what to set console notification to for run status '" + run.status() + "'");
        }
        updater.accept("something in the framework went wrong. Such errors are " +
                "usually transient. Please contact the Vespa team if the problem persists!");
    }

    private Optional<Mail> mailOf(Run run, List<String> recipients) {
        switch (run.status()) {
            case running:
            case aborted:
            case success:
                return Optional.empty();
            case outOfCapacity:
                return run.id().type().isProduction() ? Optional.of(mails.outOfCapacity(run.id(), recipients)) : Optional.empty();
            case deploymentFailed:
                return Optional.of(mails.deploymentFailure(run.id(), recipients));
            case installationFailed:
                return Optional.of(mails.installationFailure(run.id(), recipients));
            case testFailure:
                return Optional.of(mails.testFailure(run.id(), recipients));
            case error:
            case endpointCertificateTimeout:
                return Optional.of(mails.systemError(run.id(), recipients));
            default:
                logger.log(WARNING, "Don't know what mail to send for run status '" + run.status() + "'");
                return Optional.of(mails.systemError(run.id(), recipients));
        }
    }

    /** Returns the deployment of the real application in the zone of the given job, if it exists. */
    private Optional<Deployment> deployment(ApplicationId id, JobType type) {
        return Optional.ofNullable(application(id).deployments().get(type.zone(controller.system())));
    }

    /** Returns the real application with the given id. */
    private Instance application(ApplicationId id) {
        controller.applications().lockApplicationOrThrow(TenantAndApplicationId.from(id), __ -> { }); // Memory fence.
        return controller.applications().requireInstance(id);
    }

    /**
     * Returns whether the time since deployment is more than the zone deployment expiry, or the given timeout.
     *
     * We time out the job before the deployment expires, for zones where deployments are not persistent,
     * to be able to collect the Vespa log from the deployment. Thus, the lower of the zone's deployment expiry,
     * and the given default installation timeout, minus one minute, is used as a timeout threshold.
     */
    private boolean timedOut(RunId id, Deployment deployment, Duration defaultTimeout) {
        // TODO jonmv: This is a workaround for new deployment writes not yet being visible in spite of Curator locking.
        // TODO Investigate what's going on here, and remove this workaround.
        Run run = controller.jobController().run(id).get();
        if ( ! controller.system().isCd() && run.start().isAfter(deployment.at()))
            return false;

        Duration timeout = controller.zoneRegistry().getDeploymentTimeToLive(deployment.zone())
                                     .filter(zoneTimeout -> zoneTimeout.compareTo(defaultTimeout) < 0)
                                     .orElse(defaultTimeout);
        return deployment.at().isBefore(controller.clock().instant().minus(timeout.minus(Duration.ofMinutes(1))));
    }

    private boolean useTesterCertificate(RunId id) {
        return controller.system().isPublic() && id.type().environment().isTest();
    }

    /** Returns the application package for the tester application, assembled from a generated config, fat-jar and services.xml. */
    private ApplicationPackage testerPackage(RunId id) {
        ApplicationVersion version = controller.jobController().run(id).get().versions().targetApplication();
        DeploymentSpec spec = controller.applications().requireApplication(TenantAndApplicationId.from(id.application())).deploymentSpec();

        ZoneId zone = id.type().zone(controller.system());
        boolean useTesterCertificate = useTesterCertificate(id);

        byte[] servicesXml = servicesXml( ! controller.system().isPublic(),
                                         useTesterCertificate,
                                         testerResourcesFor(zone, spec.requireInstance(id.application().instance())),
                                         controller.controllerConfig().steprunner().testerapp());
        byte[] testPackage = controller.applications().applicationStore().getTester(id.application().tenant(), id.application().application(), version);
        byte[] deploymentXml = deploymentXml(id.tester(),
                                             spec.athenzDomain(),
                                             spec.requireInstance(id.application().instance()).athenzService(zone.environment(), zone.region()));

        try (ZipBuilder zipBuilder = new ZipBuilder(testPackage.length + servicesXml.length + deploymentXml.length + 1000)) {
            // Copy contents of submitted application-test.zip, and ensure required directories exist within the zip.
            zipBuilder.add(testPackage);
            zipBuilder.add("artifacts/.ignore-" + UUID.randomUUID(), new byte[0]);
            zipBuilder.add("tests/.ignore-" + UUID.randomUUID(), new byte[0]);

            zipBuilder.add("services.xml", servicesXml);
            zipBuilder.add("deployment.xml", deploymentXml);
            if (useTesterCertificate)
                appendAndStoreCertificate(zipBuilder, id);

            zipBuilder.close();
            return new ApplicationPackage(zipBuilder.toByteArray());
        }
    }

    private void appendAndStoreCertificate(ZipBuilder zipBuilder, RunId id) {
        KeyPair keyPair = KeyUtils.generateKeypair(KeyAlgorithm.RSA, 2048);
        X500Principal subject = new X500Principal("CN=" + id.tester().id().toFullString() + "." + id.type() + "." + id.number());
        X509Certificate certificate = X509CertificateBuilder.fromKeypair(keyPair,
                                                                         subject,
                                                                         controller.clock().instant(),
                                                                         controller.clock().instant().plus(timeouts.testerCertificate()),
                                                                         SignatureAlgorithm.SHA512_WITH_RSA,
                                                                         BigInteger.valueOf(1))
                                                            .build();
        controller.jobController().storeTesterCertificate(id, certificate);
        zipBuilder.add("artifacts/key", KeyUtils.toPem(keyPair.getPrivate()).getBytes(UTF_8));
        zipBuilder.add("artifacts/cert", X509CertificateUtils.toPem(certificate).getBytes(UTF_8));
    }

    private DeploymentId getTesterDeploymentId(RunId runId) {
        ZoneId zoneId = runId.type().zone(controller.system());
        return new DeploymentId(runId.tester().id(), zoneId);
    }

    static NodeResources testerResourcesFor(ZoneId zone, DeploymentInstanceSpec spec) {
        NodeResources nodeResources = spec.steps().stream()
                   .filter(step -> step.concerns(zone.environment()))
                   .findFirst()
                   .flatMap(step -> step.zones().get(0).testerFlavor())
                   .map(NodeResources::fromLegacyName)
                   .orElse(zone.region().value().contains("aws-") ?
                           DEFAULT_TESTER_RESOURCES_AWS : DEFAULT_TESTER_RESOURCES);
        return nodeResources.with(NodeResources.DiskSpeed.any);
    }

    /** Returns the generated services.xml content for the tester application. */
    static byte[] servicesXml(boolean systemUsesAthenz, boolean useTesterCertificate,
                              NodeResources resources, ControllerConfig.Steprunner.Testerapp config) {
        int jdiscMemoryGb = 2; // 2Gb memory for tester application (excessive?).
        int jdiscMemoryPct = (int) Math.ceil(100 * jdiscMemoryGb / resources.memoryGb());

        // Of the remaining memory, split 50/50 between Surefire running the tests and the rest
        int testMemoryMb = (int) (1024 * (resources.memoryGb() - jdiscMemoryGb) / 2);

        String resourceString = Text.format(
                                              "<resources vcpu=\"%.2f\" memory=\"%.2fGb\" disk=\"%.2fGb\" disk-speed=\"%s\" storage-type=\"%s\"/>",
                                              resources.vcpu(), resources.memoryGb(), resources.diskGb(), resources.diskSpeed().name(), resources.storageType().name());

        String runtimeProviderClass = config.runtimeProviderClass();
        String tenantCdBundle = config.tenantCdBundle();

        String servicesXml =
                "<?xml version='1.0' encoding='UTF-8'?>\n" +
                "<services xmlns:deploy='vespa' version='1.0'>\n" +
                "    <container version='1.0' id='tester'>\n" +
                "\n" +
                "        <component id=\"com.yahoo.vespa.hosted.testrunner.TestRunner\" bundle=\"vespa-testrunner-components\">\n" +
                "            <config name=\"com.yahoo.vespa.hosted.testrunner.test-runner\">\n" +
                "                <artifactsPath>artifacts</artifactsPath>\n" +
                "                <surefireMemoryMb>" + testMemoryMb + "</surefireMemoryMb>\n" +
                "                <useAthenzCredentials>" + systemUsesAthenz + "</useAthenzCredentials>\n" +
                "                <useTesterCertificate>" + useTesterCertificate + "</useTesterCertificate>\n" +
                "            </config>\n" +
                "        </component>\n" +
                "\n" +
                "        <handler id=\"com.yahoo.vespa.testrunner.TestRunnerHandler\" bundle=\"vespa-osgi-testrunner\">\n" +
                "            <binding>http://*/tester/v1/*</binding>\n" +
                "        </handler>\n" +
                "\n" +
                "        <component id=\"" + runtimeProviderClass + "\" bundle=\"" + tenantCdBundle + "\" />\n" +
                "\n" +
                "        <component id=\"com.yahoo.vespa.testrunner.JunitRunner\" bundle=\"vespa-osgi-testrunner\">\n" +
                "            <config name=\"com.yahoo.vespa.testrunner.junit-test-runner\">\n" +
                "                <artifactsPath>artifacts</artifactsPath>\n" +
                "                <useAthenzCredentials>" + systemUsesAthenz + "</useAthenzCredentials>\n" +
                "            </config>\n" +
                "        </component>\n" +
                "\n" +
                "        <component id=\"com.yahoo.vespa.testrunner.VespaCliTestRunner\" bundle=\"vespa-osgi-testrunner\">\n" +
                "            <config name=\"com.yahoo.vespa.testrunner.vespa-cli-test-runner\">\n" +
                "                <artifactsPath>artifacts</artifactsPath>\n" +
                "                <testsPath>tests</testsPath>\n" +
                "                <useAthenzCredentials>" + systemUsesAthenz + "</useAthenzCredentials>\n" +
                "            </config>\n" +
                "        </component>\n" +
                "\n" +
                "        <nodes count=\"1\" allocated-memory=\"" + jdiscMemoryPct + "%\">\n" +
                "            " + resourceString + "\n" +
                "        </nodes>\n" +
                "    </container>\n" +
                "</services>\n";

        return servicesXml.getBytes(UTF_8);
    }

    /** Returns a dummy deployment xml which sets up the service identity for the tester, if present. */
    private static byte[] deploymentXml(TesterId id, Optional<AthenzDomain> athenzDomain, Optional<AthenzService> athenzService) {
        String deploymentSpec =
                "<?xml version='1.0' encoding='UTF-8'?>\n" +
                "<deployment version=\"1.0\" " +
                athenzDomain.map(domain -> "athenz-domain=\"" + domain.value() + "\" ").orElse("") +
                athenzService.map(service -> "athenz-service=\"" + service.value() + "\" ").orElse("") + ">" +
                "  <instance id=\"" + id.id().instance().value() + "\" />" +
                "</deployment>";
        return deploymentSpec.getBytes(UTF_8);
    }

    /** Logger which logs to a {@link JobController}, as well as to the parent class' {@link Logger}. */
    private class DualLogger {

        private final RunId id;
        private final Step step;

        private DualLogger(RunId id, Step step) {
            this.id = id;
            this.step = step;
        }

        private void log(String... messages) {
            log(INFO, List.of(messages));
        }

        private void log(Level level, String... messages) {
            log(level, List.of(messages));
        }

        private void logAll(List<LogEntry> messages) {
            controller.jobController().log(id, step, messages);
        }

        private void log(List<String> messages) {
            log(INFO, messages);
        }

        private void log(Level level, List<String> messages) {
            controller.jobController().log(id, step, level, messages);
        }

        private void log(Level level, String message) {
            log(level, message, null);
        }

        // Print stack trace in our logs, but don't expose it to end users
        private void logWithInternalException(Level level, String message, Throwable thrown) {
            logger.log(level, id + " at " + step + ": " + message, thrown);
            controller.jobController().log(id, step, level, message);
        }

        private void log(Level level, String message, Throwable thrown) {
            logger.log(level, id + " at " + step + ": " + message, thrown);

            if (thrown != null) {
                ByteArrayOutputStream traceBuffer = new ByteArrayOutputStream();
                thrown.printStackTrace(new PrintStream(traceBuffer));
                message += "\n" + traceBuffer;
            }
            controller.jobController().log(id, step, level, message);
        }

    }


    static class Timeouts {

        private final SystemName system;

        private Timeouts(SystemName system) {
            this.system = requireNonNull(system);
        }

        public static Timeouts of(SystemName system) {
            return new Timeouts(system);
        }

        Duration capacity() { return Duration.ofMinutes(system.isCd() ? 15 : 0); }
        Duration endpoint() { return Duration.ofMinutes(15); }
        Duration endpointCertificate() { return Duration.ofMinutes(20); }
        Duration tester() { return Duration.ofMinutes(30); }
        Duration statelessNodesDown() { return Duration.ofMinutes(system.isCd() ? 30 : 60); }
        Duration statefulNodesDown() { return Duration.ofMinutes(system.isCd() ? 30 : 720); }
        Duration noNodesDown() { return Duration.ofMinutes(system.isCd() ? 30 : 240); }
        Duration testerCertificate() { return Duration.ofMinutes(300); }

    }

}
