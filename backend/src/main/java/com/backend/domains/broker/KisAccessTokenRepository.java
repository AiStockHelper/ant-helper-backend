package com.backend.domains.broker;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface KisAccessTokenRepository extends CrudRepository<KisAccessToken, Long> {
}
