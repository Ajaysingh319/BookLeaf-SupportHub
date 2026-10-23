package com.bookleaf.support.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** List response shape: { data, pagination }. */
public record PageResponse<T>(List<T> data, Pagination pagination) {

    public record Pagination(int page, int size, long totalItems, int totalPages) {
    }

    public static <S, T> PageResponse<T> of(Page<S> page, Function<S, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                new Pagination(page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages()));
    }
}
