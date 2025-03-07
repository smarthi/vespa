// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.deployment;

import com.google.common.collect.ImmutableMap;
import com.yahoo.component.Version;
import com.yahoo.config.application.api.DeploymentInstanceSpec;
import com.yahoo.config.application.api.DeploymentSpec;
import com.yahoo.config.application.api.DeploymentSpec.DeclaredTest;
import com.yahoo.config.application.api.DeploymentSpec.DeclaredZone;
import com.yahoo.config.application.api.DeploymentSpec.UpgradeRollout;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.InstanceName;
import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.vespa.hosted.controller.Application;
import com.yahoo.vespa.hosted.controller.Instance;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.ApplicationVersion;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.JobId;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType;
import com.yahoo.vespa.hosted.controller.application.Change;
import com.yahoo.vespa.hosted.controller.application.Deployment;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.yahoo.config.provision.Environment.prod;
import static com.yahoo.config.provision.Environment.staging;
import static com.yahoo.config.provision.Environment.test;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.stagingTest;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.systemTest;
import static java.util.Comparator.comparing;
import static java.util.Comparator.naturalOrder;
import static java.util.Objects.requireNonNull;
import static java.util.function.BinaryOperator.maxBy;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toUnmodifiableList;

/**
 * Status of the deployment jobs of an {@link Application}.
 *
 * @author jonmv
 */
public class DeploymentStatus {

    public static List<JobId> jobsFor(Application application, SystemName system) {
        if (DeploymentSpec.empty.equals(application.deploymentSpec()))
            return List.of();

        return application.deploymentSpec().instances().stream()
                          .flatMap(spec -> Stream.concat(Stream.of(systemTest, stagingTest),
                                                         flatten(spec).filter(step -> step.concerns(prod))
                                                                      .map(step -> {
                                                                          if (step instanceof DeclaredZone)
                                                                              return JobType.from(system, prod, ((DeclaredZone) step).region().get());
                                                                          return JobType.testFrom(system, ((DeclaredTest) step).region());
                                                                      })
                                                                      .flatMap(Optional::stream))
                                                 .map(type -> new JobId(application.id().instance(spec.name()), type)))
                          .collect(toUnmodifiableList());
    }

    private static Stream<DeploymentSpec.Step> flatten(DeploymentSpec.Step step) {
        return step instanceof DeploymentSpec.Steps ? step.steps().stream().flatMap(DeploymentStatus::flatten) : Stream.of(step);
    }

    private static <T> List<T> union(List<T> first, List<T> second) {
        return Stream.concat(first.stream(), second.stream()).distinct().collect(toUnmodifiableList());
    }

    private final Application application;
    private final JobList allJobs;
    private final SystemName system;
    private final Version systemVersion;
    private final Instant now;
    private final Map<JobId, StepStatus> jobSteps;
    private final List<StepStatus> allSteps;

    public DeploymentStatus(Application application, Map<JobId, JobStatus> allJobs, SystemName system,
                            Version systemVersion, Instant now) {
        this.application = requireNonNull(application);
        this.allJobs = JobList.from(allJobs.values());
        this.system = requireNonNull(system);
        this.systemVersion = requireNonNull(systemVersion);
        this.now = requireNonNull(now);
        List<StepStatus> allSteps = new ArrayList<>();
        this.jobSteps = jobDependencies(application.deploymentSpec(), allSteps);
        this.allSteps = Collections.unmodifiableList(allSteps);
    }

    /** The application this deployment status concerns. */
    public Application application() {
        return application;
    }

    /** A filterable list of the status of all jobs for this application. */
    public JobList jobs() {
        return allJobs;
    }

    /** Whether any jobs both dependent on the dependency, and a dependency for the dependent, are failing. */
    private boolean hasFailures(StepStatus dependency, StepStatus dependent) {
        Set<StepStatus> dependents = new HashSet<>();
        fillDependents(dependency, new HashSet<>(), dependents, dependent);
        Set<JobId> criticalJobs = dependents.stream().flatMap(step -> step.job().stream()).collect(Collectors.toSet());

        return ! allJobs.matching(job -> criticalJobs.contains(job.id()))
                        .failingHard()
                        .isEmpty();
    }

    private boolean fillDependents(StepStatus dependency, Set<StepStatus> visited, Set<StepStatus> dependents, StepStatus current) {
        if (visited.contains(current))
            return dependents.contains(current);

        if (dependency == current)
            dependents.add(current);
        else
            for (StepStatus dep : current.dependencies)
                if (fillDependents(dependency, visited, dependents, dep))
                    dependents.add(current);

        visited.add(current);
        return dependents.contains(current);
    }

    /** Whether any job is failing on anything older than version, with errors other than lack of capacity in a test zone.. */
    public boolean hasFailures(ApplicationVersion version) {
        return ! allJobs.failingHard()
                        .matching(job -> job.lastTriggered().get().versions().targetApplication().compareTo(version) < 0)
                        .isEmpty();
    }

