package com.backend.domains.stock.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import com.backend.domains.stock.domain.DomesticStock;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

public class StockDTO {

	@Getter
	@Setter
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	@Builder
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static class OrderStockRequest {

		@JsonProperty("PDNO")
		private String PDNO; // 종목코드 (6자리, 최대 12자리)

		@JsonProperty("ORD_DVSN")
		private String ORD_DVSN; // 주문구분 (2자리)

		@JsonProperty("ORD_QTY")
		private String ORD_QTY; // 주문수량 (최대 10자리)

		@JsonProperty("ORD_UNPR")
		private String ORD_UNPR; // 주문단가 (최대 19자리)
	}

	@Getter
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	public static class KisBaseResponse {

		private String rt_cd; // 응답 코드
		private String msg_cd; // 메시지 코드
		private String msg1; // 메시지 내용
	}

	@Getter
	@Setter
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	@Builder
	public static class KisOrderStockRequest {

		@JsonProperty("CANO")
		private String CANO; // 종합계좌번호 (8자리)

		@JsonProperty("ACNT_PRDT_CD")
		private String ACNT_PRDT_CD; // 계좌상품코드 (2자리)

		@JsonProperty("PDNO")
		private String PDNO; // 종목코드 (6자리, 최대 12자리)

		@JsonProperty("ORD_DVSN")
		private String ORD_DVSN; // 주문구분 (2자리)

		@JsonProperty("ORD_QTY")
		private String ORD_QTY; // 주문수량 (최대 10자리)

		@JsonProperty("ORD_UNPR")
		private String ORD_UNPR; // 주문단가 (최대 19자리)
	}

	@Getter
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	public static class OrderStockResponse extends KisBaseResponse {
		// 출력 데이터
		private Output output;

		@Getter
		@NoArgsConstructor(access = AccessLevel.PRIVATE)
		public static class Output {

			// KRX 전달 주문 원본 번호
			private String KRX_FWDG_ORD_ORGNO;

			// 주문 번호
			private String ODNO;

			// 주문 시간
			private String ORD_TMD;
		}
	}

	@Getter
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	public static class GetStockBalanceResponse extends KisBaseResponse {

		private String ctx_area_fk100; // 연속조회검색조건100
		private String ctx_area_nk100; // 연속조회키100
		private List<Output1> output1; // 응답상세1
		private List<Output2> output2; // 응답상세2

		@Getter
		@NoArgsConstructor(access = AccessLevel.PRIVATE)
		public static class Output1 {
			private String pdno; // 상품번호 (종목번호 뒷 6자리)
			private String prdt_name; // 상품명 (종목명)
			private String trad_dvsn_name; // 매매구분명 (매수매도구분)
			private String bfdy_buy_qty; // 전일매수수량
			private String bfdy_sll_qty; // 전일매도수량
			private String thdt_buyqty; // 금일매수수량
			private String thdt_sll_qty; // 금일매도수량
			private String hldg_qty; // 보유수량
			private String ord_psbl_qty; // 주문가능수량
			private String pchs_avg_pric; // 매입평균가격 (매입금액 / 보유수량)
			private String pchs_amt; // 매입금액
			private String prpr; // 현재가
			private String evlu_amt; // 평가금액
			private String evlu_pfls_amt; // 평가손익금액 (평가금액 - 매입금액)
			private String evlu_pfls_rt; // 평가손익율
			private String evlu_erng_rt; // 평가수익율 (미사용항목, 0으로 출력)
			private String loan_dt; // 대출일자 (INQR_DVSN(조회구분)을 01(대출일별)로 설정해야 값이 나옴)
			private String loan_amt; // 대출금액
			private String stln_slng_chgs; // 대주매각대금
			private String expd_dt; // 만기일자
			private String fltt_rt; // 등락율
			private String bfdy_cprs_icdc; // 전일대비증감
			private String item_mgna_rt_name; // 종목증거금율명
			private String grta_rt_name; // 보증금율명
			private String sbst_pric; // 대용가격 (증권매매의 위탁보증금으로서 현금 대신에 사용되는 유가증권 가격)
			private String stck_loan_unpr; // 주식대출단가
		}

		@Getter
		@NoArgsConstructor(access = AccessLevel.PRIVATE)
		public static class Output2 {
			private String dnca_tot_amt; // 예수금총금액 (예수금)
			private String nxdy_excc_amt; // 익일정산금액 (D+1 예수금)
			private String prvs_rcdl_excc_amt; // 가수도정산금액 (D+2 예수금)
			private String cma_evlu_amt; // CMA평가금액
			private String bfdy_buy_amt; // 전일매수금액
			private String thdt_buy_amt; // 금일매수금액
			private String nxdy_auto_rdpt_amt; // 익일자동상환금액
			private String bfdy_sll_amt; // 전일매도금액
			private String thdt_sll_amt; // 금일매도금액
			private String d2_auto_rdpt_amt; // D+2자동상환금액
			private String bfdy_tlex_amt; // 전일제비용금액
			private String thdt_tlex_amt; // 금일제비용금액
			private String tot_loan_amt; // 총대출금액
			private String scts_evlu_amt; // 유가평가금액
			private String tot_evlu_amt; // 총평가금액 (유가증권 평가금액 합계금액 + D+2 예수금)
			private String nass_amt; // 순자산금액
			private String fncg_gld_auto_rdpt_yn; // 융자금자동상환여부 (보유현금에 대한 융자금만 차감여부)
			private String pchs_amt_smtl_amt; // 매입금액합계금액
			private String evlu_amt_smtl_amt; // 평가금액합계금액 (유가증권 평가금액 합계금액)
			private String evlu_pfls_smtl_amt; // 평가손익합계금액
			private String tot_stln_slng_chgs; // 총대주매각대금
			private String bfdy_tot_asst_evlu_amt; // 전일총자산평가금액
			private String asst_icdc_amt; // 자산증감액
			private String asst_icdc_erng_rt; // 자산증감수익율 (데이터 미제공)
		}
	}

