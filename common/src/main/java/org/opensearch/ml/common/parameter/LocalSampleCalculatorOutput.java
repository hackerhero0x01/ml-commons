package org.opensearch.ml.common.parameter;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.xcontent.XContentBuilder;

import java.io.IOException;

@Data
public class LocalSampleCalculatorOutput implements Output{

    private Double result;

    @Builder
    public LocalSampleCalculatorOutput(Double totalSum) {
        this.result = totalSum;
    }

    public LocalSampleCalculatorOutput(StreamInput in) throws IOException {
        result = in.readOptionalDouble();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeOptionalDouble(result);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        if (result != null) {
            builder.field("result", result);
        }
        builder.endObject();
        return builder;
    }
}