    /** Whether any jobs of this application are failing with other errors than lack of capacity in a test zone. */
    public boolean hasFailures() {
        return ! allJobs.failingHard().isEmpty();
    }

    /** All job statuses, by job type, for the given instance. */
    public Map<JobType, JobStatus> instanceJobs(InstanceName instance) {
        return allJobs.asList().stream()
                      .filter(job -> job.id().application().equals(application.id().instance(instance)))
                      .collect(Collectors.toUnmodifiableMap(job -> job.id().type(),
                                                            Function.identity()));
    }

    /** Filterable job status lists for each instance of this application. */
    public Map<ApplicationId, JobList> instanceJobs() {
        return allJobs.groupingBy(job -> job.id().application());
    }

    /**
     * The set of jobs that need to run for the changes of each instance of the application to be considered complete,
     * and any test jobs for any outstanding change, which will likely be needed to later deploy this change.
     */
    public Map<JobId, List<Job>> jobsToRun() {
        Map<InstanceName, Change> changes = new LinkedHashMap<>();
        for (InstanceName instance : application.deploymentSpec().instanceNames())
            changes.put(instance, application.require(instance).change());
        Map<JobId, List<Job>> jobs = jobsToRun(changes);

        // Add test jobs for any outstanding change.
        for (InstanceName instance : application.deploymentSpec().instanceNames())
            changes.put(instance, outstandingChange(instance).onTopOf(application.require(instance).change()));
        var testJobs = jobsToRun(changes, true).entrySet().stream()
                                               .filter(entry -> ! entry.getKey().type().isProduction());

        return Stream.concat(jobs.entrySet().stream(), testJobs)
                     .collect(collectingAndThen(toMap(Map.Entry::getKey,
                                                      Map.Entry::getValue,
                                                      DeploymentStatus::union,
                                                      LinkedHashMap::new),
                                                Collections::unmodifiableMap));
    }

    private Map<JobId, List<Job>> jobsToRun(Map<InstanceName, Change> changes, boolean eagerTests) {
        Map<JobId, List<Job>> productionJobs = new LinkedHashMap<>();
        changes.forEach((instance, change) -> productionJobs.putAll(productionJobs(instance, change, eagerTests)));
        Map<JobId, List<Job>> testJobs = testJobs(productionJobs);
        Map<JobId, List<Job>> jobs = new LinkedHashMap<>(testJobs);
        jobs.putAll(productionJobs);
        // Add runs for idle, declared test jobs if they have no successes on their instance's change's versions.
        jobSteps.forEach((job, step) -> {
            if ( ! step.isDeclared() || jobs.containsKey(job))
                return;

            Change change = changes.get(job.application().instance());
            if (change == null || ! change.hasTargets())
                return;

            Optional<JobId> firstProductionJobWithDeployment = jobSteps.keySet().stream()
                                                                       .filter(jobId -> jobId.type().isProduction() && jobId.type().isDeployment())
                                                                       .filter(jobId -> deploymentFor(jobId).isPresent())
                                                                       .findFirst();

            Versions versions = Versions.from(change, application, firstProductionJobWithDeployment.flatMap(this::deploymentFor), systemVersion);
            if (step.completedAt(change, firstProductionJobWithDeployment).isEmpty())
                jobs.merge(job, List.of(new Job(versions, step.readyAt(change), change)), DeploymentStatus::union);
        });
        return Collections.unmodifiableMap(jobs);
    }

    /** The set of jobs that need to run for the given changes to be considered complete. */
    public Map<JobId, List<Job>> jobsToRun(Map<InstanceName, Change> changes) {
        return jobsToRun(changes, false);
    }

    /** The step status for all steps in the deployment spec of this, which are jobs, in the same order as in the deployment spec. */
    public Map<JobId, StepStatus> jobSteps() { return jobSteps; }

    public Map<InstanceName, StepStatus> instanceSteps() {
        ImmutableMap.Builder<InstanceName, StepStatus> instances = ImmutableMap.builder();
        for (StepStatus status : allSteps)
            if (status instanceof InstanceStatus)
                instances.put(status.instance(), status);
        return instances.build();
    }

    /** The step status for all relevant steps in the deployment spec of this, in the same order as in the deployment spec. */
    public List<StepStatus> allSteps() {
        if (allSteps.isEmpty())
            return List.of();

        List<JobId> firstTestJobs = List.of(firstDeclaredOrElseImplicitTest(systemTest),
                                            firstDeclaredOrElseImplicitTest(stagingTest));
        return allSteps.stream()
                       .filter(step -> step.isDeclared() || firstTestJobs.contains(step.job().orElseThrow()))
                       .collect(toUnmodifiableList());
    }

    public Optional<Deployment> deploymentFor(JobId job) {
        return Optional.ofNullable(application.require(job.application().instance())
                                              .deployments().get(job.type().zone(system)));
    }

