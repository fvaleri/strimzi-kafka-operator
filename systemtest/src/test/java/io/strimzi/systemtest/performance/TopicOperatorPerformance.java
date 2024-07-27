/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.performance;

import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import io.strimzi.operator.common.Annotations;
import io.strimzi.systemtest.AbstractST;
import io.strimzi.systemtest.TestConstants;
import io.strimzi.systemtest.performance.utils.TopicOperatorPerformanceUtils;
import io.strimzi.systemtest.resources.ResourceManager;
import io.strimzi.systemtest.templates.crd.KafkaNodePoolTemplates;
import io.strimzi.systemtest.templates.crd.KafkaTemplates;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaTopicUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.strimzi.systemtest.TestConstants.PERFORMANCE;

@Tag(PERFORMANCE)
public class TopicOperatorPerformance extends AbstractST {
    private static final Logger LOGGER = LogManager.getLogger(TopicOperatorPerformance.class);
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    
    private final String namespace = clusterOperator.getDeploymentNamespace();
    private final String clusterName = "my-cluster-" + System.nanoTime();
    private final String topicNamePrefix = "my-topic-" + System.nanoTime();
    
    // topic event batches to test
    private final List<Integer> eventBatches = List.of(10, 100, 500, 1000);

    // Topic Operator configuration
    private final long reconciliationIntervalMs = 10_000;
    private final int maxBatchSize = 100;
    private final int maxBatchLingerMs = 10;
    private final int maxQueueSize = Integer.MAX_VALUE;
    
    @Test
    void runScalabilityTests() {
        eventBatches.forEach(numEvents -> {
            try {
                final int eventPerTask = 4;
                final int numberOfTasks = numEvents / eventPerTask;
                final int numSpareEvents = numEvents % eventPerTask;
                runWorkload(namespace, clusterName, topicNamePrefix, numberOfTasks, numSpareEvents);
            } finally {
                // safe net if something went wrong during test case and KafkaTopic is not properly deleted
                LOGGER.info("Cleaning namespace: {}", namespace);
                resourceManager.deleteResourcesOfTypeWithoutWait(KafkaTopic.RESOURCE_KIND);
                KafkaTopicUtils.waitForTopicWithPrefixDeletion(namespace, topicNamePrefix);
            }
        });
    }

