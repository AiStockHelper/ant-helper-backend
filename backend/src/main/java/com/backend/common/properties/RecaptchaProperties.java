package com.backend.common.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "recaptcha")
public class RecaptchaProperties {

	private String secret;
	private String url;
}
