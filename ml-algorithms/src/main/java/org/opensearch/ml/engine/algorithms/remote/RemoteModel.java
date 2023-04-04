/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.algorithms.remote;

import ai.djl.modality.Input;
import ai.djl.modality.Output;
import ai.djl.translate.TranslateException;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorFactory;
import lombok.extern.log4j.Log4j2;
import org.opensearch.ml.common.FunctionName;
import org.opensearch.ml.common.MLModel;
import org.opensearch.ml.common.connector.Connector;
import org.opensearch.ml.common.exception.MLException;
import org.opensearch.ml.common.input.MLInput;
import org.opensearch.ml.common.model.MLModelConfig;
import org.opensearch.ml.common.output.MLOutput;
import org.opensearch.ml.common.output.model.ModelTensorOutput;
import org.opensearch.ml.engine.MLEngineClassLoader;
import org.opensearch.ml.engine.algorithms.DLModel;
import org.opensearch.ml.engine.annotation.Function;
import org.opensearch.ml.engine.encryptor.Encryptor;

import java.util.Map;

@Log4j2
@Function(FunctionName.REMOTE)
public class RemoteModel extends DLModel {

    private RemoteConnectorExecutor connectorExecutor;

    @Override
    public MLOutput predict(MLInput mlInput) {
        try {
            return predict(modelId, mlInput);
        } catch (Throwable t) {
            log.error("Failed to call remote model", t);
            throw new MLException("Failed to call remote model");
        }
    }

    @Override
    public ModelTensorOutput predict(String modelId, MLInput mlInput) throws TranslateException {
        return connectorExecutor.execute(mlInput);
    }

    @Override
    public void initModel(MLModel model, Map<String, Object> params, Encryptor encryptor) {
        try {
            Connector connector = model.getConnector().clone();
            connector.decrypt((credential) -> encryptor.decrypt(credential));
            this.connectorExecutor = MLEngineClassLoader.initInstance(connector.getName(), connector, Connector.class);
        } catch (Exception e) {
            log.error("Failed to init remote model", e);
            throw new MLException(e);
        }
    }

    @Override
    public Translator<Input, Output> getTranslator(String engine, MLModelConfig modelConfig) {
        return null;
    }

    @Override
    public TranslatorFactory getTranslatorFactory(String engine, MLModelConfig modelConfig) {
        return null;
    }


}
