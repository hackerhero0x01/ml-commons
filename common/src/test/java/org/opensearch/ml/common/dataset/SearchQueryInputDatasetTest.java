/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  The OpenSearch Contributors require contributions made to
 *  this file be licensed under the Apache-2.0 license or a
 *  compatible open source license.
 *
 *  Modifications Copyright OpenSearch Contributors. See
 *  GitHub history for details.
 */

package org.opensearch.ml.common.dataset;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.search.builder.SearchSourceBuilder;

import static org.junit.Assert.assertEquals;

public class SearchQueryInputDatasetTest {

    @Rule
    public ExpectedException exceptionRule = ExpectedException.none();

    @Test
    public void writeTo_Success() throws IOException {
        SearchQueryInputDataset searchQueryInputDataset = SearchQueryInputDataset.builder()
            .indices(Arrays.asList("index1"))
            .searchSourceBuilder(new SearchSourceBuilder().size(1))
            .build();
        BytesStreamOutput bytesStreamOutput = new BytesStreamOutput();
        searchQueryInputDataset.writeTo(bytesStreamOutput);
        StreamInput streamInput = bytesStreamOutput.bytes().streamInput();
        MLInputDataType inputDataType = streamInput.readEnum(MLInputDataType.class);
        assertEquals(MLInputDataType.SEARCH_QUERY, inputDataType);
        searchQueryInputDataset = new SearchQueryInputDataset(streamInput);
        assertEquals(1, searchQueryInputDataset.getIndices().size());
        assertEquals(1, searchQueryInputDataset.getSearchSourceBuilder().size());
    }

    @Test
    public void init_EmptyIndices() {
        exceptionRule.expect(IllegalArgumentException.class);
        exceptionRule.expectMessage("indices can't be empty");
        SearchQueryInputDataset.builder()
            .indices(new ArrayList<>())
            .searchSourceBuilder(new SearchSourceBuilder().size(1))
            .build();
    }
}