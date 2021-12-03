/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 *
 */

package org.opensearch.ml.engine.algorithms.anomalylocalization;

import java.util.List;
import java.util.Map;

import lombok.extern.log4j.Log4j2;

/**
 * A hybrid counter that starts with exact counting with map and switches to approximate counting with sketch as the size grows.
 */
@Log4j2
public class HybridCounter implements Counter {

    protected static int SKETCH_THRESHOLD = 10_000;

    private Counter counter = new Hashmap();
    private int count = 0;

    @Override
    public void increment(List<String> key, double value) {
        this.counter.increment(key, value);
        updateCount();
    }

    @Override
    public double estimate(List<String> key) {
        return this.counter.estimate(key);
    }

    private void updateCount() {
        this.count++;
        if (this.count == SKETCH_THRESHOLD) {
            Map<List<String>, Double> hashmap = ((Hashmap) counter).getKeyValues();
            boolean hasNegative = hashmap.values().stream().anyMatch(v -> v < 0);
            Counter newCounter;
            if (hasNegative) {
                newCounter = new CountSketch();
            } else {
                newCounter = new CountMinSketch();
            }
            hashmap.forEach((k, v) -> newCounter.increment(k, v));
            this.counter = newCounter;
        }
    }
}
