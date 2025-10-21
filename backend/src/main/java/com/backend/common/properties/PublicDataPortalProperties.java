package com.backend.common.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "public-data-portal")
public class PublicDataPortalProperties {
	private String url;
	private String keywordsKey;
}