    /**
     * The change of this application's latest submission, if this upgrades any of its production deployments,
     * and has not yet started rolling out, due to some other change or a block window being present at the time of submission.
     */
    public Change outstandingChange(InstanceName instance) {
        return nextVersion(instance).map(Change::of)
                          .filter(change -> application.require(instance).change().application().map(change::upgrades).orElse(true))
                          .filter(change -> ! jobsToRun(Map.of(instance, change)).isEmpty())
                          .orElse(Change.empty());
    }

    /** The next application version to roll out to instance. */
    private Optional<ApplicationVersion> nextVersion(InstanceName instance) {
        return Optional.ofNullable(instanceSteps().get(instance)).stream()
                       .flatMap(this::allDependencies)
                       .flatMap(step -> step.instance.latestDeployed().stream())
                       .min(naturalOrder())
                       .or(application::latestVersion);
    }

    private Stream<InstanceStatus> allDependencies(StepStatus step) {
        return step.dependencies.stream()
                                .flatMap(dep -> Stream.concat(Stream.of(dep), allDependencies(dep)))
                                .filter(InstanceStatus.class::isInstance)
                                .map(InstanceStatus.class::cast)
                                .distinct();
    }

    /** Earliest instant when job was triggered with given versions, or both system and staging tests were successful. */
    public Optional<Instant> verifiedAt(JobId job, Versions versions) {
        Optional<Instant> triggeredAt = allJobs.get(job)
                                               .flatMap(status -> status.runs().values().stream()
                                                                        .filter(run -> run.versions().equals(versions))
                                                                        .findFirst())
                                               .map(Run::start);
        Optional<Instant> systemTestedAt = testedAt(job.application(), systemTest, versions);
        Optional<Instant> stagingTestedAt = testedAt(job.application(), stagingTest, versions);
        if (systemTestedAt.isEmpty() || stagingTestedAt.isEmpty()) return triggeredAt;
        Optional<Instant> testedAt = systemTestedAt.get().isAfter(stagingTestedAt.get()) ? systemTestedAt : stagingTestedAt;
        return triggeredAt.isPresent() && triggeredAt.get().isBefore(testedAt.get()) ? triggeredAt : testedAt;
    }

    /** Earliest instant when versions were tested for the given instance */
    private Optional<Instant> testedAt(ApplicationId instance, JobType type, Versions versions) {
        return declaredTest(instance, type).map(__ -> allJobs.instance(instance.instance()))
                                           .orElse(allJobs)
                                           .type(type).asList().stream()
                                           .flatMap(status -> RunList.from(status)
                                                                        .on(versions)
                                                                        .status(RunStatus.success)
                                                                        .asList().stream()
                                                                        .map(Run::start))
                                           .min(naturalOrder());
    }

    private Map<JobId, List<Job>> productionJobs(InstanceName instance, Change change, boolean assumeUpgradesSucceed) {
        Map<JobId, List<Job>> jobs = new LinkedHashMap<>();
        jobSteps.forEach((job, step) -> {
            // When computing eager test jobs for outstanding changes, assume current upgrade completes successfully.
            Optional<Deployment> deployment = deploymentFor(job)
                    .map(existing -> assumeUpgradesSucceed ? withChange(existing, change.withoutApplication()) : existing);
            if (job.application().instance().equals(instance) && job.type().isProduction()) {

                List<Job> toRun = new ArrayList<>();
                List<Change> changes = changes(job, step, change);
                if (changes.isEmpty()) return;
                for (Change partial : changes) {
                    toRun.add(new Job(Versions.from(partial, application, deployment, systemVersion),
                                      step.readyAt(partial, Optional.of(job)),
                                      partial));
                    // Assume first partial change is applied before the second.
                    deployment = deployment.map(existing -> withChange(existing, partial));
                }
                jobs.put(job, toRun);
            }
        });
        return jobs;
    }

    private static Deployment withChange(Deployment deployment, Change change) {
        return new Deployment(deployment.zone(),
                              change.application().orElse(deployment.applicationVersion()),
                              change.platform().orElse(deployment.version()),
                              deployment.at(),
                              deployment.metrics(),
                              deployment.activity(),
                              deployment.quota(),
                              deployment.cost());
    }

