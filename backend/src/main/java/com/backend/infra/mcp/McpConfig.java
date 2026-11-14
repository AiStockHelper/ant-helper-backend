package com.backend.infra.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpConfig {

	/** MCP 서버가 노출할 도구. 계좌/주문 도구는 포함하지 않는다. */
	@Bean
	public ToolCallbackProvider mcpToolCallbackProvider(McpTools tools) {
		return MethodToolCallbackProvider.builder().toolObjects(tools).build();
	}
}
