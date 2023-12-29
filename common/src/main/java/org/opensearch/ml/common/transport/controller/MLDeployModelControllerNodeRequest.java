/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.common.transport.controller;

import java.io.IOException;
import lombok.Getter;
import org.opensearch.action.support.nodes.BaseNodeRequest;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;

public class MLDeployModelControllerNodeRequest extends BaseNodeRequest {
    @Getter
    private MLDeployModelControllerNodesRequest deployModelControllerNodesRequest;

    public MLDeployModelControllerNodeRequest(StreamInput in) throws IOException {
        super(in);
        this.deployModelControllerNodesRequest = new MLDeployModelControllerNodesRequest(in);
    }

    public MLDeployModelControllerNodeRequest(MLDeployModelControllerNodesRequest request) {
        this.deployModelControllerNodesRequest = request;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        deployModelControllerNodesRequest.writeTo(out);
    }
}