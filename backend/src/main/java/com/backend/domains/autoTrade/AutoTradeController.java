package com.backend.domains.autoTrade;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.backend.common.dto.DataResponse;
import lombok.RequiredArgsConstructor;

@Tag(name = "AUTO TRADE API", description = "자동거래에 대한 API입니다.")
@RestController
@RequestMapping("/api/autotrades")
@RequiredArgsConstructor
public class AutoTradeController {

	private final AutoTradeService autoTradeService;

	@GetMapping
	public ResponseEntity<DataResponse<Void>> get() {

		autoTradeService.executeAutoTradeForAllMembers();

		return ResponseEntity.ok(DataResponse.ok());
	}
}
