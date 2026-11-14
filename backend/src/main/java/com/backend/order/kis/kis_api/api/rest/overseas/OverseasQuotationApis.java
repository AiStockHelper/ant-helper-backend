package com.backend.order.kis.kis_api.api.rest.overseas;

import com.backend.order.kis.kis_api.api.CommonRestApi;
import com.backend.order.kis.kis_api.api.CommonRestResult;
import com.backend.order.kis.kis_client.api.annotation.Header;
import com.backend.order.kis.kis_client.api.annotation.Parameter;
import com.backend.order.kis.kis_client.api.annotation.RestApi;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 해외주식 시세 API 모음 (미국 등 해외 거래소).
 * 경로/TR_ID 는 한국투자증권 Open API 해외주식 시세 명세를 따른다.
 */
public final class OverseasQuotationApis {

	private OverseasQuotationApis() {
	}

	/** 해외주식 현재가상세 [HHDFS76200200] */
	@NoArgsConstructor
	@Setter
	@RestApi(method = RestApi.Method.GET, path = "/uapi/overseas-price/v1/quotations/price-detail")
	public static class PriceDetailApi extends CommonRestApi<PriceDetailResult> {
		@Header
		private String trId = "HHDFS76200200";
		@Parameter("AUTH")
		private String auth = "";
		/** 거래소 코드: NAS, NYS, AMS ... */
		@Parameter("EXCD")
		private String excd;
		@Parameter("SYMB")
		private String symb;

		public PriceDetailApi(String excd, String symb) {
			this.excd = excd;
			this.symb = symb;
		}
	}

	@Getter
	public static class PriceDetailResult extends CommonRestResult {
		private String rtCd;
		private String msgCd;
		private String msg1;
		private Output output;

		@Getter
		public static class Output {
			private String rsym;
			private String zdiv;
			private String base;
			private String pvol;
			private String last;
			private String sign;
			private String diff;
			private String rate;
			private String tvol;
			private String tamt;
			private String open;
			private String high;
			private String low;
			private String tomv;
			private String h52p;
			private String l52p;
			private String perx;
			private String pbrx;
			private String epsx;
			private String curr;
		}
	}

	/** 해외주식 기간별시세 [HHDFS76240000] (일/주/월) */
	@NoArgsConstructor
	@Setter
	@RestApi(method = RestApi.Method.GET, path = "/uapi/overseas-price/v1/quotations/dailyprice")
	public static class DailyPriceApi extends CommonRestApi<DailyPriceResult> {
		@Header
		private String trId = "HHDFS76240000";
		@Parameter("AUTH")
		private String auth = "";
		@Parameter("EXCD")
		private String excd;
		@Parameter("SYMB")
		private String symb;
		/** 0: 일, 1: 주, 2: 월 */
		@Parameter("GUBN")
		private String gubn = "0";
		/** 조회 기준일(YYYYMMDD). 이 날짜부터 과거 방향으로 최대 100건. 공백이면 오늘 */
		@Parameter("BYMD")
		private String bymd = "";
		/** 수정주가 반영: 0 미반영, 1 반영 */
		@Parameter("MODP")
		private String modp = "1";

		public DailyPriceApi(String excd, String symb, String gubn, String bymd) {
			this.excd = excd;
			this.symb = symb;
			this.gubn = gubn;
			this.bymd = bymd == null ? "" : bymd;
		}
	}

	@Getter
	public static class DailyPriceResult extends CommonRestResult {
		private String rtCd;
		private String msgCd;
		private String msg1;
		private Row[] output2;

		@Getter
		public static class Row {
			private String xymd;
			private String clos;
			private String open;
			private String high;
			private String low;
			private String tvol;
		}
	}
}
