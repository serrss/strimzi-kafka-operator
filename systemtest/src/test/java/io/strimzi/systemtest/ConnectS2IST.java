/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest;

import io.strimzi.systemtest.annotations.OpenShiftOnly;
import io.strimzi.systemtest.utils.StUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static io.strimzi.systemtest.Constants.ACCEPTANCE;
import static io.strimzi.systemtest.Constants.REGRESSION;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

@Tag(REGRESSION)
class ConnectS2IST extends AbstractST {

    public static final String NAMESPACE = "connect-s2i-cluster-test";
    public static final String CONNECT_CLUSTER_NAME = "my-connect-cluster";
    public static final String CONNECT_DEPLOYMENT_NAME = CONNECT_CLUSTER_NAME + "-connect";
    private static final Logger LOGGER = LogManager.getLogger(ConnectS2IST.class);

    @Test
    @OpenShiftOnly
    @Tag(ACCEPTANCE)
    void testDeployS2IWithMongoDBPlugin() throws IOException {
        getTestClassResources().kafkaConnectS2I(CONNECT_CLUSTER_NAME, 1, CLUSTER_NAME)
            .editMetadata()
                .addToLabels("type", "kafka-connect-s2i")
            .endMetadata()
            .done();

        Map<String, String> connectSnapshot = StUtils.depConfigSnapshot(CONNECT_DEPLOYMENT_NAME);

        File dir = StUtils.downloadAndUnzip("https://repo1.maven.org/maven2/io/debezium/debezium-connector-mongodb/0.7.5/debezium-connector-mongodb-0.7.5-plugin.zip");

        // Start a new image build using the plugins directory
        cmdKubeClient().exec("oc", "start-build", CONNECT_DEPLOYMENT_NAME, "--from-dir", dir.getAbsolutePath());
        // Wait for rolling update connect pods
        StUtils.waitTillDepConfigHasRolled(CONNECT_DEPLOYMENT_NAME, 1, connectSnapshot);
        String connectS2IPodName = kubeClient().listPods("type", "kafka-connect-s2i").get(0).getMetadata().getName();
        String plugins = cmdKubeClient().execInPod(connectS2IPodName, "curl", "-X", "GET", "http://localhost:8083/connector-plugins").out();

        assertThat(plugins, containsString("io.debezium.connector.mongodb.MongoDbConnector"));
    }

    @BeforeEach
    void createTestResources() {
        createTestMethodResources();
    }

    @BeforeAll
    void setupEnvironment() {
        LOGGER.info("Creating resources before the test class");
        prepareEnvForOperator(NAMESPACE);

        createTestClassResources();
        applyRoleBindings(NAMESPACE);
        // 050-Deployment
        getTestClassResources().clusterOperator(NAMESPACE).done();
        deployTestSpecificResources();
    }

    void deployTestSpecificResources() {
        getTestClassResources().kafkaEphemeral(CLUSTER_NAME, 3, 1).done();
    }

    @Override
    protected void recreateTestEnv(String coNamespace, List<String> bindingsNamespaces) {
        super.recreateTestEnv(coNamespace, bindingsNamespaces);
        deployTestSpecificResources();
    }
}
