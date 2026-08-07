package com.chronos.common;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Our own pagination envelope.
 *
 * <p><b>Why not serialise Spring's {@code Page} directly:</b> Boot 3.3 logs a warning for
 * exactly that — {@code PageImpl}'s JSON is an implementation detail that has changed between
 * versions and would silently break the client. This record is a stable contract we own.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last
) {
    /** Maps a page of entities to a page of DTOs in one step, so controllers stay one-liners. */
    public static <E, D> PageResponse<D> from(Page<E> page, Function<E, D> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast());
    }
}
