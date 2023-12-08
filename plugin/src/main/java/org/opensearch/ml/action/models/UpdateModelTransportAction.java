/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.action.models;

import static org.opensearch.ml.common.CommonValue.ML_MODEL_GROUP_INDEX;
import static org.opensearch.ml.common.CommonValue.ML_MODEL_INDEX;
import static org.opensearch.ml.common.FunctionName.REMOTE;
import static org.opensearch.ml.common.FunctionName.TEXT_EMBEDDING;
import static org.opensearch.ml.settings.MLCommonsSettings.ML_COMMONS_TRUSTED_CONNECTOR_ENDPOINTS_REGEX;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.opensearch.OpenSearchStatusException;
import org.opensearch.action.ActionRequest;
import org.opensearch.action.DocWriteResponse;
import org.opensearch.action.get.GetResponse;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.action.update.UpdateRequest;
import org.opensearch.action.update.UpdateResponse;
import org.opensearch.client.Client;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.commons.authuser.User;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.common.Strings;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.ml.common.FunctionName;
import org.opensearch.ml.common.MLModel;
import org.opensearch.ml.common.MLModelGroup;
import org.opensearch.ml.common.connector.Connector;
import org.opensearch.ml.common.exception.MLValidationException;
import org.opensearch.ml.common.model.MLModelState;
import org.opensearch.ml.common.transport.model.MLInPlaceUpdateModelAction;
import org.opensearch.ml.common.transport.model.MLInPlaceUpdateModelNodesRequest;
import org.opensearch.ml.common.transport.model.MLUpdateModelAction;
import org.opensearch.ml.common.transport.model.MLUpdateModelInput;
import org.opensearch.ml.common.transport.model.MLUpdateModelRequest;
import org.opensearch.ml.engine.MLEngine;
import org.opensearch.ml.helper.ConnectorAccessControlHelper;
import org.opensearch.ml.helper.ModelAccessControlHelper;
import org.opensearch.ml.model.MLModelGroupManager;
import org.opensearch.ml.model.MLModelManager;
import org.opensearch.ml.utils.RestActionUtils;
import org.opensearch.tasks.Task;
import org.opensearch.transport.TransportService;

import com.google.common.annotations.VisibleForTesting;

import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.log4j.Log4j2;

@Log4j2
@FieldDefaults(level = AccessLevel.PRIVATE)
public class UpdateModelTransportAction extends HandledTransportAction<ActionRequest, UpdateResponse> {
    Client client;

    Settings settings;
    ClusterService clusterService;
    ModelAccessControlHelper modelAccessControlHelper;
    ConnectorAccessControlHelper connectorAccessControlHelper;
    MLModelManager mlModelManager;
    MLModelGroupManager mlModelGroupManager;
    MLEngine mlEngine;
    volatile List<String> trustedConnectorEndpointsRegex;

    @Inject
    public UpdateModelTransportAction(
        TransportService transportService,
        ActionFilters actionFilters,
        Client client,
        ConnectorAccessControlHelper connectorAccessControlHelper,
        ModelAccessControlHelper modelAccessControlHelper,
        MLModelManager mlModelManager,
        MLModelGroupManager mlModelGroupManager,
        Settings settings,
        ClusterService clusterService,
        MLEngine mlEngine
    ) {
        super(MLUpdateModelAction.NAME, transportService, actionFilters, MLUpdateModelRequest::new);
        this.client = client;
        this.modelAccessControlHelper = modelAccessControlHelper;
        this.connectorAccessControlHelper = connectorAccessControlHelper;
        this.mlModelManager = mlModelManager;
        this.mlModelGroupManager = mlModelGroupManager;
        this.clusterService = clusterService;
        this.mlEngine = mlEngine;
        this.settings = settings;
        trustedConnectorEndpointsRegex = ML_COMMONS_TRUSTED_CONNECTOR_ENDPOINTS_REGEX.get(settings);
        clusterService
            .getClusterSettings()
            .addSettingsUpdateConsumer(ML_COMMONS_TRUSTED_CONNECTOR_ENDPOINTS_REGEX, it -> trustedConnectorEndpointsRegex = it);
    }

