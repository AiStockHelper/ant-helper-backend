package com.backend.domains.member;

import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.util.encoder.EncryptUtil;
import com.backend.common.util.encoder.PasswordEncoderUtil;
import com.backend.domains.email.service.EmailTokenService;
import com.backend.domains.member.domain.AutoTradeState;
import com.backend.domains.member.domain.LogoutToken;
import com.backend.domains.member.domain.Member;
import com.backend.domains.member.domain.MemberRole;
import com.backend.domains.member.dto.MemberDTO.CreateMemberRequest;
import com.backend.domains.member.repository.LogoutRepository;
import com.backend.domains.member.repository.MemberRepository;
import com.backend.domains.member.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService {

	private final MemberRepository memberRepository;
	private final PasswordEncoderUtil passwordEncoderUtil;

	private final EmailTokenService emailTokenService;
	private final RefreshTokenRepository refreshTokenRepository;
	private final LogoutRepository logoutRepository;

	// 회원가입하기
	@Transactional(rollbackFor = ApiException.class)
	public void createMember(CreateMemberRequest request) {
		// 이메일 검증
		validateDuplicatedEmail(request.getEmail());
		emailTokenService.verifyEmailToken(request.getToken());

		// 비밀번호 암호화
		String password = passwordEncoderUtil.encodePassword(request.getPw());

		// appKey 암호화
		SecretKey appKeySalt = EncryptUtil.generateKey();
		String encryptedAppKey = EncryptUtil.encrypt(request.getAppKey(), appKeySalt);

		// secretKey 암호화
		SecretKey secretKeySalt = EncryptUtil.generateKey();
		String encryptedSecretKey = EncryptUtil.encrypt(request.getSecretKey(), secretKeySalt);

		// 회원 저장
		Member member = Member.builder()
			.email(request.getEmail())
			.pw(password)
			.memberRole(MemberRole.USER) // 기본 권한은 USER
			.appKey(encryptedAppKey)
			.secretKey(encryptedSecretKey)
			.appKeySalt(EncryptUtil.keyToString(appKeySalt))
			.secretKeySalt(EncryptUtil.keyToString(secretKeySalt))
			.autoTradeState(AutoTradeState.OFF)
			.comprehensiveAccountNumber(request.getComprehensiveAccountNumber())
			.accountProductCode(request.getAccountProductCode())
			.build();

		memberRepository.save(member);
	}

	// 이메일 중복 조회
	public void validateDuplicatedEmail(String email) {
		boolean isDuplicated = memberRepository.existsByEmail(email);
		if (isDuplicated) {
			throw ApiException.from(ErrorCode.EMAIL_DUPLICATE);
		}
	}

	// 회원 삭제하기
	@Transactional
	public void deleteMember(Member member) {
		memberRepository.delete(member);
	}

	// AppKey 조회
	public String getDecryptedAppKey(Member member) {
		String appKey = member.getAppKey();
		String appKeySalt = member.getAppKeySalt();

		return EncryptUtil.decrypt(appKey, EncryptUtil.stringToKey(appKeySalt));
	}

	// SecretKey 조회
	public String getDecryptedSecretKey(Member member) {
		String secretKey = member.getSecretKey();
		String secretKeySalt = member.getSecretKeySalt();

		return EncryptUtil.decrypt(secretKey, EncryptUtil.stringToKey(secretKeySalt));
	}

	//Approval Key 저장
	@Transactional
	public void updateApprovalKey(Member member, String approvalKey) {
		member.setApprovalKey(approvalKey);
	}

	//로그아웃
	@Transactional
	public void logoutMember(Member member, String accessToken) {
		Long memberId = member.getId();

		// 회원의 refreshToken 삭제
		refreshTokenRepository.deleteByMemberId(memberId);

		// 같은 accessToken으로 다시 로그인하지 못하도록 블랙리스트에 저장
		logoutRepository.save(new LogoutToken(UUID.randomUUID().toString(), accessToken));
	}

	// 자동 거래 상태 변경
	@Transactional
	public void updateAutoTradeState(Member member, AutoTradeState autoTradeState) {
		member.setAutoTradeState(autoTradeState);
	}

	// 자동 거래를 ON한 모든 멤버 조회
	public List<Member> findAllAutoTradeOnMember() {
		return memberRepository.findAllByAutoTradeState(AutoTradeState.ON);
	}

	// 자동 거래 상태 확인
	public boolean isAutoTradeStateOn(Member member) {
		return member.getAutoTradeState() == AutoTradeState.ON;
	}
}
