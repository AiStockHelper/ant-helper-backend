package com.backend.domains.member;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

import com.backend.common.exception.ApiException;
import com.backend.common.exception.ErrorCode;
import com.backend.common.util.encoder.PasswordEncoderUtil;
import com.backend.config.slice.ServiceTestConfig;
import com.backend.domains.email.EmailService;
import com.backend.domains.member.domain.AutoTradeState;
import com.backend.domains.member.domain.LogoutToken;
import com.backend.domains.member.domain.Member;
import com.backend.domains.member.domain.MemberRole;
import com.backend.domains.member.domain.RefreshToken;
import com.backend.domains.member.dto.MemberDTO.CreateMemberRequest;
import com.backend.domains.member.repository.LogoutRepository;
import com.backend.domains.member.repository.MemberRepository;
import com.backend.domains.member.repository.RefreshTokenRepository;

public class MemberServiceTest extends ServiceTestConfig {

	@Mock
	private MemberRepository memberRepository;

	@Mock
	private PasswordEncoderUtil passwordEncoderUtil;

	@Mock
	private EmailService emailService;

	@Mock
	private RefreshTokenRepository refreshTokenRepository;

	@Mock
	private LogoutRepository logoutRepository;

	@InjectMocks
	private MemberService memberService;

	@Test
	@DisplayName("회원가입을 성공한다.")
	void 회원가입을_성공한다() {
		// given
		CreateMemberRequest request = new CreateMemberRequest("test@example.com", "password", "appKey", "secretKey",
			"comprehensiveAccountNumber", "accountProductCode");

		when(memberRepository.existsByEmail(request.getEmail())).thenReturn(false);
		when(passwordEncoderUtil.encodePassword(request.getPw())).thenReturn("encodedPassword");
		doNothing().when(emailService).validateVerifiedEmail(request.getEmail());
		when(memberRepository.save(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));

		// when
		memberService.createMember(request);

		// then
		verify(memberRepository, times(1)).save(any(Member.class));
		verify(emailService, times(1)).deleteVerifiedEmail(request.getEmail());
	}

	@Test
	@DisplayName("회원가입 시 이메일이 중복되면 예외가 발생한다.")
	void 회원가입_시_이메일이_중복되면_예외가_발생한다() {
		// given
		CreateMemberRequest request = new CreateMemberRequest("duplicate@example.com", "password", "appKey",
			"secretKey", "comprehensiveAccountNumber", "accountProductCode");

		when(memberRepository.existsByEmail(request.getEmail())).thenReturn(true);

		// when, then
		ApiException exception = assertThrows(ApiException.class, () -> memberService.createMember(request));
		assertEquals(ErrorCode.EMAIL_DUPLICATE, exception.getErrorCode());
	}

	@Test
	@DisplayName("회원 삭제를 성공한다.")
	void 회원_삭제를_성공한다() {
		// given
		Member member = Member.builder()
			.email("a@naver.com")
			.pw(passwordEncoderUtil.encodePassword("1234"))
			.accountProductCode("123400")
			.comprehensiveAccountNumber("01")
			.memberRole(MemberRole.USER)
			.autoTradeState(AutoTradeState.OFF)
			.appKey("appKey")
			.appKeySalt("appKeySalt")
			.secretKey("secretKey")
			.secretKeySalt("secretKey")
			.build();
		ReflectionTestUtils.setField(member, "id", 1L);

		// when
		memberService.deleteMember(member);

		// then
		verify(memberRepository, times(1)).delete(member);
	}

	@Test
	@DisplayName("로그아웃을 성공한다.")
	void 로그아웃을_성공한다() {
		// given
		Member member = Member.builder()
			.email("a@naver.com")
			.pw(passwordEncoderUtil.encodePassword("1234"))
			.accountProductCode("123400")
			.comprehensiveAccountNumber("01")
			.memberRole(MemberRole.USER)
			.autoTradeState(AutoTradeState.OFF)
			.appKey("appKey")
			.appKeySalt("appKeySalt")
			.secretKey("secretKey")
			.secretKeySalt("secretKey")
			.build();
		ReflectionTestUtils.setField(member, "id", 1L);
		String accessToken = "accessToken";
		RefreshToken refreshToken = new RefreshToken("refreshTokenValue", member.getId());

		doNothing().when(refreshTokenRepository).deleteByMemberId(member.getId());

		// when
		memberService.logoutMember(member, accessToken);

		// then
		verify(refreshTokenRepository, times(1)).deleteByMemberId(member.getId());
		verify(logoutRepository, times(1)).save(any(LogoutToken.class));
	}
}