/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.performance.utils;

import io.strimzi.api.kafka.model.topic.KafkaTopic;
import io.strimzi.api.kafka.model.topic.KafkaTopicSpec;
import io.strimzi.api.kafka.model.topic.KafkaTopicSpecBuilder;
import io.strimzi.systemtest.TestConstants;
import io.strimzi.systemtest.enums.ConditionStatus;
import io.strimzi.systemtest.enums.CustomResourceStatus;
import io.strimzi.systemtest.resources.ResourceManager;
import io.strimzi.systemtest.resources.crd.KafkaTopicResource;
import io.strimzi.systemtest.templates.crd.KafkaTopicTemplates;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaTopicUtils;
import io.strimzi.test.TestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.Map;

import static io.strimzi.test.k8s.KubeClusterResource.cmdKubeClient;

/**
 * Utility class for KafkaTopic operations in performance tests.
 * The log level is set to DEBUG in order to avoid spamming the log.
 */
public class TopicOperatorPerformanceUtils {
    private static final Logger LOGGER = LogManager.getLogger(TopicOperatorPerformanceUtils.class);
    
    private TopicOperatorPerformanceUtils() { }

    /**
     * Creates a KafkaTopic and waits until their status is ready.
     * 
     * <p>Note: The {@code ResourceManager.setTestContext(currentContext);} is needed because this method is invoked in a new thread.
     * Therefore, if you do not set the context, you would end up with a NullPointerException (NPE) because a new thread does not hold
     * the state of the {@code ExtensionContext}, and so you need to set it.</p>
     *
     * @param namespace          The namespace name.  
     * @param clusterName        The cluster name.    
     * @param topicName          The topic name.      
     * @param currentContext     The current context of the test case.
     */
    public static void createTopicWithWait(String namespace, String clusterName, String topicName, ExtensionContext currentContext) {
        ResourceManager.setTestContext(currentContext);
        createTopic(namespace, clusterName, topicName, 12, 3, 2);
        waitForTopicStatus(namespace, topicName, CustomResourceStatus.Ready, ConditionStatus.True);
    }

    /**
     * Updates a KafkaTopic and waits until the configuration is updated.
     * 
     * <p>Note: The {@code ResourceManager.setTestContext(currentContext);} is needed because this method is invoked in a new thread.
     * Therefore, if you do not set the context, you would end up with a NullPointerException (NPE) because a new thread does not hold
     * the state of the {@code ExtensionContext}, and so you need to set it.</p>
     *
     * @param namespace          The namespace name
     * @param clusterName        The cluster name. 
     * @param topicName          The topic name.   
     * @param currentContext     The current context of the test case.
     * @param configToUpdate     Configuration to update in the Kafka topics.
     */
    public static void updateTopicWithWait(String namespace, String clusterName, String topicName, ExtensionContext currentContext, Map<String, Object> configToUpdate) {
        ResourceManager.setTestContext(currentContext);
        updateTopic(namespace, topicName, new KafkaTopicSpecBuilder().withConfig(configToUpdate).build());
        waitForTopicConfigContains(namespace, topicName, configToUpdate);
    }

    /**
     * Deletes Kafka topics within a specified range and waits until they are fully deleted.
     * 
     * <p>Note: The {@code ResourceManager.setTestContext(currentContext);} is needed because this method is invoked in a new thread.
     * Therefore, if you do not set the context, you would end up with a NullPointerException (NPE) because a new thread does not hold
     * the state of the {@code ExtensionContext}, and so you need to set it.</p>
     *
     * @param namespace          The namespace name.
     * @param topicName          The topic name.     
     * @param currentContext     The current context of the test case.
     */
    public static void deleteTopicWithWait(String namespace, String topicName, ExtensionContext currentContext) {
        ResourceManager.setTestContext(currentContext);
        deleteKafkaTopic(namespace, topicName);
        waitForKafkaTopicDeletion(namespace, topicName);
    }

    private static void createTopic(String namespace, String clusterName, String topicName, int numberOfPartitions, int numberOfReplicas, int minInSyncReplicas) {
        LOGGER.debug("Creating KafkaTopic {}/{}", namespace, topicName);
        ResourceManager.getInstance().createResourceWithoutWait(KafkaTopicTemplates.topic(
            clusterName, topicName, numberOfPartitions, numberOfReplicas, minInSyncReplicas, namespace).build());
    }

    private static void waitForTopicStatus(String namespace, String topicName, Enum<?> conditionType, ConditionStatus conditionStatus) {
        LOGGER.debug("Waiting for KafkaTopic {}/{} to be {}", namespace, topicName, conditionType.toString());
        KafkaTopicUtils.waitForKafkaTopicStatus(namespace, topicName, conditionType, conditionStatus);
    }

    private static void updateTopic(String namespace, String topicName, KafkaTopicSpec topicSpec) {
        LOGGER.debug("Updating KafkaTopic {}/{}", namespace, topicName);
        KafkaTopicResource.replaceTopicResourceInSpecificNamespace(topicName, kafkaTopic -> kafkaTopic.setSpec(topicSpec), namespace);
    }

    public static void waitForTopicConfigContains(String namespace, String topicName, Map<String, Object> config) {
        LOGGER.debug("Waiting for KafkaTopic: {}/{} to contain correct config", namespace, topicName);
        TestUtils.waitFor("KafkaTopic: " + namespace + "/" + topicName + " to contain correct config",
            TestConstants.GLOBAL_POLL_INTERVAL, TestConstants.GLOBAL_STATUS_TIMEOUT,
            () -> KafkaTopicUtils.configsAreEqual(KafkaTopicResource.kafkaTopicClient()
                .inNamespace(namespace).withName(topicName).get().getSpec().getConfig(), config)
        );
        LOGGER.debug("KafkaTopic: {}/{} contains correct config", namespace, topicName);
    }

    private static void deleteKafkaTopic(String namespace, String topicName) {
        LOGGER.debug("Deleting topic {}", topicName);
        ResourceManager.cmdKubeClient().namespace(namespace).deleteByName(KafkaTopic.RESOURCE_SINGULAR, topicName);
    }

    public static void waitForKafkaTopicDeletion(String namespaceName, String topicName) {
        LOGGER.debug("Waiting for KafkaTopic: {}/{} deletion", namespaceName, topicName);
        TestUtils.waitFor("deletion of KafkaTopic: " + namespaceName + "/" + topicName, 
                TestConstants.POLL_INTERVAL_FOR_RESOURCE_READINESS, 
                TestConstants.GLOBAL_TIMEOUT_SHORT,
            () -> {
                if (KafkaTopicResource.kafkaTopicClient().inNamespace(namespaceName).withName(topicName).get() == null) {
                    return true;
                } else {
                    LOGGER.warn("KafkaTopic: {}/{} is not deleted yet! Triggering force delete by cmd client!", namespaceName, topicName);
                    cmdKubeClient(namespaceName).deleteByName(KafkaTopic.RESOURCE_KIND, topicName);
                    return false;
                }
            },
            () -> LOGGER.info(KafkaTopicResource.kafkaTopicClient().inNamespace(namespaceName).withName(topicName).get())
        );
    }
}