    /** Changes to deploy with the given job, possibly split in two steps. */
    private List<Change> changes(JobId job, StepStatus step, Change change) {
        // Signal strict completion criterion by depending on job itself.
        if (step.completedAt(change, Optional.of(job)).isPresent())
            return List.of();

        if (change.platform().isEmpty() || change.application().isEmpty() || change.isPinned())
            return List.of(change);

        if (   step.completedAt(change.withoutApplication(), Optional.of(job)).isPresent()
            || step.completedAt(change.withoutPlatform(), Optional.of(job)).isPresent())
            return List.of(change);

        // For a dual change, where both target remain, we determine what to run by looking at when the two parts became ready:
        // for deployments, we look at dependencies; for tests, this may be overridden by what is already deployed.
        JobId deployment = new JobId(job.application(), JobType.from(system, job.type().zone(system)).get());
        UpgradeRollout rollout = application.deploymentSpec().requireInstance(job.application().instance()).upgradeRollout();
        if (job.type().isTest()) {
            Optional<Instant> platformDeployedAt = jobSteps.get(deployment).completedAt(change.withoutApplication(), Optional.of(deployment));
            Optional<Instant> revisionDeployedAt = jobSteps.get(deployment).completedAt(change.withoutPlatform(), Optional.of(deployment));

            // If only the revision has deployed, then we expect to test that first.
            if (platformDeployedAt.isEmpty() && revisionDeployedAt.isPresent()) return List.of(change.withoutPlatform(), change);

            // If only the upgrade has deployed, then we expect to test that first, with one exception:
            // The revision has caught up to the upgrade at the deployment job; and either
            // the upgrade is failing between deployment and here, or
            // the specified rollout is leading or simultaneous; and
            // the revision is now blocked by waiting for the production test to verify the upgrade.
            // In this case we must abandon the production test on the pure upgrade, so the revision can be deployed.
            if (platformDeployedAt.isPresent() && revisionDeployedAt.isEmpty()) {
                    if (jobSteps.get(deployment).readyAt(change, Optional.of(deployment))
                                      .map(ready -> ! now.isBefore(ready)).orElse(false)) {
                        switch (rollout) {
                            // If separate rollout, this test should keep blocking the revision, unless there are failures.
                            case separate: return hasFailures(jobSteps.get(deployment), jobSteps.get(job)) ? List.of(change) : List.of(change.withoutApplication(), change);
                            // If leading rollout, this test should now expect the two changes to fuse and roll together.
                            case leading: return List.of(change);
                            // If simultaneous rollout, this test should now expect the revision to run ahead.
                            case simultaneous: return List.of(change.withoutPlatform(), change);
                        }
                    }
                return List.of(change.withoutApplication(), change);
            }
            // If neither is deployed, then neither is ready, and we guess like for deployments.
            // If both are deployed, then we need to follow normal logic for whatever is ready.
        }

        Optional<Instant> platformReadyAt = step.dependenciesCompletedAt(change.withoutApplication(), Optional.of(job));
        Optional<Instant> revisionReadyAt = step.dependenciesCompletedAt(change.withoutPlatform(), Optional.of(job));

        // If neither change is ready, we guess based on the specified rollout.
        if (platformReadyAt.isEmpty() && revisionReadyAt.isEmpty()) {
            switch (rollout) {
                case separate: return List.of(change.withoutApplication(), change);  // Platform should stay ahead.
                case leading: return List.of(change);                                // They should eventually join.
                case simultaneous: return List.of(change.withoutPlatform(), change); // Revision should get ahead.
            }
        }

        // If only the revision is ready, we run that first.
        if (platformReadyAt.isEmpty()) return List.of(change.withoutPlatform(), change);

        // If only the platform is ready, we run that first.
        if (revisionReadyAt.isEmpty()) {
            return List.of(change.withoutApplication(), change);
        }

        // Both changes are ready for this step, and we look to the specified rollout to decide.
        boolean platformReadyFirst = platformReadyAt.get().isBefore(revisionReadyAt.get());
        boolean revisionReadyFirst = revisionReadyAt.get().isBefore(platformReadyAt.get());
        switch (rollout) {
            case separate:      // Let whichever change rolled out first, keep rolling first, unless upgrade alone is failing.
                return (platformReadyFirst || platformReadyAt.get().equals(Instant.EPOCH)) // Assume platform was first if no jobs have run yet.
                       ? step.job().flatMap(jobs()::get).flatMap(JobStatus::firstFailing).isPresent()
                         ? List.of(change)                                 // Platform was first, but is failing.
                         : List.of(change.withoutApplication(), change)    // Platform was first, and is OK.
                       : revisionReadyFirst
                         ? List.of(change.withoutPlatform(), change)       // Revision was first.
                         : List.of(change);                                // Both ready at the same time, probably due to earlier failure.
            case leading:      // When one change catches up, they fuse and continue together.
                return List.of(change);
            case simultaneous: // Revisions are allowed to run ahead, but the job where it caught up should have both changes.
                return platformReadyFirst ? List.of(change) : List.of(change.withoutPlatform(), change);
            default: throw new IllegalStateException("Unknown upgrade rollout policy");
        }
    }

