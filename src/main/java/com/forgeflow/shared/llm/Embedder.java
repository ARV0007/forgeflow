package com.forgeflow.shared.llm;

import java.util.List;

/**
 * Turns text into vectors whose distance tracks similarity of meaning.
 *
 * Two kinds of text, embedded differently on purpose: a DOCUMENT is something
 * stored to be found later (a chunk of code); a QUERY is what someone is
 * looking for. Models trained for retrieval place a question near its answer,
 * not near other questions - but only if told which is which.
 */
public interface Embedder {

    enum Kind { DOCUMENT, QUERY }

    /** Vector length. Must match the database column: vector(768). */
    int DIMENSIONS = 768;

    /** One vector per input, in the same order. */
    List<float[]> embed(List<String> texts, Kind kind);

    /** Stored beside each vector, so vectors from different models are never compared. */
    String modelName();
}