	@Getter
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	public static class GetStockBalanceRealizedProfitAndLossResponse extends KisBaseResponse {

		private List<Output1> output1; // 응답상세1 (Object Array)
		private List<Output2> output2; // 응답상세2 (Object)

		@Getter
		@NoArgsConstructor(access = AccessLevel.PRIVATE)
		public static class Output1 {
			private String pdno; // 상품번호
			private String prdt_name; // 상품명
			private String trad_dvsn_name; // 매매구분명
			private String bfdy_buy_qty; // 전일매수수량
			private String bfdy_sll_qty; // 전일매도수량
			private String thdt_buyqty; // 금일매수수량
			private String thdt_sll_qty; // 금일매도수량
			private String hldg_qty; // 보유수량
			private String ord_psbl_qty; // 주문가능수량
			private String pchs_avg_pric; // 매입평균가격
			private String pchs_amt; // 매입금액
			private String prpr; // 현재가
			private String evlu_amt; // 평가금액
			private String evlu_pfls_amt; // 평가손익금액
			private String evlu_pfls_rt; // 평가손익율
			private String evlu_erng_rt; // 평가수익율
			private String loan_dt; // 대출일자
			private String loan_amt; // 대출금액
			private String stln_slng_chgs; // 대주매각대금
			private String expd_dt; // 만기일자
			private String stck_loan_unpr; // 주식대출단가
			private String bfdy_cprs_icdc; // 전일대비증감
			private String fltt_rt; // 등락율
		}

		@Getter
		@NoArgsConstructor(access = AccessLevel.PRIVATE)
		public static class Output2 {
			private String dnca_tot_amt; // 예수금총금액
			private String nxdy_excc_amt; // 익일정산금액
			private String prvs_rcdl_excc_amt; // 가수도정산금액
			private String cma_evlu_amt; // CMA평가금액
			private String bfdy_buy_amt; // 전일매수금액
			private String thdt_buy_amt; // 금일매수금액
			private String nxdy_auto_rdpt_amt; // 익일자동상환금액
			private String bfdy_sll_amt; // 전일매도금액
			private String thdt_sll_amt; // 금일매도금액
			private String d2_auto_rdpt_amt; // D+2자동상환금액
			private String bfdy_tlex_amt; // 전일제비용금액
			private String thdt_tlex_amt; // 금일제비용금액
			private String tot_loan_amt; // 총대출금액
			private String scts_evlu_amt; // 유가평가금액
			private String tot_evlu_amt; // 총평가금액
			private String nass_amt; // 순자산금액
			private String fncg_gld_auto_rdpt_yn; // 융자금자동상환여부
			private String pchs_amt_smtl_amt; // 매입금액합계금액
			private String evlu_amt_smtl_amt; // 평가금액합계금액
			private String evlu_pfls_smtl_amt; // 평가손익합계금액
			private String tot_stln_slng_chgs; // 총대주매각대금
			private String bfdy_tot_asst_evlu_amt; // 전일총자산평가금액
			private String asst_icdc_amt; // 자산증감액
			private String asst_icdc_erng_rt; // 자산증감수익율
			private String rlzt_pfls; // 실현손익
			private String rlzt_erng_rt; // 실현수익율
			private String real_evlu_pfls; // 실평가손익
			private String real_evlu_pfls_erng_rt; // 실평가손익수익율
		}
	}

