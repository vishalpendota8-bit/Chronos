package com.chronos.common;

/**
 * Thrown when a resource does not exist — or exists but is not visible to the caller.
 *
 * <p><b>Why 404 and not 403 for someone else's resource:</b> answering 403 confirms the id
 * exists, which leaks the shape of other users' data. Ownership-scoped lookups therefore just
 * return empty and we report "not found" uniformly.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }

    public static NotFoundException of(String resource, Object id) {
        return new NotFoundException(resource + " " + id + " not found");
    }
}
