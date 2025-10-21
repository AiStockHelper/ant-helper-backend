package com.backend.common.security.filter.loginFilter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.backend.common.util.encoder.PasswordEncoderUtil;
import com.backend.domains.member.domain.AutoTradeState;
import com.backend.domains.member.domain.Member;
import com.backend.domains.member.domain.MemberRole;
import com.backend.domains.member.repository.MemberRepository;

@SpringBootTest
@ActiveProfiles("test-env")
@AutoConfigureMockMvc
@Transactional
class LoginFilterTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	MemberRepository memberRepository;

	@Autowired
	PasswordEncoderUtil passwordEncoderUtil;

	@Test
	public void 로그인() throws Exception {
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
		memberRepository.save(member);

		// when && then
		MultiValueMap<String, String> request = new LinkedMultiValueMap<>();
		request.add("email", "a@naver.com");
		request.add("pw", "1234");

		mockMvc.perform(post("/api/members/login")
				.params(request)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.accessToken").exists());
	}

	@Test
	public void 로그인_실패() throws Exception {
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
		memberRepository.save(member);

		// when && then
		MultiValueMap<String, String> request = new LinkedMultiValueMap<>();
		request.add("email", "a@naver.com");
		request.add("pw", "다른 비밀번호");

		mockMvc.perform(post("/api/members/login")
				.params(request)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.accessToken").doesNotExist());
	}
}