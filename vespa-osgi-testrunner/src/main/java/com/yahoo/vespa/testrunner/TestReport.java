// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.testrunner;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.logging.LogRecord;

import static com.yahoo.vespa.testrunner.TestRunner.Status.FAILURE;
import static com.yahoo.vespa.testrunner.TestRunner.Status.INCONCLUSIVE;
import static com.yahoo.vespa.testrunner.TestRunner.Status.SUCCESS;

/**
 * @author mortent
 */
public class TestReport {

    final long totalCount;
    final long successCount;
    final long failedCount;
    final long inconclusiveCount;
    final long ignoredCount;
    final long abortedCount;
    final List<Failure> failures;
    final List<LogRecord> logLines;

    private TestReport(long totalCount, long successCount, long failedCount, long inconclusiveCount, long ignoredCount, long abortedCount, List<Failure> failures, List<LogRecord> logLines) {
        this.totalCount = totalCount;
        this.successCount = successCount;
        this.failedCount = failedCount;
        this.inconclusiveCount = inconclusiveCount;
        this.ignoredCount = ignoredCount;
        this.abortedCount = abortedCount;
        this.failures = failures;
        this.logLines = logLines;
    }

    public List<LogRecord> logLines() {
        return logLines;
    }

    public TestRunner.Status status() {
        return failedCount > 0 ? FAILURE : inconclusiveCount > 0 ? INCONCLUSIVE : SUCCESS;
    }

    public static Builder builder(){
        return new Builder();
    }


    public static class Builder {

        private long totalCount;
        private long successCount;
        private long failedCount;
        private long inconclusiveCount;
        private long ignoredCount;
        private long abortedCount;
        private List<Failure> failures = Collections.emptyList();
        private List<LogRecord> logLines = Collections.emptyList();

        public TestReport build() {
            return new TestReport(totalCount, successCount, failedCount, inconclusiveCount, ignoredCount, abortedCount, failures, logLines);
        }

        public Builder withTotalCount(long totalCount) {
            this.totalCount = totalCount;
            return this;
        }

        public Builder withSuccessCount(long successCount) {
            this.successCount = successCount;
            return this;
        }

        public Builder withFailedCount(long failedCount) {
            this.failedCount = failedCount;
            return this;
        }

        public Builder withInconclusiveCount(long failedCount) {
            this.failedCount = failedCount;
            return this;
        }

        public Builder withIgnoredCount(long ignoredCount) {
            this.ignoredCount = ignoredCount;
            return this;
        }

        public Builder withAbortedCount(long abortedCount) {
            this.abortedCount = abortedCount;
            return this;
        }

        public Builder withFailures(List<Failure> failures) {
            this.failures = List.copyOf(failures);
            return this;
        }

        public Builder withLogs(Collection<LogRecord> logRecords) {
            this.logLines = List.copyOf(logRecords);
            return this;
        }

    }


    public static class Failure {

        private final String testId;
        private final Throwable exception;

        public Failure(String testId, Throwable exception) {
            this.testId = testId;
            this.exception = exception;
        }

        public String testId() {
            return testId;
        }

        public Throwable exception() {
            return exception;
        }

    }

}
