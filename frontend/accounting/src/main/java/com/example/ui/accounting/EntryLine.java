package com.example.ui.accounting;

import java.math.BigDecimal;
import java.util.List;

/** One editable row of the entry form. A JavaBean, because ZK's EL cannot read record accessors. */
public class EntryLine {

	private AccountDto account;

	private BigDecimal debit;

	private BigDecimal credit;

	private String memo;

	public AccountDto getAccount() {
		return account;
	}

	public void setAccount(AccountDto account) {
		this.account = account;
	}

	public String getAccountLabel() {
		return account == null ? "" : account.getLabel();
	}

	/** Text matching no single account leaves the line without one, so Save refuses it instead of keeping the old account. */
	public boolean pickByLabel(String text, List<AccountDto> options) {
		account = AccountMatcher.find(text, options).orElse(null);
		return account != null;
	}

	public BigDecimal getDebit() {
		return debit;
	}

	/** Typing an amount on one side clears the other; an entry line has exactly one side. */
	public void setDebit(BigDecimal value) {
		debit = positiveOrNull(value);
		if (debit != null) {
			credit = null;
		}
	}

	public BigDecimal getCredit() {
		return credit;
	}

	public void setCredit(BigDecimal value) {
		credit = positiveOrNull(value);
		if (credit != null) {
			debit = null;
		}
	}

	public String getMemo() {
		return memo;
	}

	public void setMemo(String memo) {
		this.memo = memo;
	}

	public boolean isBlank() {
		return account == null && debit == null && credit == null && (memo == null || memo.isBlank());
	}

	private static BigDecimal positiveOrNull(BigDecimal value) {
		return value == null || value.signum() <= 0 ? null : value;
	}

}
