/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.utils.kubeUtils.controllers;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatus;
import io.strimzi.systemtest.TestConstants;
import io.strimzi.systemtest.resources.ResourceOperation;
import io.strimzi.test.TestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;
import static java.util.Arrays.asList;

public class JobUtils {

    private static final Logger LOGGER = LogManager.getLogger(JobUtils.class);
    private static final long DELETION_TIMEOUT = ResourceOperation.getTimeoutForResourceDeletion();

    private JobUtils() { }

    /**
     * Wait until the Pod of Job with {@param jobName} contains specified {@param logMessage}
     * @param namespaceName name of Namespace where the Pod is running
     * @param jobName name of Job with which the Pod name obtained
     * @param logMessage desired log message
     */
    public static void waitForJobContainingLogMessage(String namespaceName, String jobName, String logMessage) {
        String jobPodName = kubeClient().listPodsByPrefixInName(namespaceName, jobName).get(0).getMetadata().getName();

        TestUtils.waitFor("Job contains log message: " + logMessage, TestConstants.GLOBAL_POLL_INTERVAL_LONG, TestConstants.GLOBAL_TIMEOUT,
            () -> kubeClient().logsInSpecificNamespace(namespaceName, jobPodName).contains(logMessage));
    }

    /**
     * Wait until all Jobs are deleted in given namespace.
     * @param namespace Delete all jobs in this namespace
     */
    public static void removeAllJobs(String namespace) {
        kubeClient().namespace(namespace).getJobList().getItems().forEach(
            job -> JobUtils.deleteJobWithWait(namespace, job.getMetadata().getName()));
    }

    /**
     * Wait until the given Job has been deleted.
     * @param name The name of the Job
     */
    public static void waitForJobDeletion(final String namespaceName, String name) {
        LOGGER.debug("Waiting for Job: {}/{} deletion", namespaceName, name);
        TestUtils.waitFor("deletion of Job: " + namespaceName + "/" + name, TestConstants.POLL_INTERVAL_FOR_RESOURCE_DELETION, DELETION_TIMEOUT,
            () -> kubeClient(namespaceName).listPodNamesInSpecificNamespace(namespaceName, "job-name", name).isEmpty());
        LOGGER.debug("Job: {}/{} was deleted", namespaceName, name);
    }

    /**
     * Delete Job and wait for it's deletion
     * @param name name of the job
     * @param namespace name of the Namespace
     */
    public static void deleteJobWithWait(String namespace, String name) {
        kubeClient(namespace).deleteJob(namespace, name);
        waitForJobDeletion(namespace, name);
    }

    /**
     * Delete Jobs with wait
     * @param namespace - name of the namespace
     * @param names - job names
     */
    public static void deleteJobsWithWait(String namespace, String... names) {
        for (String jobName : names) {
            deleteJobWithWait(namespace, jobName);
        }
    }

    /**
     * Wait for specific Job success
     * @param jobName job name
     * @param timeout timeout in ms after which we assume that job failed
     */
    public static void waitForJobSuccess(String namespaceName, String jobName, long timeout) {
        LOGGER.info("Waiting for Job: {}/{} to success", namespaceName, jobName);
        TestUtils.waitFor("success of Job: " + namespaceName + "/" + jobName, TestConstants.GLOBAL_POLL_INTERVAL, timeout,
                () -> kubeClient().checkSucceededJobStatus(namespaceName, jobName, 1));
    }

    /**
     * Wait for specific Job failure
     * @param jobName job name
     * @param timeout timeout in ms after which we assume that job failed
     */
    public static void waitForJobFailure(String namespaceName, String jobName, long timeout) {
        LOGGER.info("Waiting for Job: {}/{} to fail", namespaceName, jobName);
        TestUtils.waitFor("failure of Job: " + namespaceName + "/" + jobName, TestConstants.GLOBAL_POLL_INTERVAL, timeout,
            () -> kubeClient().checkFailedJobStatus(namespaceName, jobName, 1));
    }

    /**
     * Wait for specific Job Running active status
     *
     * @param namespaceName Namespace
     * @param jobName       Job name
     */
    public static boolean waitForJobRunning(String namespaceName, String jobName) {
        LOGGER.info("Waiting for Job: {}/{} to be in active state", namespaceName, jobName);
        TestUtils.waitFor("Job: " + namespaceName + "/" + jobName + " to be in active state", TestConstants.GLOBAL_POLL_INTERVAL, ResourceOperation.getTimeoutForResourceReadiness(TestConstants.JOB),
            () -> {
                JobStatus jb = kubeClient().namespace(namespaceName).getJobStatus(jobName);
                return jb.getActive() > 0;
            });

        return true;
    }

    /**
     * Log actual status of Job with pods.
     *
     * @param namespaceName - namespace/project where is job running
     * @param jobName       - name of the job, for which we should scrape status
     */
    public static void logCurrentJobStatus(String namespaceName, String jobName) {
        Job currentJob = kubeClient().getJob(namespaceName, jobName);

        if (currentJob != null && currentJob.getStatus() != null) {
            List<String> log = new ArrayList<>(asList(TestConstants.JOB, " status:\n"));

            List<JobCondition> conditions = currentJob.getStatus().getConditions();

            log.add("\tActive: " + currentJob.getStatus().getActive());
            log.add("\n\tFailed: " + currentJob.getStatus().getFailed());
            log.add("\n\tReady: " + currentJob.getStatus().getReady());
            log.add("\n\tSucceeded: " + currentJob.getStatus().getSucceeded());

            if (conditions != null) {
                List<String> conditionList = new ArrayList<>();

                for (JobCondition condition : conditions) {
                    if (condition.getMessage() != null) {
                        conditionList.add("\t\tType: " + condition.getType() + "\n");
                        conditionList.add("\t\tMessage: " + condition.getMessage() + "\n");
                    }
                }

                if (!conditionList.isEmpty()) {
                    log.add("\n\tConditions:\n");
                    log.addAll(conditionList);
                }
            }

            log.add("\n\nPods with conditions and messages:\n\n");

            for (Pod pod : kubeClient().namespace(currentJob.getMetadata().getNamespace()).listPodsByPrefixInName(jobName)) {
                log.add(pod.getMetadata().getName() + ":");
                List<String> podConditions = new ArrayList<>();

                for (PodCondition podCondition : pod.getStatus().getConditions()) {
                    if (podCondition.getMessage() != null) {
                        podConditions.add("\n\tType: " + podCondition.getType() + "\n");
                        podConditions.add("\tMessage: " + podCondition.getMessage() + "\n");
                    }
                }

                if (podConditions.isEmpty()) {
                    log.add("\n\t<EMPTY>");
                } else {
                    log.addAll(podConditions);
                }
                log.add("\n\n");
            }
            LOGGER.info("{}", String.join("", log).strip());
        }
    }
}