	@Getter
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	public static class GetStockPriceResponse extends KisBaseResponse {

		private Output output; // 응답상세

		@Getter
		@NoArgsConstructor(access = AccessLevel.PRIVATE)
		@JsonInclude(JsonInclude.Include.NON_NULL)
		public static class Output {

			@Setter
			private String name; // 주식 이름
			private String iscd_stat_cls_code; // 종목 상태 구분 코드
			private String marg_rate; // 증거금 비율
			private String rprs_mrkt_kor_name; // 대표 시장 한글 명
			private String new_hgpr_lwpr_cls_code; // 신 고가 저가 구분 코드
			private String bstp_kor_isnm; // 업종 한글 종목명
			private String temp_stop_yn; // 임시 정지 여부
			private String oprc_rang_cont_yn; // 시가 범위 연장 여부
			private String clpr_rang_cont_yn; // 종가 범위 연장 여부
			private String crdt_able_yn; // 신용 가능 여부
			private String grmn_rate_cls_code; // 보증금 비율 구분 코드
			private String elw_pblc_yn; // ELW 발행 여부
			private String stck_prpr; // 주식 현재가
			private String prdy_vrss; // 전일 대비
			private String prdy_vrss_sign; // 전일 대비 부호
			private String prdy_ctrt; // 전일 대비율
			private String acml_tr_pbmn; // 누적 거래 대금
			private String acml_vol; // 누적 거래량
			private String prdy_vrss_vol_rate; // 전일 대비 거래량 비율
			private String stck_oprc; // 주식 시가
			private String stck_hgpr; // 주식 최고가
			private String stck_lwpr; // 주식 최저가
			private String stck_mxpr; // 주식 상한가
			private String stck_llam; // 주식 하한가
			private String stck_sdpr; // 주식 기준가
			private String wghn_avrg_stck_prc; // 가중 평균 주식 가격
			private String hts_frgn_ehrt; // HTS 외국인 소진율
			private String frgn_ntby_qty; // 외국인 순매수 수량
			private String pgtr_ntby_qty; // 프로그램매매 순매수 수량
			private String pvt_scnd_dmrs_prc; // 피벗 2차 디저항 가격
			private String pvt_frst_dmrs_prc; // 피벗 1차 디저항 가격
			private String pvt_pont_val; // 피벗 포인트 값
			private String pvt_frst_dmsp_prc; // 피벗 1차 디지지 가격
			private String pvt_scnd_dmsp_prc; // 피벗 2차 디지지 가격
			private String dmrs_val; // 디저항 값
			private String dmsp_val; // 디지지 값
			private String cpfn; // 자본금
			private String rstc_wdth_prc; // 제한 폭 가격
			private String stck_fcam; // 주식 액면가
			private String stck_sspr; // 주식 대용가
			private String aspr_unit; // 호가단위
			private String hts_deal_qty_unit_val; // HTS 매매 수량 단위 값
			private String lstn_stcn; // 상장 주수
			private String hts_avls; // HTS 시가총액
			private String per; // PER
			private String pbr; // PBR
			private String stac_month; // 결산 월
			private String vol_tnrt; // 거래량 회전율
			private String eps; // EPS
			private String bps; // BPS
			private String d250_hgpr; // 250일 최고가
			private String d250_hgpr_date; // 250일 최고가 일자
			private String d250_hgpr_vrss_prpr_rate; // 250일 최고가 대비 현재가 비율
			private String d250_lwpr; // 250일 최저가
			private String d250_lwpr_date; // 250일 최저가 일자
			private String d250_lwpr_vrss_prpr_rate; // 250일 최저가 대비 현재가 비율
			private String stck_dryy_hgpr; // 주식 연중 최고가
			private String dryy_hgpr_vrss_prpr_rate; // 연중 최고가 대비 현재가 비율
			private String dryy_hgpr_date; // 연중 최고가 일자
			private String stck_dryy_lwpr; // 주식 연중 최저가
			private String dryy_lwpr_vrss_prpr_rate; // 연중 최저가 대비 현재가 비율
			private String dryy_lwpr_date; // 연중 최저가 일자
			private String w52_hgpr; // 52주일 최고가
			private String w52_hgpr_vrss_prpr_ctrt; // 52주일 최고가 대비 현재가 대비
			private String w52_hgpr_date; // 52주일 최고가 일자
			private String w52_lwpr; // 52주일 최저가
			private String w52_lwpr_vrss_prpr_ctrt; // 52주일 최저가 대비 현재가 대비
			private String w52_lwpr_date; // 52주일 최저가 일자
			private String whol_loan_rmnd_rate; // 전체 융자 잔고 비율
			private String ssts_yn; // 공매도가능여부
			private String stck_shrn_iscd; // 주식 단축 종목코드
			private String fcam_cnnm; // 액면가 통화명
			private String cpfn_cnnm; // 자본금 통화명
			private String apprch_rate; // 접근도
			private String frgn_hldn_qty; // 외국인 보유 수량
			private String vi_cls_code; // VI적용구분코드
			private String ovtm_vi_cls_code; // 시간외단일가VI적용구분코드
			private String last_ssts_cntg_qty; // 최종 공매도 체결 수량
			private String invt_caful_yn; // 투자유의여부
			private String mrkt_warn_cls_code; // 시장경고코드
			private String short_over_yn; // 단기과열여부
			private String sltr_yn; // 정리매매여부
		}
	}

