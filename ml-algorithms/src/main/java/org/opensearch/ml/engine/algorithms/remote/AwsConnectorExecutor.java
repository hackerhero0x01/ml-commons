/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.algorithms.remote;

import static org.opensearch.ml.common.CommonValue.REMOTE_SERVICE_ERROR;
import static org.opensearch.ml.common.connector.AbstractConnector.ACCESS_KEY_FIELD;
import static org.opensearch.ml.common.connector.AbstractConnector.SECRET_KEY_FIELD;
import static org.opensearch.ml.common.connector.AbstractConnector.SESSION_TOKEN_FIELD;
import static org.opensearch.ml.common.connector.ConnectorProtocols.AWS_SIGV4;
import static org.opensearch.ml.engine.algorithms.remote.ConnectorUtils.processOutput;
import static software.amazon.awssdk.http.SdkHttpMethod.POST;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.OpenSearchStatusException;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.util.TokenBucket;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.ml.common.connector.AwsConnector;
import org.opensearch.ml.common.connector.Connector;
import org.opensearch.ml.common.exception.MLException;
import org.opensearch.ml.common.input.MLInput;
import org.opensearch.ml.common.output.model.ModelTensors;
import org.opensearch.ml.engine.annotation.ConnectorExecutor;
import org.opensearch.ml.engine.credentials.aws.InternalAwsCredentials;
import org.opensearch.ml.engine.credentialscommunication.Credentials;
import org.opensearch.ml.engine.credentialscommunication.CredentialsRequest;
import org.opensearch.script.ScriptService;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import software.amazon.awssdk.core.internal.http.loader.DefaultSdkHttpClientBuilder;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpFullRequest;

@Log4j2
@ConnectorExecutor(AWS_SIGV4)
public class AwsConnectorExecutor implements RemoteConnectorExecutor {

    @Getter
    private AwsConnector connector;
    private final SdkHttpClient httpClient;
    @Setter
    @Getter
    private ScriptService scriptService;
    @Setter
    @Getter
    private ClusterService clusterService;
    @Setter
    @Getter
    private TokenBucket modelRateLimiter;

    public AwsConnectorExecutor(Connector connector, SdkHttpClient httpClient) {
        this.connector = (AwsConnector) connector;
        this.httpClient = httpClient;
    }

    public AwsConnectorExecutor(Connector connector) {
        this(connector, new DefaultSdkHttpClientBuilder().build());
    }

    private Map<String, String> getCredentialsFromIAMRole(String roleArn, String clusterName) throws IOException {
        Map<String, String> awsCredentials = new HashMap<>();
        try {
            CredentialsRequest credentialsRequest = new CredentialsRequest(roleArn, clusterName);
            InternalAwsCredentials credentials = Credentials.getCredentials(credentialsRequest);
            awsCredentials.put(ACCESS_KEY_FIELD, credentials.getAccessKey());
            awsCredentials.put(SECRET_KEY_FIELD, credentials.getSecretKey());
            awsCredentials.put(SESSION_TOKEN_FIELD, credentials.getSessionToken());
        } catch (Exception ex) {
            log.error("Exception occurred gaining credentials: " + ex);
            throw ex;
        }
        return awsCredentials;
    }

    @Override
    public void invokeRemoteModelInManagedService(
        MLInput mlInput,
        Map<String, String> parameters,
        String payload,
        List<ModelTensors> tensorOutputs
    ) {
        try {
            String clusterName = clusterService.getClusterName().toString();
            String roleArn = "";
            if (connector.getDecryptedCredential().get("roleArn") != null) {
                roleArn = connector.getDecryptedCredential().get("roleArn");
            }
            Map<String, String> awsCredentials = getCredentialsFromIAMRole(roleArn, clusterName);
            String endpoint = connector.getPredictEndpoint(parameters);
            RequestBody requestBody = RequestBody.fromString(payload);

            SdkHttpFullRequest.Builder builder = SdkHttpFullRequest
                .builder()
                .method(POST)
                .uri(URI.create(endpoint))
                .contentStreamProvider(requestBody.contentStreamProvider());
            Map<String, String> headers = connector.getDecryptedHeaders();
            if (headers != null) {
                for (String key : headers.keySet()) {
                    builder.putHeader(key, headers.get(key));
                }
            }
            SdkHttpFullRequest request = builder.build();
            HttpExecuteRequest executeRequest = HttpExecuteRequest
                .builder()
                .request(signRequestInManagedService(request, awsCredentials))
                .contentStreamProvider(request.contentStreamProvider().orElse(null))
                .build();

            HttpExecuteResponse response = AccessController.doPrivileged((PrivilegedExceptionAction<HttpExecuteResponse>) () -> {
                return httpClient.prepareRequest(executeRequest).call();
            });
            int statusCode = response.httpResponse().statusCode();

            AbortableInputStream body = null;
            if (response.responseBody().isPresent()) {
                body = response.responseBody().get();
            }

            StringBuilder responseBuilder = new StringBuilder();
            if (body != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        responseBuilder.append(line);
                    }
                }
            } else {
                throw new OpenSearchStatusException("No response from model", RestStatus.BAD_REQUEST);
            }
            String modelResponse = responseBuilder.toString();
            if (statusCode < 200 || statusCode >= 300) {
                throw new OpenSearchStatusException(REMOTE_SERVICE_ERROR + modelResponse, RestStatus.fromCode(statusCode));
            }