    @Override
    protected void doExecute(Task task, ActionRequest request, ActionListener<UpdateResponse> actionListener) {
        MLUpdateModelRequest updateModelRequest = MLUpdateModelRequest.fromActionRequest(request);
        MLUpdateModelInput updateModelInput = updateModelRequest.getUpdateModelInput();
        String modelId = updateModelInput.getModelId();
        User user = RestActionUtils.getUserContext(client);
        boolean isSuperAdmin = isSuperAdminUserWrapper(clusterService, client);

        String[] excludes = new String[] { MLModel.MODEL_CONTENT_FIELD, MLModel.OLD_MODEL_CONTENT_FIELD };

        try (ThreadContext.StoredContext context = client.threadPool().getThreadContext().stashContext()) {
            ActionListener<UpdateResponse> wrappedListener = ActionListener.runBefore(actionListener, context::restore);
            mlModelManager.getModel(modelId, null, excludes, ActionListener.wrap(mlModel -> {
                if (!isModelDeploying(mlModel.getModelState())) {
                    boolean isModelDeployed = isModelDeployed(mlModel.getModelState());
                    FunctionName functionName = mlModel.getAlgorithm();
                    if (functionName == TEXT_EMBEDDING || functionName == REMOTE) {
                        if (mlModel.getIsHidden() != null && mlModel.getIsHidden()) {
                            if (isSuperAdmin) {
                                updateRemoteOrTextEmbeddingModel(
                                    modelId,
                                    updateModelInput,
                                    mlModel,
                                    user,
                                    wrappedListener,
                                    isModelDeployed
                                );
                            } else {
                                wrappedListener
                                    .onFailure(
                                        new OpenSearchStatusException(
                                            "User doesn't have privilege to perform this operation on this model, model ID " + modelId,
                                            RestStatus.FORBIDDEN
                                        )
                                    );
                            }
                        } else {
                            modelAccessControlHelper
                                .validateModelGroupAccess(user, mlModel.getModelGroupId(), client, ActionListener.wrap(hasPermission -> {
                                    if (hasPermission) {
                                        updateRemoteOrTextEmbeddingModel(
                                            modelId,
                                            updateModelInput,
                                            mlModel,
                                            user,
                                            wrappedListener,
                                            isModelDeployed
                                        );
                                    } else {
                                        wrappedListener
                                            .onFailure(
                                                new OpenSearchStatusException(
                                                    "User doesn't have privilege to perform this operation on this model, model ID "
                                                        + modelId,
                                                    RestStatus.FORBIDDEN
                                                )
                                            );
                                    }
                                }, exception -> {
                                    log.error("Permission denied: Unable to update the model with ID {}. Details: {}", modelId, exception);
                                    wrappedListener.onFailure(exception);
                                }));
                        }

                    } else {
                        wrappedListener
                            .onFailure(
                                new MLValidationException(
                                    "User doesn't have privilege to perform this operation on this function category: "
                                        + functionName.toString()
                                )
                            );
                    }
                } else {
                    wrappedListener
                        .onFailure(
                            new OpenSearchStatusException(
                                "Model is deploying, please wait for it complete. model ID " + modelId,
                                RestStatus.CONFLICT
                            )
                        );
                }
            },
                e -> wrappedListener
                    .onFailure(
                        new OpenSearchStatusException(
                            "Failed to find model to update with the provided model id: " + modelId,
                            RestStatus.NOT_FOUND
                        )
                    )
            ));
        } catch (Exception e) {
            log.error("Failed to update ML model for " + modelId, e);
            actionListener.onFailure(e);
        }
    }

