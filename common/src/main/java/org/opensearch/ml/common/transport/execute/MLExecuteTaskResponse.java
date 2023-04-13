/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.common.transport.execute;

import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;
import org.opensearch.action.ActionResponse;
import org.opensearch.common.io.stream.InputStreamStreamInput;
import org.opensearch.common.io.stream.OutputStreamStreamOutput;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.core.xcontent.ToXContentObject;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.ml.common.MLCommonsClassLoader;
import org.opensearch.ml.common.FunctionName;
import org.opensearch.ml.common.output.Output;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.opensearch.ml.common.output.MLOutputType;
import org.opensearch.ml.common.output.execute.metrics_correlation.MetricsCorrelationOutput;

@Getter
@ToString
public class MLExecuteTaskResponse extends ActionResponse implements ToXContentObject {

    FunctionName functionName;
    Output output;

    @Builder
    public MLExecuteTaskResponse(@NonNull FunctionName functionName, Output output) {
        this.functionName = functionName;
        this.output = output;
    }

    public MLExecuteTaskResponse(StreamInput in) throws IOException {
        super(in);
        this.functionName = in.readEnum(FunctionName.class);
        if (FunctionName.METRICS_CORRELATION.equals(this.functionName)) {
            MLOutputType outputType = in.readEnum(MLOutputType.class);
            if (outputType == MLOutputType.MCORR_TENSOR) {
                output = MLCommonsClassLoader.initMLInstance(MLOutputType.MCORR_TENSOR, in, StreamInput.class);
            }
        } else {
            if (in.readBoolean()) {
                output = MLCommonsClassLoader.initExecuteOutputInstance(functionName, in, StreamInput.class);
            }
        }

    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeEnum(functionName);
        if (output == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            output.writeTo(out);
        }
    }

    public static MLExecuteTaskResponse fromActionResponse(ActionResponse actionResponse) {
        if (actionResponse instanceof MLExecuteTaskResponse) {
            return (MLExecuteTaskResponse) actionResponse;
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             OutputStreamStreamOutput osso = new OutputStreamStreamOutput(baos)) {
            actionResponse.writeTo(osso);
            try (StreamInput input = new InputStreamStreamInput(new ByteArrayInputStream(baos.toByteArray()))) {
                return new MLExecuteTaskResponse(input);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to parse ActionResponse into MLExecuteTaskResponse", e);
        }
    }

    @Override
    public XContentBuilder toXContent(final XContentBuilder builder, final Params params) throws IOException {
        builder.startObject();
        builder.field("function_name", functionName);
        builder.startObject("output");

        System.out.println("MLExecuteTaskResponse toXContent " + output.toString());

        output.toXContent(builder, params);
        builder.endObject();
        builder.endObject();
        return builder;
    }
}
