package com.backend.common.logging;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppErrorEventRepository extends JpaRepository<AppErrorEvent, Long> {
}
