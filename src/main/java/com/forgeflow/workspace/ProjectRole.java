package com.forgeflow.workspace;

import java.util.EnumSet;
import java.util.Set;

/**
 * Who someone is to a project, and therefore what they may do.
 *
 * The whole permission model is this one table, in one place:
 *
 *              READ   WRITE   ADMIN
 *   OWNER       x       x       x
 *   EDITOR      x       x
 *   VIEWER      x
 *   PUBLIC      x               (not a member - the project is just public)
 */
public enum ProjectRole {

    OWNER(EnumSet.allOf(Permission.class)),
    EDITOR(EnumSet.of(Permission.READ, Permission.WRITE)),
    VIEWER(EnumSet.of(Permission.READ)),
    PUBLIC(EnumSet.of(Permission.READ));

    private final Set<Permission> granted;

    ProjectRole(Set<Permission> granted) {
        this.granted = granted;
    }

    public boolean allows(Permission permission) {
        return granted.contains(permission);
    }

    /** The two roles an owner can hand out. OWNER is not grantable; PUBLIC is not a membership. */
    public boolean isAssignable() {
        return this == EDITOR || this == VIEWER;
    }
}
