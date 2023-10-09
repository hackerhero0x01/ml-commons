/*
 *
 *  * Copyright OpenSearch Contributors
 *  * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.opensearch.ml.settings;

import static org.opensearch.ml.settings.MLCommonsSettings.ML_COMMONS_REMOTE_INFERENCE_ENABLED;
import static org.opensearch.ml.settings.MLCommonsSettings.ML_COMMONS_UPDATE_CONNECTOR_ENABLED;

import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.Settings;

public class MLFeatureEnabledSetting {

    private volatile Boolean isRemoteInferenceEnabled;
    private volatile Boolean isUpdateConnectorEnabled;

    public MLFeatureEnabledSetting(ClusterService clusterService, Settings settings) {
        isRemoteInferenceEnabled = ML_COMMONS_REMOTE_INFERENCE_ENABLED.get(settings);
        isUpdateConnectorEnabled = ML_COMMONS_UPDATE_CONNECTOR_ENABLED.get(settings);

        clusterService
            .getClusterSettings()
            .addSettingsUpdateConsumer(ML_COMMONS_REMOTE_INFERENCE_ENABLED, it -> isRemoteInferenceEnabled = it);
        clusterService
            .getClusterSettings()
            .addSettingsUpdateConsumer(ML_COMMONS_UPDATE_CONNECTOR_ENABLED, it -> isUpdateConnectorEnabled = it);
    }

    /**
     * Whether the remote inference feature is enabled. If disabled, APIs in ml-commons will block remote inference.
     * @return whether Remote Inference is enabled.
     */
    public boolean isRemoteInferenceEnabled() {
        return isRemoteInferenceEnabled;
    }

    public boolean isUpdateConnectorEnabled() {
        return isUpdateConnectorEnabled;
    }

}