    /** The test jobs that need to run prior to the given production deployment jobs. */
    public Map<JobId, List<Job>> testJobs(Map<JobId, List<Job>> jobs) {
        Map<JobId, List<Job>> testJobs = new LinkedHashMap<>();
        for (JobType testType : List.of(systemTest, stagingTest)) {
            jobs.forEach((job, versionsList) -> {
                if (job.type().isProduction() && job.type().isDeployment()) {
                    declaredTest(job.application(), testType).ifPresent(testJob -> {
                        for (Job productionJob : versionsList)
                            if (allJobs.successOn(productionJob.versions()).get(testJob).isEmpty())
                                testJobs.merge(testJob, List.of(new Job(productionJob.versions(),
                                                                        jobSteps().get(testJob).readyAt(productionJob.change),
                                                                        productionJob.change)),
                                               DeploymentStatus::union);
                    });
                }
            });
            jobs.forEach((job, versionsList) -> {
                for (Job productionJob : versionsList)
                    if (   job.type().isProduction() && job.type().isDeployment()
                        && allJobs.successOn(productionJob.versions()).type(testType).isEmpty()
                        && testJobs.keySet().stream()
                                   .noneMatch(test ->    test.type() == testType
                                                      && testJobs.get(test).stream().anyMatch(testJob -> testJob.versions().equals(productionJob.versions())))) {
                        JobId testJob = firstDeclaredOrElseImplicitTest(testType);
                        testJobs.merge(testJob,
                                       List.of(new Job(productionJob.versions(),
                                                       jobSteps.get(testJob).readyAt(productionJob.change),
                                                       productionJob.change)),
                                       DeploymentStatus::union);
                    }
            });
        }
        return Collections.unmodifiableMap(testJobs);
    }

    private JobId firstDeclaredOrElseImplicitTest(JobType testJob) {
        return application.deploymentSpec().instanceNames().stream()
                          .map(name -> new JobId(application.id().instance(name), testJob))
                          .min(comparing(id -> ! jobSteps.get(id).isDeclared())).orElseThrow();
    }

    /** JobId of any declared test of the given type, for the given instance. */
    private Optional<JobId> declaredTest(ApplicationId instanceId, JobType testJob) {
        JobId jobId = new JobId(instanceId, testJob);
        return jobSteps.get(jobId).isDeclared() ? Optional.of(jobId) : Optional.empty();
    }

    /** A DAG of the dependencies between the primitive steps in the spec, with iteration order equal to declaration order. */
    private Map<JobId, StepStatus> jobDependencies(DeploymentSpec spec, List<StepStatus> allSteps) {
        if (DeploymentSpec.empty.equals(spec))
            return Map.of();

        Map<JobId, StepStatus> dependencies = new LinkedHashMap<>();
        List<StepStatus> previous = List.of();
        for (DeploymentSpec.Step step : spec.steps())
            previous = fillStep(dependencies, allSteps, step, previous, null);

        return Collections.unmodifiableMap(dependencies);
    }

    /** Adds the primitive steps contained in the given step, which depend on the given previous primitives, to the dependency graph. */
    private List<StepStatus> fillStep(Map<JobId, StepStatus> dependencies, List<StepStatus> allSteps,
                                      DeploymentSpec.Step step, List<StepStatus> previous, InstanceName instance) {
        if (step.steps().isEmpty() && ! (step instanceof DeploymentInstanceSpec)) {
            if (instance == null)
                return previous; // Ignore test and staging outside all instances.

            if ( ! step.delay().isZero()) {
                StepStatus stepStatus = new DelayStatus((DeploymentSpec.Delay) step, previous, instance);
                allSteps.add(stepStatus);
                return List.of(stepStatus);
            }

            JobType jobType;
            StepStatus stepStatus;
            if (step.concerns(test) || step.concerns(staging)) {
                jobType = JobType.from(system, ((DeclaredZone) step).environment(), null)
                                 .orElseThrow(() -> new IllegalStateException(application + " specifies " + step + ", but this has no job in " + system));
                stepStatus = JobStepStatus.ofTestDeployment((DeclaredZone) step, List.of(), this, instance, jobType, true);
                previous = new ArrayList<>(previous);
                previous.add(stepStatus);
            }
            else if (step.isTest()) {
                jobType = JobType.testFrom(system, ((DeclaredTest) step).region())
                                 .orElseThrow(() -> new IllegalStateException(application + " specifies " + step + ", but this has no job in " + system));
                JobType preType = JobType.from(system, prod, ((DeclaredTest) step).region())
                                         .orElseThrow(() -> new IllegalStateException(application + " specifies " + step + ", but this has no job in " + system));
                stepStatus = JobStepStatus.ofProductionTest((DeclaredTest) step, previous, this, instance, jobType, preType);
                previous = List.of(stepStatus);
            }
            else if (step.concerns(prod)) {
                jobType = JobType.from(system, ((DeclaredZone) step).environment(), ((DeclaredZone) step).region().get())
                                 .orElseThrow(() -> new IllegalStateException(application + " specifies " + step + ", but this has no job in " + system));
                stepStatus = JobStepStatus.ofProductionDeployment((DeclaredZone) step, previous, this, instance, jobType);
                previous = List.of(stepStatus);
            }
            else return previous; // Empty container steps end up here, and are simply ignored.
            JobId jobId = new JobId(application.id().instance(instance), jobType);
            allSteps.removeIf(existing -> existing.job().equals(Optional.of(jobId))); // Replace implicit tests with explicit ones.
            allSteps.add(stepStatus);
            dependencies.put(jobId, stepStatus);
            return previous;
        }

        if (step instanceof DeploymentInstanceSpec) {
            DeploymentInstanceSpec spec = ((DeploymentInstanceSpec) step);
            StepStatus instanceStatus = new InstanceStatus(spec, previous, now, application.require(spec.name()));
            instance = spec.name();
            allSteps.add(instanceStatus);
            previous = List.of(instanceStatus);
            for (JobType test : List.of(systemTest, stagingTest)) {
                JobId job = new JobId(application.id().instance(instance), test);
                if ( ! dependencies.containsKey(job)) {
                    var testStatus = JobStepStatus.ofTestDeployment(new DeclaredZone(test.environment()), List.of(),
                                                                    this, job.application().instance(), test, false);
                    dependencies.put(job, testStatus);
                    allSteps.add(testStatus);
                }
            }
        }

        if (step.isOrdered()) {
            for (DeploymentSpec.Step nested : step.steps())
                previous = fillStep(dependencies, allSteps, nested, previous, instance);

            return previous;
        }

        List<StepStatus> parallel = new ArrayList<>();
        for (DeploymentSpec.Step nested : step.steps())
            parallel.addAll(fillStep(dependencies, allSteps, nested, previous, instance));

        return List.copyOf(parallel);
    }


