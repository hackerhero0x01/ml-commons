/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.action.models;

import static org.opensearch.ml.common.CommonValue.ML_MODEL_GROUP_INDEX;
import static org.opensearch.ml.common.CommonValue.ML_MODEL_INDEX;
import static org.opensearch.ml.common.FunctionName.REMOTE;
import static org.opensearch.ml.common.FunctionName.TEXT_EMBEDDING;

import java.io.IOException;
import java.time.Instant;
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
import org.opensearch.ml.common.exception.MLValidationException;
import org.opensearch.ml.common.model.MLModelState;
import org.opensearch.ml.common.transport.model.MLUpdateModelAction;
import org.opensearch.ml.common.transport.model.MLUpdateModelInput;
import org.opensearch.ml.common.transport.model.MLUpdateModelRequest;
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
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
public class UpdateModelTransportAction extends HandledTransportAction<ActionRequest, UpdateResponse> {
    Client client;

    Settings settings;
    ClusterService clusterService;
    ModelAccessControlHelper modelAccessControlHelper;
    ConnectorAccessControlHelper connectorAccessControlHelper;
    MLModelManager mlModelManager;
    MLModelGroupManager mlModelGroupManager;

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
        ClusterService clusterService
    ) {
        super(MLUpdateModelAction.NAME, transportService, actionFilters, MLUpdateModelRequest::new);
        this.client = client;
        this.modelAccessControlHelper = modelAccessControlHelper;
        this.connectorAccessControlHelper = connectorAccessControlHelper;
        this.mlModelManager = mlModelManager;
        this.mlModelGroupManager = mlModelGroupManager;
        this.clusterService = clusterService;
        this.settings = settings;
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
            mlModelManager.getModel(modelId, null, excludes, ActionListener.wrap(mlModel -> {
                FunctionName functionName = mlModel.getAlgorithm();
                MLModelState mlModelState = mlModel.getModelState();

                if (functionName == TEXT_EMBEDDING || functionName == REMOTE) {
                    if (mlModel.getIsHidden() != null && mlModel.getIsHidden()) {
                        if (isSuperAdmin) {
                            updateRemoteOrTextEmbeddingModel(modelId, updateModelInput, mlModel, user, actionListener, context);
                        } else {
                            actionListener
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
                                    if (isModelDeployed(mlModelState)) {
                                        updateRemoteOrTextEmbeddingModel(modelId, updateModelInput, mlModel, user, actionListener, context);
                                    } else {
                                        actionListener
                                            .onFailure(
                                                new OpenSearchStatusException(
                                                    "ML Model "
                                                        + modelId
                                                        + " is in deploying or deployed state, please undeploy the models first!",
                                                    RestStatus.FORBIDDEN
                                                )
                                            );
                                    }
                                } else {
                                    actionListener
                                        .onFailure(
                                            new OpenSearchStatusException(
                                                "User doesn't have privilege to perform this operation on this model, model ID " + modelId,
                                                RestStatus.FORBIDDEN
                                            )
                                        );
                                }
                            }, exception -> {
                                log.error("Permission denied: Unable to update the model with ID {}. Details: {}", modelId, exception);
                                actionListener.onFailure(exception);
                            }));
                    }

                } else {
                    actionListener
                        .onFailure(
                            new MLValidationException(
                                "User doesn't have privilege to perform this operation on this function category: "
                                    + functionName.toString()
                            )
                        );
                }
            },
                e -> actionListener
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
        ActionListener<UpdateResponse> actionListener,
        ThreadContext.StoredContext context
    ) {
        String newModelGroupId = (Strings.hasLength(updateModelInput.getModelGroupId())
            && !Objects.equals(updateModelInput.getModelGroupId(), mlModel.getModelGroupId())) ? updateModelInput.getModelGroupId() : null;
        String relinkConnectorId = Strings.hasLength(updateModelInput.getConnectorId()) ? updateModelInput.getConnectorId() : null;

        if (mlModel.getAlgorithm() == TEXT_EMBEDDING) {
            if (relinkConnectorId == null) {
                updateModelWithRegisteringToAnotherModelGroup(modelId, newModelGroupId, user, updateModelInput, actionListener, context);
            } else {
                actionListener
                    .onFailure(
                        new OpenSearchStatusException(
                            "Trying to update the connector or connector_id field on a local model",
                            RestStatus.BAD_REQUEST
                        )
                    );
            }
        } else {
            // mlModel.getAlgorithm() == REMOTE
            if (relinkConnectorId == null) {
                updateModelWithRegisteringToAnotherModelGroup(modelId, newModelGroupId, user, updateModelInput, actionListener, context);
            } else {
                updateModelWithRelinkStandAloneConnector(
                    modelId,
                    newModelGroupId,
                    relinkConnectorId,
                    mlModel,
                    user,
                    updateModelInput,
                    actionListener,
                    context
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
        ActionListener<UpdateResponse> actionListener,
        ThreadContext.StoredContext context
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
                            actionListener,
                            context
                        );
                    } else {
                        actionListener
                            .onFailure(
                                new OpenSearchStatusException(
                                    "You don't have permission to update the connector, connector id: " + relinkConnectorId,
                                    RestStatus.FORBIDDEN
                                )
                            );
                    }
                }, exception -> {
                    log.error("Permission denied: Unable to update the connector with ID {}. Details: {}", relinkConnectorId, exception);
                    actionListener.onFailure(exception);
                }));
        } else {
            actionListener
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
        ActionListener<UpdateResponse> actionListener,
        ThreadContext.StoredContext context
    ) {
        UpdateRequest updateRequest = new UpdateRequest(ML_MODEL_INDEX, modelId);
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
                            actionListener,
                            context
                        );
                    },
                        exception -> actionListener
                            .onFailure(
                                new OpenSearchStatusException(
                                    "Failed to find the model group with the provided model group id in the update model input, MODEL_GROUP_ID: "
                                        + newModelGroupId,
                                    RestStatus.NOT_FOUND
                                )
                            )
                    ));
                } else {
                    actionListener
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
                actionListener.onFailure(exception);
            }));
        } else {
            updateRequestConstructor(modelId, updateRequest, updateModelInput, actionListener, context);
        }
    }

    private void updateRequestConstructor(
        String modelId,
        UpdateRequest updateRequest,
        MLUpdateModelInput updateModelInput,
        ActionListener<UpdateResponse> actionListener,
        ThreadContext.StoredContext context
    ) {
        try {
            updateRequest.doc(updateModelInput.toXContent(XContentFactory.jsonBuilder(), ToXContent.EMPTY_PARAMS));
            updateRequest.docAsUpsert(true);
            client.update(updateRequest, getUpdateResponseListener(modelId, actionListener, context));
        } catch (IOException e) {
            log.error("Failed to build update request.");
            actionListener.onFailure(e);
        }
    }

    private void updateRequestConstructor(
        String modelId,
        String newModelGroupId,
        UpdateRequest updateRequest,
        MLUpdateModelInput updateModelInput,
        GetResponse newModelGroupResponse,
        ActionListener<UpdateResponse> actionListener,
        ThreadContext.StoredContext context
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
            client.update(updateModelGroupRequest, ActionListener.wrap(r -> {
                client.update(updateRequest, getUpdateResponseListener(modelId, actionListener, context));
            }, e -> {
                log
                    .error(
                        "Failed to register ML model with model ID {} to the new model group with model group ID {}",
                        modelId,
                        newModelGroupId
                    );
                actionListener.onFailure(e);
            }));
        } catch (IOException e) {
            log.error("Failed to build update request.");
            actionListener.onFailure(e);
        }
    }

    private ActionListener<UpdateResponse> getUpdateResponseListener(
        String modelId,
        ActionListener<UpdateResponse> actionListener,
        ThreadContext.StoredContext context
    ) {
        return ActionListener.runBefore(ActionListener.wrap(updateResponse -> {
            if (updateResponse != null && updateResponse.getResult() != DocWriteResponse.Result.UPDATED) {
                log.info("Model id:{} failed update", modelId);
                actionListener.onResponse(updateResponse);
                return;
            }
            log.info("Successfully update ML model with model ID {}", modelId);
            actionListener.onResponse(updateResponse);
        }, exception -> {
            log.error("Failed to update ML model: " + modelId, exception);
            actionListener.onFailure(exception);
        }), context::restore);
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
        return !mlModelState.equals(MLModelState.LOADED)
            && !mlModelState.equals(MLModelState.LOADING)
            && !mlModelState.equals(MLModelState.PARTIALLY_LOADED)
            && !mlModelState.equals(MLModelState.DEPLOYED)
            && !mlModelState.equals(MLModelState.DEPLOYING)
            && !mlModelState.equals(MLModelState.PARTIALLY_DEPLOYED);
    }

    @VisibleForTesting
    boolean isSuperAdminUserWrapper(ClusterService clusterService, Client client) {
        return RestActionUtils.isSuperAdminUser(clusterService, client);
    }
}
