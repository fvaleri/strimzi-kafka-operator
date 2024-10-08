/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.templates.crd;

import io.strimzi.api.kafka.model.user.KafkaUser;
import io.strimzi.api.kafka.model.user.KafkaUserBuilder;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.systemtest.storage.TestStorage;

public class KafkaUserTemplates {

    private KafkaUserTemplates() {}

    public static KafkaUserBuilder tlsUser(TestStorage testStorage) {
        return tlsUser(testStorage.getNamespaceName(), testStorage.getUsername(), testStorage.getClusterName());
    }

    public static KafkaUserBuilder tlsUser(String namespaceName, String userName, String kafkaClusterName) {
        return defaultUser(namespaceName, userName, kafkaClusterName)
            .withNewSpec()
                .withNewKafkaUserTlsClientAuthentication()
                .endKafkaUserTlsClientAuthentication()
            .endSpec();
    }

    public static KafkaUserBuilder scramShaUser(TestStorage testStorage) {
        return scramShaUser(testStorage.getNamespaceName(), testStorage.getUsername(), testStorage.getClusterName());
    }

    public static KafkaUserBuilder scramShaUser(String namespaceName, String userName, String kafkaClusterName) {
        return defaultUser(namespaceName, userName, kafkaClusterName)
            .withNewSpec()
                .withNewKafkaUserScramSha512ClientAuthentication()
                .endKafkaUserScramSha512ClientAuthentication()
            .endSpec();
    }

    public static KafkaUserBuilder tlsExternalUser(final String namespaceName, final String userName, final String kafkaClusterName) {
        return defaultUser(namespaceName, userName, kafkaClusterName)
            .withNewSpec()
                .withNewKafkaUserTlsExternalClientAuthentication()
                .endKafkaUserTlsExternalClientAuthentication()
            .endSpec();
    }

    public static KafkaUserBuilder defaultUser(String namespaceName, String userName, String kafkaClusterName) {
        return new KafkaUserBuilder()
            .withNewMetadata()
                .withName(userName)
                .withNamespace(namespaceName)
                .addToLabels(Labels.STRIMZI_CLUSTER_LABEL, kafkaClusterName)
            .endMetadata();
    }

    public static KafkaUserBuilder userWithQuotas(KafkaUser user, Integer prodRate, Integer consRate, Integer requestPerc, Double mutRate) {
        return new KafkaUserBuilder(user)
                .editSpec()
                    .withNewQuotas()
                        .withConsumerByteRate(consRate)
                        .withProducerByteRate(prodRate)
                        .withRequestPercentage(requestPerc)
                        .withControllerMutationRate(mutRate)
                    .endQuotas()
                .endSpec();
    }
}