    public enum StepType {

        /** An instance — completion marks a change as ready for the jobs contained in it. */
        instance,

        /** A timed delay. */
        delay,

        /** A system, staging or production test. */
        test,

        /** A production deployment. */
        deployment,
    }

    /**
     * Used to represent all steps — explicit and implicit — that may run in order to complete deployment of a change.
     *
     * Each node contains a step describing the node,
     * a list of steps which need to be complete before the step may start,
     * a list of jobs from which completion of the step is computed, and
     * optionally, an instance name used to identify a job type for the step,
     *
     * The completion criterion for each type of step is implemented in subclasses of this.
     */
    public static abstract class StepStatus {

        private final StepType type;
        private final DeploymentSpec.Step step;
        private final List<StepStatus> dependencies; // All direct dependencies of this step.
        private final InstanceName instance;

        private StepStatus(StepType type, DeploymentSpec.Step step, List<StepStatus> dependencies, InstanceName instance) {
            this.type = requireNonNull(type);
            this.step = requireNonNull(step);
            this.dependencies = List.copyOf(dependencies);
            this.instance = instance;
        }

        /** The type of step this is. */
        public final StepType type() { return type; }

        /** The step defining this. */
        public final DeploymentSpec.Step step() { return step; }

        /** The list of steps that need to be complete before this may start. */
        public final List<StepStatus> dependencies() { return dependencies; }

        /** The instance of this. */
        public final InstanceName instance() { return instance; }

        /** The id of the job this corresponds to, if any. */
        public Optional<JobId> job() { return Optional.empty(); }

        /** The time at which this is, or was, complete on the given change and / or versions. */
        public Optional<Instant> completedAt(Change change) { return completedAt(change, Optional.empty()); }

        /** The time at which this is, or was, complete on the given change and / or versions. */
        abstract Optional<Instant> completedAt(Change change, Optional<JobId> dependent);

        /** The time at which this step is ready to run the specified change and / or versions. */
        public Optional<Instant> readyAt(Change change) { return readyAt(change, Optional.empty()); }

        /** The time at which this step is ready to run the specified change and / or versions. */
        Optional<Instant> readyAt(Change change, Optional<JobId> dependent) {
            return dependenciesCompletedAt(change, dependent)
                    .map(ready -> Stream.of(blockedUntil(change),
                                            pausedUntil(),
                                            coolingDownUntil(change))
                                        .flatMap(Optional::stream)
                                        .reduce(ready, maxBy(naturalOrder())));
        }

        /** The time at which all dependencies completed on the given change and / or versions. */
        Optional<Instant> dependenciesCompletedAt(Change change, Optional<JobId> dependent) {
            Instant latest = Instant.EPOCH;
            for (StepStatus step : dependencies) {
                Optional<Instant> completedAt = step.completedAt(change, dependent);
                if (completedAt.isEmpty()) return Optional.empty();
                latest = latest.isBefore(completedAt.get()) ? completedAt.get() : latest;
            }
            return Optional.of(latest);
        }

        /** The time until which this step is blocked by a change blocker. */
        public Optional<Instant> blockedUntil(Change change) { return Optional.empty(); }