    private void updateRemoteOrTextEmbeddingModel(
        String modelId,
        MLUpdateModelInput updateModelInput,
        MLModel mlModel,
        User user,
        ActionListener<UpdateResponse> wrappedListener,
        boolean isModelDeployed
    ) {
        String newModelGroupId = (Strings.hasLength(updateModelInput.getModelGroupId())
            && !Objects.equals(updateModelInput.getModelGroupId(), mlModel.getModelGroupId())) ? updateModelInput.getModelGroupId() : null;
        String relinkConnectorId = Strings.hasLength(updateModelInput.getConnectorId()) ? updateModelInput.getConnectorId() : null;

        if (mlModel.getAlgorithm() == TEXT_EMBEDDING) {
            if (relinkConnectorId == null && updateModelInput.getConnectorUpdateContent() == null) {
                updateModelWithRegisteringToAnotherModelGroup(
                    modelId,
                    newModelGroupId,
                    user,
                    updateModelInput,
                    wrappedListener,
                    isModelDeployed
                );
            } else {
                wrappedListener
                    .onFailure(
                        new OpenSearchStatusException(
                            "Trying to update the connector or connector_id field on a local model.",
                            RestStatus.BAD_REQUEST
                        )
                    );
            }
        } else {
            // mlModel.getAlgorithm() == REMOTE
            if (relinkConnectorId == null) {
                if (updateModelInput.getConnectorUpdateContent() != null) {
                    Connector connector = mlModel.getConnector();
                    connector.update(updateModelInput.getConnectorUpdateContent(), mlEngine::encrypt);
                    connector.validateConnectorURL(trustedConnectorEndpointsRegex);
                    updateModelInput.setConnector(connector);
                    updateModelInput.setConnectorUpdateContent(null);
                }
                updateModelWithRegisteringToAnotherModelGroup(
                    modelId,
                    newModelGroupId,
                    user,
                    updateModelInput,
                    wrappedListener,
                    isModelDeployed
                );
            } else {
                updateModelWithRelinkStandAloneConnector(
                    modelId,
                    newModelGroupId,
                    relinkConnectorId,
                    mlModel,
                    user,
                    updateModelInput,
                    wrappedListener,
                    isModelDeployed
                );
            }
        }
    }

    private void updateModelWithRelinkStandAloneConnector(
        String modelId,
        String newModelGroupId,
        String relinkConnectorId,
        MLModel mlModel,
        User user,
        MLUpdateModelInput updateModelInput,
        ActionListener<UpdateResponse> wrappedListener,
        boolean isModelDeployed
    ) {
        if (Strings.hasLength(mlModel.getConnectorId())) {
            connectorAccessControlHelper
                .validateConnectorAccess(client, relinkConnectorId, ActionListener.wrap(hasRelinkConnectorPermission -> {
                    if (hasRelinkConnectorPermission) {
                        updateModelWithRegisteringToAnotherModelGroup(
                            modelId,
                            newModelGroupId,
                            user,
                            updateModelInput,
                            wrappedListener,
                            isModelDeployed
                        );
                    } else {
                        wrappedListener
                            .onFailure(
                                new OpenSearchStatusException(
                                    "You don't have permission to update the connector, connector id: " + relinkConnectorId,
                                    RestStatus.FORBIDDEN
                                )
                            );
                    }
                }, exception -> {
                    log.error("Permission denied: Unable to update the connector with ID {}. Details: {}", relinkConnectorId, exception);
                    wrappedListener.onFailure(exception);
                }));
        } else {
            wrappedListener
                .onFailure(
                    new OpenSearchStatusException(
                        "This remote does not have a connector_id field, maybe it uses an internal connector.",
                        RestStatus.BAD_REQUEST
                    )
                );
        }
    }

    private void updateModelWithRegisteringToAnotherModelGroup(
        String modelId,
        String newModelGroupId,
        User user,
        MLUpdateModelInput updateModelInput,
        ActionListener<UpdateResponse> wrappedListener,
        boolean isModelDeployed
    ) {
        UpdateRequest updateRequest = new UpdateRequest(ML_MODEL_INDEX, modelId);
        // This flag is used to decide if we need to re-deploy the predictor(model) when performing the in-place update
        boolean isPredictorUpdate = (updateModelInput.getConnector() != null || updateModelInput.getConnectorId() != null);
        // This flag is used to decide if we need to perform an in-place update
        boolean isInPlaceUpdate = isModelDeployed && isPredictorUpdate;
        if (newModelGroupId != null) {
            modelAccessControlHelper.validateModelGroupAccess(user, newModelGroupId, client, ActionListener.wrap(hasRelinkPermission -> {
                if (hasRelinkPermission) {
                    mlModelGroupManager.getModelGroupResponse(newModelGroupId, ActionListener.wrap(newModelGroupResponse -> {
                        updateRequestConstructor(
                            modelId,
                            newModelGroupId,
                            updateRequest,
                            updateModelInput,
                            newModelGroupResponse,
                            wrappedListener,
                            isInPlaceUpdate,
                            isPredictorUpdate
                        );
                    },
                        exception -> wrappedListener
                            .onFailure(
                                new OpenSearchStatusException(
                                    "Failed to find the model group with the provided model group id in the update model input, MODEL_GROUP_ID: "
                                        + newModelGroupId,
                                    RestStatus.NOT_FOUND
                                )
                            )
                    ));
                } else {
                    wrappedListener
                        .onFailure(
                            new OpenSearchStatusException(
                                "User Doesn't have privilege to re-link this model to the target model group due to no access to the target model group with model group ID "
                                    + newModelGroupId,
                                RestStatus.FORBIDDEN
                            )
                        );
                }
            }, exception -> {
                log.error("Permission denied: Unable to update the model with ID {}. Details: {}", modelId, exception);
                wrappedListener.onFailure(exception);
            }));
        } else {
            updateRequestConstructor(modelId, updateRequest, updateModelInput, wrappedListener, isInPlaceUpdate, isPredictorUpdate);
        }
    }

