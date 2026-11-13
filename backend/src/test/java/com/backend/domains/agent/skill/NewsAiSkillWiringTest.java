package com.backend.domains.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import com.backend.common.properties.AppProperties;
import com.backend.domains.chat.ai.LlmGateway;
import com.backend.domains.news.NewsFeedService;

/** 운영에서 앱이 뜰 때 스킬과 뉴스 서비스가 스프링 주입으로 만들어지는지(생성자·@Value 기본값) 확인한다. DB·Redis 없이 이 부분만 검증한다. */
class NewsAiSkillWiringTest {

	@Test
	void skillAndFeedServiceAreCreatedByTheContainerWithDefaults() {
		try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
			ctx.registerBean(AppProperties.class, AppProperties::new);
			ctx.registerBean(LlmGateway.class, () -> mock(LlmGateway.class));
			ctx.register(NewsFeedService.class, NewsAiSkill.class);
			ctx.refresh();

			NewsAiSkill skill = ctx.getBean(NewsAiSkill.class);
			assertThat(skill.id()).isEqualTo("news-ai-trader");
			assertThat(skill).isInstanceOf(Skill.class);
			NewsFeedService feeds = ctx.getBean(NewsFeedService.class);
			assertThat(feeds.feedNames("KRX")).hasSize(6);                    // 설정이 비어 있으면 기본 피드를 쓴다
			assertThat(feeds.feedNames("US")).hasSize(7);
		}
	}

	@Test
	void feedsCanBeOverriddenFromConfiguration() {
		try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
			ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of("app.news.feeds-us", "Only|https://only.example/rss")));
			ctx.registerBean(AppProperties.class, AppProperties::new);
			ctx.registerBean(LlmGateway.class, () -> mock(LlmGateway.class));
			ctx.register(NewsFeedService.class, NewsAiSkill.class);
			ctx.refresh();
			assertThat(ctx.getBean(NewsFeedService.class).feedNames("US")).containsExactly("Only");
		}
	}
}