        /** The time until which this step is paused by user intervention. */
        public Optional<Instant> pausedUntil() { return Optional.empty(); }

        /** The time until which this step is cooling down, due to consecutive failures. */
        public Optional<Instant> coolingDownUntil(Change change) { return Optional.empty(); }

        /** Whether this step is declared in the deployment spec, or is an implicit step. */
        public boolean isDeclared() { return true; }

    }


    private static class DelayStatus extends StepStatus {

        private DelayStatus(DeploymentSpec.Delay step, List<StepStatus> dependencies, InstanceName instance) {
            super(StepType.delay, step, dependencies, instance);
        }

        @Override
        Optional<Instant> completedAt(Change change, Optional<JobId> dependent) {
            return readyAt(change, dependent).map(completion -> completion.plus(step().delay()));
        }

    }


    private static class InstanceStatus extends StepStatus {

        private final DeploymentInstanceSpec spec;
        private final Instant now;
        private final Instance instance;

        private InstanceStatus(DeploymentInstanceSpec spec, List<StepStatus> dependencies, Instant now,
                               Instance instance) {
            super(StepType.instance, spec, dependencies, spec.name());
            this.spec = spec;
            this.now = now;
            this.instance = instance;
        }

        /**
         * Time of completion of its dependencies, if all parts of the given change are contained in the change
         * for this instance, or if no more jobs should run for this instance for the given change.
         */
        @Override
        Optional<Instant> completedAt(Change change, Optional<JobId> dependent) {
            return    (   (change.platform().isEmpty() || change.platform().equals(instance.change().platform()))
                       && (change.application().isEmpty() || change.application().equals(instance.change().application()))
                   || step().steps().stream().noneMatch(step -> step.concerns(prod)))
                      ? dependenciesCompletedAt(change, dependent)
                      : Optional.empty();
        }