	@Getter
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	public static class GetSuggestedKeywordsDTO {

		private Response response;

		// Getters and Setters

		@Getter
		@NoArgsConstructor(access = AccessLevel.PRIVATE)
		public static class Response {
			private Header header;
			private Body body;

			// Getters and Setters

			@Getter
			@NoArgsConstructor(access = AccessLevel.PRIVATE)
			public static class Header {
				private String resultCode;
				private String resultMsg;

				// Getters and Setters
			}

			@Getter
			@NoArgsConstructor(access = AccessLevel.PRIVATE)
			public static class Body {
				private int numOfRows;
				private int pageNo;
				private int totalCount;
				private Items items;

				@Getter
				@NoArgsConstructor(access = AccessLevel.PRIVATE)
				public static class Items {
					private List<Item> item;

					@Getter
					@NoArgsConstructor(access = AccessLevel.PRIVATE)
					public static class Item {
						private String basDt;

						@NotBlank
						private String srtnCd;
						private String isinCd;
						private String mrktCtg;

						@NotBlank
						private String itmsNm;
						private String crno;
						private String corpNm;

						public static DomesticStock toEntity(Item item) {
							return DomesticStock.builder()
								.productNumber(item.getSrtnCd().substring(1))
								.name(item.getItmsNm())
								.build();
						}
					}
				}
			}
		}
	}

	@Getter
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	@Builder
	public static class FindSuggestedKeywordResponse {
		private String productNumber;
		private String name;

		public static FindSuggestedKeywordResponse from(DomesticStock domesticStock) {
			return FindSuggestedKeywordResponse.builder()
				.productNumber(domesticStock.getProductNumber())
				.name(domesticStock.getName())
				.build();
		}
	}

