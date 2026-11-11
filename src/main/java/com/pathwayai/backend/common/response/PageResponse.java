package com.pathwayai.backend.common.response;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/** Stable JSON envelope; field names match Spring Data's Page (`number` = zero-based page index). */
public record PageResponse<T>(List<T> content, int number, int size, long totalElements, int totalPages) {

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
