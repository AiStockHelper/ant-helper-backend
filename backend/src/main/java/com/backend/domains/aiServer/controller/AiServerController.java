package com.backend.domains.aiServer.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backend.domains.aiServer.service.AiServerService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ai-server")
public class AiServerController {

	private final AiServerService aiServerService;
}
