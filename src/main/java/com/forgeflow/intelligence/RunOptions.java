package com.forgeflow.intelligence;

import com.forgeflow.shared.BadRequestException;

import java.util.Locale;

/**
 * Per-request switches for one run - the knobs an eval turns to measure what
 * retrieval and review are worth, instead of assuming it.
 *
 * @param code   AUTO: retrieved code only past the whole-project threshold
 *               (the product's rule); ALWAYS: retrieve even for a small project;
 *               OFF: never
 * @param notes  how many reference notes to attach; null = the configured
 *               default, 0 = none
 * @param review whether the reviewer agent checks the changes; null = the
 *               configured default
 */
public record RunOptions(Code code, Integer notes, Boolean review) {

    public enum Code { AUTO, ALWAYS, OFF }

    public static final RunOptions DEFAULT = new RunOptions(Code.AUTO, null, null);

    public RunOptions(Code code, Integer notes) {
        this(code, notes, null);
    }

    /** From request fields; null means "the default". A bad value is a 400. */
    public static RunOptions parse(String code, Integer notes) {
        return parse(code, notes, null);
    }

    public static RunOptions parse(String code, Integer notes, Boolean review) {
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
        return new RunOptions(c, notes, review);
    }
}
