package com.forgeflow.intelligence;

import com.forgeflow.shared.BadRequestException;

import java.util.Locale;

/**
 * How much retrieval one run uses - the knobs an eval turns to measure what
 * RAG is worth, instead of assuming it.
 *
 * @param code  AUTO: retrieved code only past the whole-project threshold
 *              (the product's rule); ALWAYS: retrieve even for a small project;
 *              OFF: never
 * @param notes how many reference notes to attach; null = the configured
 *              default, 0 = none
 */
public record RagOptions(Code code, Integer notes) {

    public enum Code { AUTO, ALWAYS, OFF }

    public static final RagOptions DEFAULT = new RagOptions(Code.AUTO, null);

    /** From request fields; null means "the default". A bad value is a 400. */
    public static RagOptions parse(String code, Integer notes) {
        Code c = Code.AUTO;
        if (code != null && !code.isBlank()) {
            try {
                c = Code.valueOf(code.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("codeContext must be auto, always or off");
            }
        }
        if (notes != null && (notes < 0 || notes > 5)) {
            throw new BadRequestException("notes must be between 0 and 5");
        }
        return new RagOptions(c, notes);
    }
}