            ModelTensors tensors = processOutput(modelResponse, connector, scriptService, parameters);
            tensors.setStatusCode(statusCode);
            tensorOutputs.add(tensors);
        } catch (RuntimeException exception) {
            log.error("Failed to execute predict in aws connector: " + exception.getMessage(), exception);
            throw exception;
        } catch (Throwable e) {
            log.error("Failed to execute predict in aws connector", e);
            throw new MLException("Fail to execute predict in aws connector", e);
        }

    }

    @Override
    public void invokeRemoteModel(MLInput mlInput, Map<String, String> parameters, String payload, List<ModelTensors> tensorOutputs) {
        try {
            String endpoint = connector.getPredictEndpoint(parameters);
            RequestBody requestBody = RequestBody.fromString(payload);

            SdkHttpFullRequest.Builder builder = SdkHttpFullRequest
                .builder()
                .method(POST)
                .uri(URI.create(endpoint))
                .contentStreamProvider(requestBody.contentStreamProvider());
            Map<String, String> headers = connector.getDecryptedHeaders();
            if (headers != null) {
                for (String key : headers.keySet()) {
                    builder.putHeader(key, headers.get(key));
                }
            }
            SdkHttpFullRequest request = builder.build();
            HttpExecuteRequest executeRequest = HttpExecuteRequest
                .builder()
                .request(signRequest(request))
                .contentStreamProvider(request.contentStreamProvider().orElse(null))
                .build();

            HttpExecuteResponse response = AccessController.doPrivileged((PrivilegedExceptionAction<HttpExecuteResponse>) () -> {
                return httpClient.prepareRequest(executeRequest).call();
            });
            int statusCode = response.httpResponse().statusCode();

            AbortableInputStream body = null;
            if (response.responseBody().isPresent()) {
                body = response.responseBody().get();
            }

            StringBuilder responseBuilder = new StringBuilder();
            if (body != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        responseBuilder.append(line);
                    }
                }
            } else {
                throw new OpenSearchStatusException("No response from model", RestStatus.BAD_REQUEST);
            }
            String modelResponse = responseBuilder.toString();
            if (statusCode < 200 || statusCode >= 300) {
                throw new OpenSearchStatusException(REMOTE_SERVICE_ERROR + modelResponse, RestStatus.fromCode(statusCode));
            }

            ModelTensors tensors = processOutput(modelResponse, connector, scriptService, parameters);
            tensors.setStatusCode(statusCode);
            tensorOutputs.add(tensors);
        } catch (RuntimeException exception) {
            log.error("Failed to execute predict in aws connector: " + exception.getMessage(), exception);
            throw exception;
        } catch (Throwable e) {
            log.error("Failed to execute predict in aws connector", e);
            throw new MLException("Fail to execute predict in aws connector", e);
        }
    }

    private SdkHttpFullRequest signRequest(SdkHttpFullRequest request) {
        String accessKey = connector.getAccessKey();
        String secretKey = connector.getSecretKey();
        String sessionToken = connector.getSessionToken();
        String signingName = connector.getServiceName();
        String region = connector.getRegion();

        return ConnectorUtils.signRequest(request, accessKey, secretKey, sessionToken, signingName, region);
    }

    private SdkHttpFullRequest signRequestInManagedService(SdkHttpFullRequest request, Map<String, String> awsCredentials) {
        String accessKey = awsCredentials.get(ACCESS_KEY_FIELD);
        String secretKey = awsCredentials.get(SECRET_KEY_FIELD);
        String sessionToken = awsCredentials.get(SESSION_TOKEN_FIELD);
        String signingName = connector.getServiceName();
        String region = connector.getRegion();
        return ConnectorUtils.signRequest(request, accessKey, secretKey, sessionToken, signingName, region);
    }
}
