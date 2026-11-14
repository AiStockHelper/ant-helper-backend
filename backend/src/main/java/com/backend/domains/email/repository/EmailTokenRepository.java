package com.backend.domains.email.repository;

import org.springframework.data.repository.CrudRepository;

import com.backend.domains.email.domain.EmailToken;

public interface EmailTokenRepository extends CrudRepository<EmailToken, String> {
}
