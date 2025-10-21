package com.backend.domains.member;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.backend.domains.email.EmailService;
import com.backend.domains.member.dto.MemberDTO.CreateMemberRequest;

@DisplayName("MemberController 테스트")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "common"})
class MemberControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private WebApplicationContext context;

	@BeforeEach
	public void mockMvcSetUp() {
		this.mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
	}

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private EmailService emailService;
	@Autowired
	private MemberService memberService;

	@Nested
	@Transactional
	@DisplayName("회원가입")
	class createMember {

		private final String url = "/api/members/signup";

		@Test
		@DisplayName("회원가입 성공 테스트")
		void 회원가입_성공() throws Exception {
			//given
			CreateMemberRequest request = new CreateMemberRequest(
				"a@naver.com",
				"1234",
				"1234",
				"1234");

			String body = objectMapper.writeValueAsString(request);

			//when
			emailService.saveVerifiedEmail(request.getEmail());
			mockMvc.perform(post(url)
					.content(body)
					.contentType(MediaType.APPLICATION_JSON)
					.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isOk());
			//then
		}

		@Test
		@DisplayName("이메일 인증을 하지 않으면 회원가입에 실패한다.")
		void 회원가입_실패1() throws Exception {
			//given
			CreateMemberRequest request = new CreateMemberRequest(
				"b@naver.com",
				"1234",
				"1234",
				"1234");

			String body = objectMapper.writeValueAsString(request);

			//when & then
			mockMvc.perform(post(url)
					.content(body)
					.contentType(MediaType.APPLICATION_JSON)
					.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isUnauthorized());
		}

		@Test
		@DisplayName("이메일 형식이 올바르지 않으면 회원가입에 실패한다.")
		void 회원가입_실패2() throws Exception {
			//given
			CreateMemberRequest request = new CreateMemberRequest(
				"a",
				"1234",
				"1234",
				"1234");

			String body = objectMapper.writeValueAsString(request);

			//when & then
			mockMvc.perform(post(url)
					.content(body)
					.contentType(MediaType.APPLICATION_JSON)
					.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isBadRequest());
		}
	}

	@Test
	void checkLoginIdDuplicate() {
	}

	@Nested
	@Transactional
	@DisplayName("로그인")
	class loginMember {

		private final String url = "/api/members/login";

		@BeforeEach
		void setUpBeforeEach() throws Exception {
			CreateMemberRequest createMemberRequest = new CreateMemberRequest(
				"b@naver.com",
				"1234",
				"1234",
				"1234");

			memberService.saveMember(createMemberRequest);
		}

		@Test
		@DisplayName("저장된 아이디 비번으로 로그인을 성공한다.")
		void 로그인_성공() throws Exception {
			// given
			String email = "b@naver.com";
			String pw = "1234";

			// when & then
			mockMvc.perform(post(url)
					.contentType(MediaType.APPLICATION_FORM_URLENCODED) // form-data 형식으로 전송
					.param("email", email)
					.param("password", pw))
				.andExpect(status().isOk()) // 성공 시 200 OK 예상
				.andExpect(content().string("accessToken"));
		}

		@Test
		@DisplayName("비밀번호가 다르면 로그인 실패한다.")
		void 로그인_실패() throws Exception {
			// given
			String email = "b@naver.com";
			String pw = "wrongPassword";

			// when
			MvcResult result = mockMvc.perform(post(url)
					.contentType(MediaType.APPLICATION_FORM_URLENCODED) // form-data 형식으로 전송
					.param("email", email)
					.param("password", pw))
				.andExpect(status().isOk())
				.andReturn();

			// then
			String responseBody = result.getResponse().getContentAsString();
			assertFalse(responseBody.contains("accessToken"));
		}
	}

	@Test
	void deleteMember() {
	}

	@Test
	void logoutMember() {
	}

	@Test
	void getWatchList() {
	}

	@Test
	void addWatchList() {
	}

	@Test
	void deleteWatchList() {
	}

	@Test
	void getPurchases() {
	}

	@Test
	void addPurchase() {
	}

	@Test
	void deletePurchase() {
	}
}