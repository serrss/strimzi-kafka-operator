/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.strimzi.api.kafka.KafkaBridgeList;
import io.strimzi.api.kafka.model.DoneableKafkaBridge;
import io.strimzi.api.kafka.model.ExternalLogging;
import io.strimzi.api.kafka.model.KafkaBridge;
import io.strimzi.api.kafka.model.KafkaBridgeBuilder;
import io.strimzi.api.kafka.model.status.Condition;
import io.strimzi.api.kafka.model.status.ConditionBuilder;
import io.strimzi.api.kafka.model.status.KafkaBridgeStatus;
import io.strimzi.certs.CertManager;
import io.strimzi.operator.cluster.ClusterOperatorConfig;
import io.strimzi.operator.PlatformFeaturesAvailability;
import io.strimzi.operator.cluster.model.KafkaBridgeCluster;
import io.strimzi.operator.cluster.model.KafkaVersion;
import io.strimzi.operator.cluster.model.StatusDiff;
import io.strimzi.operator.cluster.operator.resource.ResourceOperatorSupplier;
import io.strimzi.operator.common.Annotations;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.model.ResourceType;
import io.strimzi.operator.common.operator.resource.CrdOperator;
import io.strimzi.operator.common.operator.resource.DeploymentOperator;
import io.strimzi.operator.common.operator.resource.ReconcileResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * <p>Assembly operator for a "Kafka Bridge" assembly, which manages:</p>
 * <ul>
 *     <li>A Kafka Bridge Deployment and related Services</li>
 * </ul>
 */
public class KafkaBridgeAssemblyOperator extends AbstractAssemblyOperator<KubernetesClient, KafkaBridge, KafkaBridgeList, DoneableKafkaBridge, Resource<KafkaBridge, DoneableKafkaBridge>> {
    private static final Logger log = LogManager.getLogger(KafkaBridgeAssemblyOperator.class.getName());
    public static final String ANNO_STRIMZI_IO_LOGGING = Annotations.STRIMZI_DOMAIN + "/logging";

    private final DeploymentOperator deploymentOperations;
    private final KafkaVersion.Lookup versions;

    /**
     * @param vertx                     The Vertx instance
     * @param pfa                       Platform features availability properties
     * @param certManager               Certificate manager
     * @param supplier                  Supplies the operators for different resources
     * @param config                    ClusterOperator configuration. Used to get the user-configured image pull policy and the secrets.
     */
    public KafkaBridgeAssemblyOperator(Vertx vertx, PlatformFeaturesAvailability pfa,
                                       CertManager certManager,
                                       ResourceOperatorSupplier supplier,
                                       ClusterOperatorConfig config) {
        super(vertx, pfa, ResourceType.KAFKABRIDGE, certManager, supplier.kafkaBridgeOperator, supplier, config);
        this.deploymentOperations = supplier.deploymentOperations;
        this.versions = config.versions();
    }