	@Getter
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	public static class FindDomesticStockPriceChartResponse extends KisBaseResponse {

		private Output1 output1;  // 응답 상세
		private List<Output2> output2;  // 일별 데이터 배열

		@Getter
		@NoArgsConstructor(access = AccessLevel.PRIVATE)
		public static class Output1 {
			private String prdy_vrss;  // 전일 대비
			private String prdy_vrss_sign;  // 전일 대비 부호
			private String prdy_ctrt;  // 전일 대비율
			private String stck_prdy_clpr;  // 주식 전일 종가
			private String acml_vol;  // 누적 거래량
			private String acml_tr_pbmn;  // 누적 거래 대금
			private String hts_kor_isnm;  // HTS 한글 종목명
			private String stck_prpr;  // 주식 현재가
			private String stck_shrn_iscd;  // 주식 단축 종목 코드
			private String prdy_vol;  // 전일 거래량
			private String stck_mxpr;  // 상한가
			private String stck_llam;  // 하한가
			private String stck_oprc;  // 시가
			private String stck_hgpr;  // 최고가
			private String stck_lwpr;  // 최저가
			private String stck_prdy_oprc;  // 주식 전일 시가
			private String stck_prdy_hgpr;  // 주식 전일 최고가
			private String stck_prdy_lwpr;  // 주식 전일 최저가
			private String askp;  // 매도호가
			private String bidp;  // 매수호가
			private String prdy_vrss_vol;  // 전일 대비 거래량
			private String vol_tnrt;  // 거래량 회전율
			private String stck_fcam;  // 주식 액면가
			private String lstn_stcn;  // 상장 주수
			private String cpfn;  // 자본금
			private String hts_avls;  // HTS 시가총액
			private String per;  // PER
			private String eps;  // EPS
			private String pbr;  // PBR
			private String itewhol_loan_rmnd_ratem;  // 전체 융자 잔고 비율
		}

		@Getter
		@NoArgsConstructor(access = AccessLevel.PRIVATE)
		public static class Output2 {
			private String stck_bsop_date;  // 주식 영업 일자
			private String stck_clpr;  // 주식 종가
			private String stck_oprc;  // 주식 시가
			private String stck_hgpr;  // 주식 최고가
			private String stck_lwpr;  // 주식 최저가
			private String acml_vol;  // 누적 거래량
			private String acml_tr_pbmn;  // 누적 거래 대금
			private String flng_cls_code;  // 락 구분 코드
			private String prtt_rate;  // 분할 비율
			private String mod_yn;  // 분할변경여부 (Y/N)
			private String prdy_vrss_sign;  // 전일 대비 부호
			private String prdy_vrss;  // 전일 대비
			private String revl_issu_reas;  // 재평가사유코드
		}
	}

	@Getter
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	public static class GetDomesticStockPopularChartResponse extends KisBaseResponse {
		private List<Output> output;

		@Getter
		@NoArgsConstructor(access = AccessLevel.PRIVATE)
		public static class Output {
			private String htsKorIsnm;  // 종목명
			private String mkscShrnIscd;  // 종목 코드
			private String dataRank;  // 데이터 순위
			private String stckPrpr;  // 현재가
			private String prdyVrssSign;  // 전일 대비 기호
			private String prdyVrss;  // 전일 대비 가격
			private String prdyCtrt;  // 전일 대비 등락률
			private String acmlVol;  // 누적 거래량
			private String prdyVol;  // 전일 거래량
			private String lstnStcn;  // 상장 주식 수
			private String avrgVol;  // 평균 거래량
			private String nBefrClprVrssPrprRate;  // 전날 종가 대비 현재가 등락률
			private String volInrt;  // 거래량 증가율
			private String volTnrt;  // 거래량 회전율
			private String ndayVolTnrt;  // N일 거래량 회전율
			private String avrgTrPbmn;  // 평균 거래 대금
			private String trPbmnTnrt;  // 거래 대금 회전율
			private String ndayTrPbmnTnrt;  // N일 거래 대금 회전율
			private String acmlTrPbmn;  // 누적 거래 대금
		}
	}

	@Getter
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	@AllArgsConstructor
	public static class FindDomesticStockPopularChartResponse {
		private String name;
		private String productNumber;
	}
}

