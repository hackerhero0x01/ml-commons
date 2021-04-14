package org.opensearch.ml.common.parameter;

import java.io.IOException;

import org.junit.Test;
import org.opensearch.common.io.stream.BytesStreamOutput;

import static org.junit.Assert.*;

public class MLParameterTest {

    @Test
    public void writeToAndReadStream() throws IOException {
        MLParameter mlParameter = new MLParameter("key", new int[]{1});
        BytesStreamOutput bytesStreamOutput = new BytesStreamOutput();
        mlParameter.writeTo(bytesStreamOutput);
        mlParameter = new MLParameter(bytesStreamOutput.bytes().streamInput());
        assertEquals("key", mlParameter.getName());
        assertEquals(1, ((int[])mlParameter.getValue())[0]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void init_Exception_NullName() {
        new MLParameter(null, 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void init_Exception_NullValue() {
        new MLParameter("key", null);
    }
}