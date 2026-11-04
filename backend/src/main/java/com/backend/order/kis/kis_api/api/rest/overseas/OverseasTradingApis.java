package com.backend.order.kis.kis_api.api.rest.overseas;

import com.backend.order.kis.kis_api.api.CommonRestApi;
import com.backend.order.kis.kis_api.api.CommonRestResult;
import com.backend.order.kis.kis_client.api.annotation.Body;
import com.backend.order.kis.kis_client.api.annotation.Header;
import com.backend.order.kis.kis_client.api.annotation.Parameter;
import com.backend.order.kis.kis_client.api.annotation.RestApi;
import com.backend.order.kis.kis_client.api.annotation.auth.AccountRequired;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 해외주식 주문/계좌 API 모음.
 * 실전/모의 TR_ID 는 호출하는 쪽(TradingService)에서 계좌 유형에 맞게 설정한다.
 */
public final class OverseasTradingApis {

	private OverseasTradingApis() {
	}

	/** 해외주식 잔고 [TTTS3012R / VTTS3012R] */
	@NoArgsConstructor
	@Setter
	@AccountRequired(location = AccountRequired.Location.PARAMETER)
	@RestApi(method = RestApi.Method.GET, path = "/uapi/overseas-stock/v1/trading/inquire-balance")
	public static class BalanceApi extends CommonRestApi<BalanceResult> {
		@Header
		private String trId = "TTTS3012R";
		/** NASD 는 미국 전체 */
		@Parameter("OVRS_EXCG_CD")
		private String ovrsExcgCd = "NASD";
		@Parameter("TR_CRCY_CD")
		private String trCrcyCd = "USD";
		@Parameter("CTX_AREA_FK200")
		private String ctxAreaFk200 = "";
		@Parameter("CTX_AREA_NK200")
		private String ctxAreaNk200 = "";

		public BalanceApi(String trId) {
			this.trId = trId;
		}
	}

	@Getter
	public static class BalanceResult extends CommonRestResult {
		private String rtCd;
		private String msgCd;
		private String msg1;
		private Position[] output1;
		private Totals output2;

		@Getter
		public static class Position {
			private String ovrsPdno;
			private String ovrsItemName;
			private String frcrEvluPflsAmt;
			private String evluPflsRt;
			private String pchsAvgPric;
			private String ovrsCblcQty;
			private String ordPsblQty;
			private String frcrPchsAmt1;
			private String ovrsStckEvluAmt;
			private String nowPric2;
			private String trCrcyCd;
			private String ovrsExcgCd;
		}

		@Getter
		public static class Totals {
			private String frcrPchsAmt1;
			private String ovrsRlztPflsAmt;
			private String ovrsTotPfls;
			private String totEvluPflsAmt;
			private String totPftrt;
			private String frcrBuyAmtSmtl1;
		}
	}

	/** 해외주식 매수가능금액조회 [TTTS3007R / VTTS3007R] */
	@NoArgsConstructor
	@Setter
	@AccountRequired(location = AccountRequired.Location.PARAMETER)
	@RestApi(method = RestApi.Method.GET, path = "/uapi/overseas-stock/v1/trading/inquire-psamount")
	public static class PsAmountApi extends CommonRestApi<PsAmountResult> {
		@Header
		private String trId = "TTTS3007R";
		@Parameter("OVRS_EXCG_CD")
		private String ovrsExcgCd;
		@Parameter("OVRS_ORD_UNPR")
		private String ovrsOrdUnpr;
		@Parameter("ITEM_CD")
		private String itemCd;

		public PsAmountApi(String trId, String ovrsExcgCd, String ovrsOrdUnpr, String itemCd) {
			this.trId = trId;
			this.ovrsExcgCd = ovrsExcgCd;
			this.ovrsOrdUnpr = ovrsOrdUnpr;
			this.itemCd = itemCd;
		}
	}

	@Getter
	public static class PsAmountResult extends CommonRestResult {
		private String rtCd;
		private String msgCd;
		private String msg1;
		private Output output;

		@Getter
		public static class Output {
			private String trCrcyCd;
			private String ordPsblFrcrAmt;
			private String frcrOrdPsblAmt1;
			private String ovrsOrdPsblAmt;
			private String maxOrdPsblQty;
			private String ovrsMaxOrdPsblQty;
			private String exrt;
		}
	}

	/** 해외주식 주문 [매수 TTTT1002U/VTTT1002U, 매도 TTTT1006U/VTTT1001U] */
	@NoArgsConstructor
	@Setter
	@AccountRequired
	@RestApi(method = RestApi.Method.POST, path = "/uapi/overseas-stock/v1/trading/order")
	public static class OrderApi extends CommonRestApi<OrderResult> {
		@Header
		private String trId;
		@Body("OVRS_EXCG_CD")
		private String ovrsExcgCd;
		@Body("PDNO")
		private String pdno;
		@Body("ORD_QTY")
		private String ordQty;
		@Body("OVRS_ORD_UNPR")
		private String ovrsOrdUnpr;
		/** 매도 구분 (매도 주문 시 "00") */
		@Body("SLL_TYPE")
		private String sllType = "";
		@Body("ORD_SVR_DVSN_CD")
		private String ordSvrDvsnCd = "0";
		/** 00: 지정가 */
		@Body("ORD_DVSN")
		private String ordDvsn = "00";

