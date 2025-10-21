package com.backend.domains.watchList.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import com.backend.domains.member.domain.Member;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WatchList {

	@Id
	@Column(name = "watch_list_id")
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String productNumber;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private String industry;

	@ManyToOne
	@JoinColumn(name = "member_id")
	private Member member;

	@Builder
	private WatchList(String productNumber, String name, String industry, Member member) {
		this.productNumber = productNumber;
		this.name = name;
		this.industry = industry;
		setMember(member);
	}

	private void setMember(Member member) {
		this.member = member;
		member.getWatchLists().add(this);
	}
}
