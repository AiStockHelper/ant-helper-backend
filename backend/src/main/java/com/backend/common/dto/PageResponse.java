package com.backend.common.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import lombok.Getter;

@Getter
public class PageResponse<T> {
	private final List<T> content;
	private final Boolean hasNext;
	private final int totalPages;
	private final long totalElements;
	private final int page;
	private final int size;
	private final Boolean isFirst;
	private final Boolean isLast;

	private PageResponse(List<T> content, boolean hasNext, int totalPages, long totalElements,
		int page, int size, boolean isFirst, boolean isLast) {
		this.content = content;
		this.hasNext = hasNext;
		this.totalPages = totalPages;
		this.totalElements = totalElements;
		this.page = page;
		this.size = size;
		this.isFirst = isFirst;
		this.isLast = isLast;
	}

	public static <T> PageResponse<T> of(Page<T> page) {
		return new PageResponse<>(
			page.getContent(),
			page.hasNext(),
			page.getTotalPages(),
			page.getTotalElements(),
			page.getNumber(),
			page.getSize(),
			page.isFirst(),
			page.isLast()
		);
	}
}