    private void updateRequestConstructor(
        String modelId,
        UpdateRequest updateRequest,
        MLUpdateModelInput updateModelInput,
        ActionListener<UpdateResponse> wrappedListener,
        boolean isInPlaceUpdate,
        boolean isPredictorUpdate
    ) {
        try {
            updateRequest.doc(updateModelInput.toXContent(XContentFactory.jsonBuilder(), ToXContent.EMPTY_PARAMS));
            updateRequest.docAsUpsert(true);
            if (isInPlaceUpdate) {
                String[] targetNodeIds = getAllNodes();
                MLInPlaceUpdateModelNodesRequest mlInPlaceUpdateModelNodesRequest = new MLInPlaceUpdateModelNodesRequest(
                    targetNodeIds,
                    modelId,
                    isPredictorUpdate
                );
                client
                    .update(
                        updateRequest,
                        getUpdateResponseListenerWithInPlaceUpdate(modelId, wrappedListener, mlInPlaceUpdateModelNodesRequest)
                    );
            } else {
                client.update(updateRequest, getUpdateResponseListener(modelId, wrappedListener));
            }
        } catch (IOException e) {
            log.error("Failed to build update request.");
            wrappedListener.onFailure(e);
        }
    }

    private void updateRequestConstructor(
        String modelId,
        String newModelGroupId,
        UpdateRequest updateRequest,
        MLUpdateModelInput updateModelInput,
        GetResponse newModelGroupResponse,
        ActionListener<UpdateResponse> wrappedListener,
        boolean isInPlaceUpdate,
        boolean isPredictorUpdate
    ) {
        Map<String, Object> newModelGroupSourceMap = newModelGroupResponse.getSourceAsMap();
        String updatedVersion = incrementLatestVersion(newModelGroupSourceMap);
        updateModelInput.setVersion(updatedVersion);
        UpdateRequest updateModelGroupRequest = createUpdateModelGroupRequest(
            newModelGroupSourceMap,
            newModelGroupId,
            newModelGroupResponse.getSeqNo(),
            newModelGroupResponse.getPrimaryTerm(),
            Integer.parseInt(updatedVersion)
        );
        try {
            updateRequest.doc(updateModelInput.toXContent(XContentFactory.jsonBuilder(), ToXContent.EMPTY_PARAMS));
            updateRequest.docAsUpsert(true);
            if (isInPlaceUpdate) {
                String[] targetNodeIds = getAllNodes();
                MLInPlaceUpdateModelNodesRequest mlInPlaceUpdateModelNodesRequest = new MLInPlaceUpdateModelNodesRequest(
                    targetNodeIds,
                    modelId,
                    isPredictorUpdate
                );
                client.update(updateModelGroupRequest, ActionListener.wrap(r -> {
                    client
                        .update(
                            updateRequest,
                            getUpdateResponseListenerWithInPlaceUpdate(modelId, wrappedListener, mlInPlaceUpdateModelNodesRequest)
                        );
                }, e -> {
                    log
                        .error(
                            "Failed to register ML model with model ID {} to the new model group with model group ID {}",
                            modelId,
                            newModelGroupId
                        );
                    wrappedListener.onFailure(e);
                }));
            } else {
                client.update(updateModelGroupRequest, ActionListener.wrap(r -> {
                    client.update(updateRequest, getUpdateResponseListener(modelId, wrappedListener));
                }, e -> {
                    log
                        .error(
                            "Failed to register ML model with model ID {} to the new model group with model group ID {}",
                            modelId,
                            newModelGroupId
                        );
                    wrappedListener.onFailure(e);
                }));
            }
        } catch (IOException e) {
            log.error("Failed to build update request.");
            wrappedListener.onFailure(e);
        }
    }