		public OrderApi(String trId, String ovrsExcgCd, String pdno, String ordQty, String ovrsOrdUnpr, boolean sell) {
			this.trId = trId;
			this.ovrsExcgCd = ovrsExcgCd;
			this.pdno = pdno;
			this.ordQty = ordQty;
			this.ovrsOrdUnpr = ovrsOrdUnpr;
			this.sllType = sell ? "00" : "";
		}
	}

	@Getter
	public static class OrderResult extends CommonRestResult {
		private String rtCd;
		private String msgCd;
		private String msg1;
		private Output output;

		@Getter
		public static class Output {
			private String krxFwdgOrdOrgno;
			private String odno;
			private String ordTmd;
		}
	}

	/** 해외주식 정정취소주문 [TTTT1004U / VTTT1004U] */
	@NoArgsConstructor
	@Setter
	@AccountRequired
	@RestApi(method = RestApi.Method.POST, path = "/uapi/overseas-stock/v1/trading/order-rvsecncl")
	public static class CancelApi extends CommonRestApi<OrderResult> {
		@Header
		private String trId;
		@Body("OVRS_EXCG_CD")
		private String ovrsExcgCd;
		@Body("PDNO")
		private String pdno;
		@Body("ORGN_ODNO")
		private String orgnOdno;
		/** 01 정정, 02 취소 */
		@Body("RVSE_CNCL_DVSN_CD")
		private String rvseCnclDvsnCd = "02";
		@Body("ORD_QTY")
		private String ordQty = "0";
		@Body("OVRS_ORD_UNPR")
		private String ovrsOrdUnpr = "0";
		@Body("ORD_SVR_DVSN_CD")
		private String ordSvrDvsnCd = "0";

		public CancelApi(String trId, String ovrsExcgCd, String pdno, String orgnOdno, String ordQty) {
			this.trId = trId;
			this.ovrsExcgCd = ovrsExcgCd;
			this.pdno = pdno;
			this.orgnOdno = orgnOdno;
			this.ordQty = ordQty;
		}
	}

	/** 해외주식 주문체결내역 [TTTS3035R / VTTS3035R] */
	@NoArgsConstructor
	@Setter
	@AccountRequired(location = AccountRequired.Location.PARAMETER)
	@RestApi(method = RestApi.Method.GET, path = "/uapi/overseas-stock/v1/trading/inquire-ccnl")
	public static class CcnlApi extends CommonRestApi<CcnlResult> {
		@Header
		private String trId = "TTTS3035R";
		@Parameter("PDNO")
		private String pdno = "%";
		@Parameter("ORD_STRT_DT")
		private String ordStrtDt;
		@Parameter("ORD_END_DT")
		private String ordEndDt;
		/** 00 전체, 01 매도, 02 매수 */
		@Parameter("SLL_BUY_DVSN")
		private String sllBuyDvsn = "00";
		/** 00 전체, 01 체결, 02 미체결 */
		@Parameter("CCLD_NCCS_DVSN")
		private String ccldNccsDvsn = "00";
		@Parameter("OVRS_EXCG_CD")
		private String ovrsExcgCd = "%";
		@Parameter("SORT_SQN")
		private String sortSqn = "DS";
		@Parameter("ORD_DT")
		private String ordDt = "";
		@Parameter("ORD_GNO_BRNO")
		private String ordGnoBrno = "";
		@Parameter("ODNO")
		private String odno = "";
		@Parameter("CTX_AREA_NK200")
		private String ctxAreaNk200 = "";
		@Parameter("CTX_AREA_FK200")
		private String ctxAreaFk200 = "";

		public CcnlApi(String trId, String ordStrtDt, String ordEndDt) {
			this.trId = trId;
			this.ordStrtDt = ordStrtDt;
			this.ordEndDt = ordEndDt;
		}
	}

	@Getter
	public static class CcnlResult extends CommonRestResult {
		private String rtCd;
		private String msgCd;
		private String msg1;
		private Row[] output;

		@Getter
		public static class Row {
			private String ordDt;
			private String odno;
			private String orgnOdno;
			private String sllBuyDvsnCd;
			private String pdno;
			private String prdtName;
			private String ftOrdQty;
			private String ftOrdUnpr3;
			private String ftCcldQty;
			private String ftCcldUnpr3;
			private String ftCcldAmt3;
			private String nccsQty;
			private String prcsStatName;
			private String rjctRson;
			private String ordTmd;
			private String ovrsExcgCd;
			private String trCrcyCd;
		}
	}
}
