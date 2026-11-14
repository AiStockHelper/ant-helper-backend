package com.backend.domains.news;

import java.time.Instant;

/** RSS 로 받은 뉴스 한 건(제목과 짧은 요약만 다룬다. 본문 전체는 저장하지 않는다). */
public record NewsItem(String source, String title, String summary, Instant publishedAt) {
}