    private ActionListener<UpdateResponse> getUpdateResponseListenerWithInPlaceUpdate(
        String modelId,
        ActionListener<UpdateResponse> wrappedListener,
        MLInPlaceUpdateModelNodesRequest mlInPlaceUpdateModelNodesRequest
    ) {
        return ActionListener.wrap(updateResponse -> {
            if (updateResponse != null && updateResponse.getResult() != DocWriteResponse.Result.UPDATED) {
                log.info("Model id:{} failed update", modelId);
                wrappedListener.onResponse(updateResponse);
                return;
            }
            client.execute(MLInPlaceUpdateModelAction.INSTANCE, mlInPlaceUpdateModelNodesRequest, ActionListener.wrap(r -> {
                log.info("Successfully perform in-place update ML model with model ID {}", modelId);
                wrappedListener.onResponse(updateResponse);
            }, e -> {
                log.error("Failed to perform in-place update for model: {}" + modelId, e);
                wrappedListener.onFailure(e);
            }));
        }, exception -> {
            log.error("Failed to update ML model: " + modelId, exception);
            wrappedListener.onFailure(exception);
        });
    }

    private ActionListener<UpdateResponse> getUpdateResponseListener(String modelId, ActionListener<UpdateResponse> wrappedListener) {
        return ActionListener.wrap(updateResponse -> {
            if (updateResponse != null && updateResponse.getResult() != DocWriteResponse.Result.UPDATED) {
                log.info("Model id:{} failed update", modelId);
                wrappedListener.onResponse(updateResponse);
                return;
            }
            log.info("Successfully update ML model with model ID {}", modelId);
            wrappedListener.onResponse(updateResponse);
        }, exception -> {
            log.error("Failed to update ML model: " + modelId, exception);
            wrappedListener.onFailure(exception);
        });
    }

    private String incrementLatestVersion(Map<String, Object> modelGroupSourceMap) {
        return Integer.toString((int) modelGroupSourceMap.get(MLModelGroup.LATEST_VERSION_FIELD) + 1);
    }

    private UpdateRequest createUpdateModelGroupRequest(
        Map<String, Object> modelGroupSourceMap,
        String modelGroupId,
        long seqNo,
        long primaryTerm,
        int updatedVersion
    ) {
        modelGroupSourceMap.put(MLModelGroup.LATEST_VERSION_FIELD, updatedVersion);
        modelGroupSourceMap.put(MLModelGroup.LAST_UPDATED_TIME_FIELD, Instant.now().toEpochMilli());
        UpdateRequest updateModelGroupRequest = new UpdateRequest();

        updateModelGroupRequest
            .index(ML_MODEL_GROUP_INDEX)
            .id(modelGroupId)
            .setIfSeqNo(seqNo)
            .setIfPrimaryTerm(primaryTerm)
            .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
            .doc(modelGroupSourceMap);

        return updateModelGroupRequest;
    }

    private Boolean isModelDeployed(MLModelState mlModelState) {
        return mlModelState.equals(MLModelState.LOADED)
            || mlModelState.equals(MLModelState.PARTIALLY_LOADED)
            || mlModelState.equals(MLModelState.DEPLOYED)
            || mlModelState.equals(MLModelState.PARTIALLY_DEPLOYED);
    }

    private Boolean isModelDeploying(MLModelState mlModelState) {
        return mlModelState.equals(MLModelState.LOADING) || mlModelState.equals(MLModelState.DEPLOYING);
    }

    private String[] getAllNodes() {
        Iterator<DiscoveryNode> iterator = clusterService.state().nodes().iterator();
        List<String> nodeIds = new ArrayList<>();
        while (iterator.hasNext()) {
            nodeIds.add(iterator.next().getId());
        }
        return nodeIds.toArray(new String[0]);
    }

    @VisibleForTesting
    boolean isSuperAdminUserWrapper(ClusterService clusterService, Client client) {
        return RestActionUtils.isSuperAdminUser(clusterService, client);
    }
}