    /**
     * Manages the lifecycle of KafkaTopic resources concurrently using a fixed size thread pool.
     * A number of tasks are handled simultaneously across the available CPU resources.
     * 
     * @param namespace          The namespace name.
     * @param clusterName        The cluster name.
     * @param topicNamePrefix    The topic name prefix.
     * @param numTasks           The number of event generation tasks to start.
     * @param numSpareEvents     The number of spare events to consume.
     */
    public void runWorkload(
        String namespace, String clusterName, String topicNamePrefix, int numTasks, int numSpareEvents
    ) {
        ExtensionContext currentContext = ResourceManager.getTestContext();
        CompletableFuture<Void>[] futures = new CompletableFuture[numTasks];
        AtomicInteger counter = new AtomicInteger(0);
        long t = System.nanoTime();

        // start tasks
        LOGGER.info("Running {} tasks", numTasks);
        for (int i = 0; i < numTasks; i++) {
            final String topicName = topicNamePrefix + "-" + i;
            futures[i] = CompletableFuture.runAsync(() ->
                runTask(namespace, clusterName, topicName, currentContext, counter), EXECUTOR);
        }

        // consume spare events
        LOGGER.info("Consuming {} spare events", numSpareEvents);
        for (int j = 0; j < numSpareEvents; j++) {
            futures[j] = CompletableFuture.completedFuture(null);
            counter.incrementAndGet();
        }

        // wait for all tasks to complete
        try {
            CompletableFuture.allOf(futures).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        long reconciliationTimeMs = (System.nanoTime() - t) / 1_000_000;
        LOGGER.info("Reconciled {} topic events in {} ms", counter.get(), reconciliationTimeMs);
    }

    /**
     * Runs 4 KafkaTopic operations as a single task suitable for parallel processing.
     * 
     * @param namespace          The namespace name.
     * @param clusterName        The cluster name.
     * @param topicName          The topic name.     
     * @param extensionContext   The current context of the test case.
     * @param counter            Reconciled events counter.
     */
    private void runTask(
        String namespace, String clusterName, String topicName, ExtensionContext extensionContext, AtomicInteger counter
    ) {
        TopicOperatorPerformanceUtils.createTopicWithWait(namespace, clusterName, topicName, extensionContext);
        counter.incrementAndGet();

        TopicOperatorPerformanceUtils.updateTopicWithWait(namespace, clusterName, topicName, extensionContext, 
            Map.of("compression.type", "gzip", "cleanup.policy", "delete", "min.insync.replicas", 2,
                "retention.ms", 3690L, "retention.bytes", 9876543L));
        counter.incrementAndGet();

        TopicOperatorPerformanceUtils.updateTopicWithWait(namespace, clusterName, topicName, extensionContext, 
            Map.of("segment.ms", 123456L,  "segment.bytes", 321654L, "flush.messages", 456123L));
        counter.incrementAndGet();
        
        TopicOperatorPerformanceUtils.deleteTopicWithWait(namespace, topicName, extensionContext);
        counter.incrementAndGet();
    }

    @BeforeAll
    void beforeAll() {
        this.clusterOperator = this.clusterOperator
            .defaultInstallation()
            .createInstallation()
            .runInstallation();
        
        resourceManager.createResourceWithWait(
            KafkaNodePoolTemplates.controllerPoolPersistentStorage(TestConstants.CO_NAMESPACE, "controller", clusterName, 3).build(),
            KafkaNodePoolTemplates.brokerPoolPersistentStorage(TestConstants.CO_NAMESPACE, "broker", clusterName, 3).build(),
            KafkaTemplates.kafkaPersistentKRaft(clusterName, 3)
                .editMetadata()
                    .addToAnnotations(Annotations.ANNO_STRIMZI_IO_NODE_POOLS, "enabled")
                    .addToAnnotations(Annotations.ANNO_STRIMZI_IO_KRAFT, "enabled")
                .endMetadata()
                .editSpec()
                    .editKafka()
                        .withResources(new ResourceRequirementsBuilder()
                            .addToLimits("memory", new Quantity("500Mi"))
                            .addToLimits("cpu", new Quantity("500m"))
                            .addToRequests("memory", new Quantity("500Mi"))
                            .addToRequests("cpu", new Quantity("500m"))
                            .build())
                    .endKafka()
                    .editEntityOperator()
                        .editTopicOperator()
                            .withReconciliationIntervalMs(reconciliationIntervalMs)
                            .withResources(new ResourceRequirementsBuilder()
                                .addToLimits("memory", new Quantity("500Mi"))
                                .addToLimits("cpu", new Quantity("500m"))
                                .addToRequests("memory", new Quantity("500Mi"))
                                .addToRequests("cpu", new Quantity("500m"))
                                .build())
                        .endTopicOperator()
                        .editOrNewTemplate()
                            .editOrNewTopicOperatorContainer()
                                .addNewEnv()
                                    .withName("STRIMZI_MAX_BATCH_SIZE")
                                    .withValue(String.valueOf(maxBatchSize))
                                .endEnv()
                                .addNewEnv()
                                    .withName("MAX_BATCH_LINGER_MS")
                                    .withValue(String.valueOf(maxBatchLingerMs))
                                .endEnv()
                                .addNewEnv()
                                    .withName("STRIMZI_MAX_QUEUE_SIZE")
                                    .withValue(String.valueOf(maxQueueSize))
                                .endEnv()
                            .endTopicOperatorContainer()
                        .endTemplate()
                    .endEntityOperator()
                .endSpec()
                .build()
        );
    }

    @AfterAll
    void afterAll() {
        try {
            EXECUTOR.shutdown();
            EXECUTOR.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            if (!EXECUTOR.isTerminated()) {
                EXECUTOR.shutdownNow();
            }
        }
    }
}