        @Override
        public Optional<Instant> blockedUntil(Change change) {
            for (Instant current = now; now.plus(Duration.ofDays(7)).isAfter(current); ) {
                boolean blocked = false;
                for (DeploymentSpec.ChangeBlocker blocker : spec.changeBlocker()) {
                    while (   blocker.window().includes(current)
                           && now.plus(Duration.ofDays(7)).isAfter(current)
                           && (   change.platform().isPresent() && blocker.blocksVersions()
                               || change.application().isPresent() && blocker.blocksRevisions())) {
                        blocked = true;
                        current = current.plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.HOURS);
                    }
                }
                if ( ! blocked)
                    return current == now ? Optional.empty() : Optional.of(current);
            }
            return Optional.of(now.plusSeconds(1 << 30)); // Some time in the future that doesn't look like anything you'd expect.
        }

    }


    private static abstract class JobStepStatus extends StepStatus {

        private final JobStatus job;
        private final DeploymentStatus status;

        private JobStepStatus(StepType type, DeploymentSpec.Step step, List<StepStatus> dependencies, JobStatus job,
                                DeploymentStatus status) {
            super(type, step, dependencies, job.id().application().instance());
            this.job = requireNonNull(job);
            this.status = requireNonNull(status);
        }

        @Override
        public Optional<JobId> job() { return Optional.of(job.id()); }

        @Override
        public Optional<Instant> pausedUntil() {
            return status.application().require(job.id().application().instance()).jobPause(job.id().type());
        }

        @Override
        public Optional<Instant> coolingDownUntil(Change change) {
            if (job.lastTriggered().isEmpty()) return Optional.empty();
            if (job.lastCompleted().isEmpty()) return Optional.empty();
            if (job.firstFailing().isEmpty() || ! job.firstFailing().get().hasEnded()) return Optional.empty();
            Versions lastVersions = job.lastCompleted().get().versions();
            if (change.platform().isPresent() && ! change.platform().get().equals(lastVersions.targetPlatform())) return Optional.empty();
            if (change.application().isPresent() && ! change.application().get().equals(lastVersions.targetApplication())) return Optional.empty();
            if (job.id().type().environment().isTest() && job.isOutOfCapacity()) return Optional.empty();

            Instant firstFailing = job.firstFailing().get().end().get();
            Instant lastCompleted = job.lastCompleted().get().end().get();

            return firstFailing.equals(lastCompleted) ? Optional.of(lastCompleted)
                                                      : Optional.of(lastCompleted.plus(Duration.ofMinutes(10))
                                                                                 .plus(Duration.between(firstFailing, lastCompleted)
                                                                                               .dividedBy(2)))
                    .filter(status.now::isBefore);
        }

        private static JobStepStatus ofProductionDeployment(DeclaredZone step, List<StepStatus> dependencies,
                                                            DeploymentStatus status, InstanceName instance, JobType jobType) {
            ZoneId zone = ZoneId.from(step.environment(), step.region().get());
            JobStatus job = status.instanceJobs(instance).get(jobType);
            Optional<Deployment> existingDeployment = Optional.ofNullable(status.application().require(instance)
                                                                                .deployments().get(zone));

            return new JobStepStatus(StepType.deployment, step, dependencies, job, status) {

                @Override
                public Optional<Instant> readyAt(Change change, Optional<JobId> dependent) {
                    Optional<Instant> readyAt = super.readyAt(change, dependent);
                    Optional<Instant> testedAt = status.verifiedAt(job.id(), Versions.from(change, status.application, existingDeployment, status.systemVersion));
                    if (readyAt.isEmpty() || testedAt.isEmpty()) return Optional.empty();
                    return readyAt.get().isAfter(testedAt.get()) ? readyAt : testedAt;
                }

                /** Complete if deployment is on pinned version, and last successful deployment, or if given versions is strictly a downgrade, and this isn't forced by a pin. */
                @Override
                Optional<Instant> completedAt(Change change, Optional<JobId> dependent) {
                    if (     change.isPinned()
                        &&   change.platform().isPresent()
                        && ! existingDeployment.map(Deployment::version).equals(change.platform()))
                        return Optional.empty();

                    if (     change.application().isPresent()
                        && ! existingDeployment.map(Deployment::applicationVersion).equals(change.application())
                        &&   dependent.equals(job())) // Job should (re-)run in this case, but other dependents need not wait.
                        return Optional.empty();

                    Change fullChange = status.application().require(instance).change();
                    if (existingDeployment.map(deployment ->    ! (change.upgrades(deployment.version()) || change.upgrades(deployment.applicationVersion()))
                                                             &&   (fullChange.downgrades(deployment.version()) || fullChange.downgrades(deployment.applicationVersion())))
                                          .orElse(false))
                        return job.lastCompleted().flatMap(Run::end);

                    return (dependent.equals(job()) ? job.lastSuccess().stream()
                                                    : RunList.from(job).status(RunStatus.success).asList().stream())
                            .filter(run ->    change.platform().map(run.versions().targetPlatform()::equals).orElse(true)
                                           && change.application().map(run.versions().targetApplication()::equals).orElse(true))
                            .map(Run::end)
                            .flatMap(Optional::stream)
                            .min(naturalOrder());
                }
            };
        }

        private static JobStepStatus ofProductionTest(DeclaredTest step, List<StepStatus> dependencies,
                                                      DeploymentStatus status, InstanceName instance, JobType testType, JobType prodType) {
            JobStatus job = status.instanceJobs(instance).get(testType);
            return new JobStepStatus(StepType.test, step, dependencies, job, status) {
                @Override
                Optional<Instant> completedAt(Change change, Optional<JobId> dependent) {
                    Versions versions = Versions.from(change, status.application, status.deploymentFor(job.id()), status.systemVersion);
                    return dependent.equals(job()) ? job.lastSuccess()
                                                        .filter(run -> versions.targetsMatch(run.versions()))
                                                        .filter(run -> ! status.jobs()
                                                                               .instance(instance)
                                                                               .type(prodType)
                                                                               .lastCompleted().endedNoLaterThan(run.start())
                                                                               .isEmpty())
                                                        .map(run -> run.end().get())
                                                   : RunList.from(job)
                                                            .matching(run -> versions.targetsMatch(run.versions()))
                                                            .status(RunStatus.success)
                                                            .first()
                                                            .map(run -> run.end().get());
                }
            };
        }

        private static JobStepStatus ofTestDeployment(DeclaredZone step, List<StepStatus> dependencies,
                                                      DeploymentStatus status, InstanceName instance,
                                                      JobType jobType, boolean declared) {
            JobStatus job = status.instanceJobs(instance).get(jobType);
            return new JobStepStatus(StepType.test, step, dependencies, job, status) {
                @Override
                Optional<Instant> completedAt(Change change, Optional<JobId> dependent) {
                    return RunList.from(job)
                                  .matching(run -> run.versions().targetsMatch(Versions.from(change,
                                                                                             status.application,
                                                                                             dependent.flatMap(status::deploymentFor),
                                                                                             status.systemVersion)))
                                  .status(RunStatus.success)
                                  .asList().stream()
                                  .map(run -> run.end().get())
                                  .max(naturalOrder());
                }

                @Override
                public boolean isDeclared() { return declared; }
            };
        }

    }

    public static class Job {

        private final Versions versions;
        private final Optional<Instant> readyAt;
        private final Change change;

        public Job(Versions versions, Optional<Instant> readyAt, Change change) {
            this.versions = versions;
            this.readyAt = readyAt;
            this.change = change;
        }

        public Versions versions() {
            return versions;
        }

        public Optional<Instant> readyAt() {
            return readyAt;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Job job = (Job) o;
            return versions.equals(job.versions) && readyAt.equals(job.readyAt) && change.equals(job.change);
        }

        @Override
        public int hashCode() {
            return Objects.hash(versions, readyAt, change);
        }

    }

}
