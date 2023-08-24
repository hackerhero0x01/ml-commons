package org.opensearch.ml.common.transport.model_group;

import org.junit.Before;
import org.junit.Test;
import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.ml.common.ModelAccessMode;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class MLRegisterModelGroupInputTest {

    private MLRegisterModelGroupInput mlRegisterModelGroupInput;

    @Before
    public void setUp() throws Exception {

        mlRegisterModelGroupInput = mlRegisterModelGroupInput.builder()
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
        mlRegisterModelGroupInput.writeTo(bytesStreamOutput);
        StreamInput streamInput = bytesStreamOutput.bytes().streamInput();
        MLRegisterModelGroupInput parsedInput = new MLRegisterModelGroupInput(streamInput);
        assertEquals(mlRegisterModelGroupInput.getName(), parsedInput.getName());
    }
}
