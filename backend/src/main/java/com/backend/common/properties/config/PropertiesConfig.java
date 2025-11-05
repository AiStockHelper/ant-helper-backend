package com.backend.common.properties.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.backend.common.properties.AiServerProperties;
import com.backend.common.properties.CorsProperties;
import com.backend.common.properties.EmailProperties;
import com.backend.common.properties.JwtProperties;
import com.backend.common.properties.PublicDataPortalProperties;
import com.backend.common.properties.RedisProperties;
import com.backend.common.properties.SecurityProperties;

@Configuration
@EnableConfigurationProperties(value = {
	RedisProperties.class,
	SecurityProperties.class,
	JwtProperties.class,
	CorsProperties.class,
	EmailProperties.class,
	AiServerProperties.class,
	PublicDataPortalProperties.class,
})
public class PropertiesConfig {
}

