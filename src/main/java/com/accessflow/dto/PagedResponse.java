package com.accessflow.dto;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * A page of results, in the shape the API promises.
 *
 * Not a Spring {@code Page}. Its JSON is a framework implementation detail that
 * has changed between versions, and a client should not be bound to it: the
 * field names here are part of this API, not of Spring Data.
 *
 * @param first true when this is the first page. Kept alongside the page number
 *              so a client does not have to derive it.
 * @param last  true when this is the last page. A page past the end is still
 *              empty and last, rather than an error, so a bookmark that has
 *              gone stale shows an empty list.
 */
public record PagedResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    public static <T> PagedResponse<T> of(Page<T> page) {
        return new PagedResponse<>(
                List.copyOf(page.getContent()),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}
