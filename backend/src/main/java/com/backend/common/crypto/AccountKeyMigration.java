package com.backend.common.crypto;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.backend.common.util.encoder.EncryptUtil;
import com.backend.domains.member.domain.MemberAccount;
import com.backend.domains.member.repository.MemberAccountRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 이전 방식(AES-ECB, 암호화 키가 같은 행에 저장)으로 저장된 앱키·시크릿을 AES-GCM(마스터 키) 형식으로 바꾼다.
 * 여러 번 실행해도 안전하다(이미 바뀐 행은 건너뜀). 바뀐 행의 옛 키 컬럼은 비운다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountKeyMigration {

	private final MemberAccountRepository accounts;
	private final CryptoService crypto;
	private final TransactionTemplate tx;

	@Order(0)
	@EventListener(ApplicationReadyEvent.class)
	public void migrate() {
		int migrated = 0;
		int failed = 0;
		for (MemberAccount a : accounts.findAll()) {
			if (CryptoService.isV2(a.getAppKey()) && CryptoService.isV2(a.getSecretKey())) {
				continue;
			}
			try {
				String appKey = CryptoService.isV2(a.getAppKey()) ? crypto.decrypt(a.getAppKey())
					: EncryptUtil.decrypt(a.getAppKey(), EncryptUtil.stringToKey(a.getAppKeySalt()));
				String secret = CryptoService.isV2(a.getSecretKey()) ? crypto.decrypt(a.getSecretKey())
					: EncryptUtil.decrypt(a.getSecretKey(), EncryptUtil.stringToKey(a.getSecretKeySalt()));
				final Long id = a.getId();
				final String encApp = crypto.encrypt(appKey);
				final String encSecret = crypto.encrypt(secret);
				tx.executeWithoutResult(s -> accounts.findById(id).ifPresent(x -> x.replaceSecrets(encApp, encSecret)));
				migrated++;
			} catch (RuntimeException e) {
				failed++;
				log.error("계좌 {} 의 키를 새 암호화 방식으로 바꾸지 못했습니다: {}", a.getId(), e.getMessage());
			}
		}
		if (migrated > 0 || failed > 0) {
			log.warn("계좌 키 암호화 마이그레이션: 변환 {}건, 실패 {}건", migrated, failed);
		}
	}
}
