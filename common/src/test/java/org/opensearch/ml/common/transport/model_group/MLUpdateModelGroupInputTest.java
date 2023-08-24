package org.opensearch.ml.common.transport.model_group;

import org.junit.Before;
import org.junit.Test;
import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.ml.common.ModelAccessMode;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class MLUpdateModelGroupInputTest {

    private MLUpdateModelGroupInput mlUpdateModelGroupInput;

    @Before
    public void setUp() throws Exception {

        mlUpdateModelGroupInput = mlUpdateModelGroupInput.builder()
                .modelGroupID("modelGroupId")
                .name("name")
                .description("description")
                .backendRoles(Arrays.asList("IT"))
                .modelAccessMode(ModelAccessMode.RESTRICTED)
                .isAddAllBackendRoles(true)
                .build();
    }

    @Test
    public void readInputStream() throws IOException {
        BytesStreamOutput bytesStreamOutput = new BytesStreamOutput();
        mlUpdateModelGroupInput.writeTo(bytesStreamOutput);
        StreamInput streamInput = bytesStreamOutput.bytes().streamInput();
        MLUpdateModelGroupInput parsedInput = new MLUpdateModelGroupInput(streamInput);
        assertEquals(mlUpdateModelGroupInput.getName(), parsedInput.getName());
    }
}