    @Override
    protected Future<Void> createOrUpdate(Reconciliation reconciliation, KafkaBridge assemblyResource) {
        Future<Void> createOrUpdateFuture = Future.future();
        String namespace = reconciliation.namespace();
        String name = reconciliation.name();
        KafkaBridgeCluster bridge;
        if (assemblyResource.getSpec() == null) {
            log.error("{} spec cannot be null", assemblyResource.getMetadata().getName());
            return Future.failedFuture("Spec cannot be null");
        }
        try {
            bridge = KafkaBridgeCluster.fromCrd(assemblyResource, versions);
        } catch (Exception e) {
            return Future.failedFuture(e);
        }

        ConfigMap logAndMetricsConfigMap = bridge.generateMetricsAndLogConfigMap(bridge.getLogging() instanceof ExternalLogging ?
                configMapOperations.get(namespace, ((ExternalLogging) bridge.getLogging()).getName()) :
                null);

        Map<String, String> annotations = new HashMap<>();
        annotations.put(ANNO_STRIMZI_IO_LOGGING, logAndMetricsConfigMap.getData().get(bridge.ANCILLARY_CM_KEY_LOG_CONFIG));

        log.debug("{}: Updating Kafka Bridge cluster", reconciliation, name, namespace);
        Future<Void> chainFuture = Future.future();
        kafkaBridgeServiceAccount(namespace, bridge)
            .compose(i -> deploymentOperations.scaleDown(namespace, bridge.getName(), bridge.getReplicas()))
            .compose(scale -> serviceOperations.reconcile(namespace, bridge.getServiceName(), bridge.generateService()))
            .compose(i -> configMapOperations.reconcile(namespace, bridge.getAncillaryConfigName(), logAndMetricsConfigMap))
            .compose(i -> podDisruptionBudgetOperator.reconcile(namespace, bridge.getName(), bridge.generatePodDisruptionBudget()))
            .compose(i -> deploymentOperations.reconcile(namespace, bridge.getName(), bridge.generateDeployment(annotations, pfa.isOpenshift(), imagePullPolicy, imagePullSecrets)))
            .compose(i -> deploymentOperations.scaleUp(namespace, bridge.getName(), bridge.getReplicas()))
            .compose(i -> chainFuture.complete(), chainFuture)
            .setHandler(reconciliationResult -> {
                Condition readyCondition;
                KafkaBridgeStatus kafkaBridgeStatus = new KafkaBridgeStatus();

                if (assemblyResource.getMetadata().getGeneration() != null) {
                    kafkaBridgeStatus.setObservedGeneration(assemblyResource.getMetadata().getGeneration());
                }

                if (reconciliationResult.succeeded()) {
                    readyCondition = new ConditionBuilder()
                            .withNewLastTransitionTime(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").format(dateSupplier()))
                            .withNewType("Ready")
                            .withNewStatus("True")
                            .build();
                } else {
                    readyCondition = new ConditionBuilder()
                            .withNewLastTransitionTime(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").format(dateSupplier()))
                            .withNewType("NotReady")
                            .withNewStatus("True")
                            .withNewReason(reconciliationResult.cause().getClass().getSimpleName())
                            .withNewMessage(reconciliationResult.cause().getMessage())
                            .build();
                }

                kafkaBridgeStatus.setField("todo");
                kafkaBridgeStatus.setConditions(Collections.singletonList(readyCondition));

                updateStatus(assemblyResource, reconciliation, kafkaBridgeStatus).setHandler(statusResult -> {
                    if (statusResult.succeeded()) {
                        log.debug("Status for {} is up to date", assemblyResource.getMetadata().getName());
                    } else {
                        log.error("Failed to set status for {}. {}", assemblyResource.getMetadata().getName(), statusResult.cause().getMessage());
                    }

                    // If both features succeeded, createOrUpdate succeeded as well
                    // If one or both of them failed, we prefer the reconciliation failure as the main error
                    if (reconciliationResult.succeeded() && statusResult.succeeded()) {
                        createOrUpdateFuture.complete();
                    } else if (reconciliationResult.failed()) {
                        createOrUpdateFuture.fail(reconciliationResult.cause());
                    } else {
                        createOrUpdateFuture.fail(statusResult.cause());
                    }
                });
            });
        return createOrUpdateFuture;
    }

    /**
     * Updates the Status field of the Kafka Bridge CR. It diffs the desired status against the current status and calls
     * the update only when there is any difference in non-timestamp fields.
     *
     * @param kafkaBridgeAssembly The CR of Kafka Bridge
     * @param reconciliation Reconciliation information
     * @param desiredStatus The KafkaBridgeStatus which should be set
     *
     * @return
     */
    Future<Void> updateStatus(KafkaBridge kafkaBridgeAssembly, Reconciliation reconciliation, KafkaBridgeStatus desiredStatus) {
        Future<Void> updateStatusFuture = Future.future();

        resourceOperator.getAsync(kafkaBridgeAssembly.getMetadata().getNamespace(), kafkaBridgeAssembly.getMetadata().getName()).setHandler(getRes -> {
            if (getRes.succeeded()) {
                KafkaBridge kafkaBridge = getRes.result();

                if (kafkaBridge != null) {
                    if ("kafka.strimzi.io/v1alpha1".equals(kafkaBridge.getApiVersion())) {
                        log.warn("{}: The resource needs to be upgraded from version {} to 'v1beta1' to use the status field", reconciliation, kafkaBridge.getApiVersion());
                        updateStatusFuture.complete();
                    } else {
                        KafkaBridgeStatus currentStatus = kafkaBridge.getStatus();

                        StatusDiff ksDiff = new StatusDiff(currentStatus, desiredStatus);

                        if (!ksDiff.isEmpty()) {
                            KafkaBridge resourceWithNewStatus = new KafkaBridgeBuilder(kafkaBridge).withStatus(desiredStatus).build();

                            ((CrdOperator<KubernetesClient, KafkaBridge, KafkaBridgeList, DoneableKafkaBridge>) resourceOperator).updateStatusAsync(resourceWithNewStatus).setHandler(updateRes -> {
                                if (updateRes.succeeded()) {
                                    log.debug("{}: Completed status update", reconciliation);
                                    updateStatusFuture.complete();
                                } else {
                                    log.error("{}: Failed to update status", reconciliation, updateRes.cause());
                                    updateStatusFuture.fail(updateRes.cause());
                                }
                            });
                        } else {
                            log.debug("{}: Status did not change", reconciliation);
                            updateStatusFuture.complete();
                        }
                    }
                } else {
                    log.error("{}: Current Kafka resource not found", reconciliation);
                    updateStatusFuture.fail("Current Kafka Bridge resource not found");
                }
            } else {
                log.error("{}: Failed to get the current Kafka Bridge resource and its status", reconciliation, getRes.cause());
                updateStatusFuture.fail(getRes.cause());
            }
        });

        return updateStatusFuture;
    }

    Future<ReconcileResult<ServiceAccount>> kafkaBridgeServiceAccount(String namespace, KafkaBridgeCluster bridge) {
        return serviceAccountOperations.reconcile(namespace,
                KafkaBridgeCluster.containerServiceAccountName(bridge.getCluster()),
                bridge.generateServiceAccount());
    }

    private Date dateSupplier() {
        return new Date();
    }
}
