package com.demo.backend.web;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.function.Function;

/** One page of a list: {items, page, size, total, totalPages}. Pages are zero-based. */
public record PageResponse<T>(List<T> items, int page, int size, long total, int totalPages) {
    public static final int DEFAULT_SIZE = 50;
    public static final int MAX_SIZE = 200;

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    /** Newest first; out-of-range values are clamped rather than rejected. */
    public static Pageable request(Integer page, Integer size) {
        int p = page == null ? 0 : Math.max(0, page);
        int s = size == null ? DEFAULT_SIZE : Math.min(MAX_SIZE, Math.max(1, size));
        return PageRequest.of(p, s, Sort.by(Sort.Direction.DESC, "id"));
    }

    /** Search text, or null when blank. */
    public static String query(String q) {
        return q == null || q.isBlank() ? null : q.trim();
    }
}
