package com.forgeflow.billing;

/**
 * How billing finds out how much of a quota someone is using - without
 * reading other modules' tables.
 *
 * Billing owns the limits; the module that owns the THING owns the count.
 * Workspace counts projects, execution counts running previews, and each
 * registers a bean implementing this. Billing depends on the interface it
 * defined, never on workspace or execution - so the dependency arrow points
 * the right way even though the data lives over there.
 */
public interface UsageSource {

    Quota quota();

    long used(Long userId);
}
