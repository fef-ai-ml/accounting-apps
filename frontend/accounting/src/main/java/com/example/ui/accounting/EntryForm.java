package com.example.ui.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** What the entry form holds and the rules that need no ZK: totals, the Balance button, row checks. */
public class EntryForm {

	private LocalDate date;

	private String reference;

	private String description;

	private final List<EntryLine> lines = new ArrayList<>();

	public static EntryForm blank(LocalDate date) {
		EntryForm form = new EntryForm();
		form.date = date;
		form.addLine();
		form.addLine();
		return form;
	}

	public static EntryForm from(EntryDto entry, Map<Long, AccountDto> accounts) {
		EntryForm form = new EntryForm();
		form.date = entry.date();
		form.reference = entry.reference();
		form.description = entry.description();
		for (LineDto dto : entry.lines()) {
			EntryLine line = new EntryLine();
			line.setAccount(accounts.get(dto.accountId()));
			line.setDebit(dto.debit());
			line.setCredit(dto.credit());
			line.setMemo(dto.memo());
			form.lines.add(line);
		}
		return form;
	}

	public EntryLine addLine() {
		EntryLine line = new EntryLine();
		lines.add(line);
		return line;
	}

	public BigDecimal totalDebit() {
		return sum(lines, EntryLine::getDebit);
	}

	public BigDecimal totalCredit() {
		return sum(lines, EntryLine::getCredit);
	}

	/** Debit minus credit. */
	public BigDecimal difference() {
		return totalDebit().subtract(totalCredit());
	}

	public boolean isBalanced() {
		return difference().signum() == 0 && totalDebit().signum() > 0;
	}

	/** Makes the whole entry balance by changing only this line. */
	public void balance(EntryLine line) {
		List<EntryLine> others = lines.stream().filter(other -> other != line).toList();
		BigDecimal gap = sum(others, EntryLine::getDebit).subtract(sum(others, EntryLine::getCredit));
		line.setDebit(null);
		line.setCredit(null);
		if (gap.signum() > 0) {
			line.setCredit(gap);
		}
		else if (gap.signum() < 0) {
			line.setDebit(gap.negate());
		}
	}

	/** The first thing stopping a save, in words a bookkeeper can act on, or null. */
	public String problem() {
		if (date == null) {
			return "Enter a date";
		}
		if (description == null || description.isBlank()) {
			return "Enter a description";
		}
		for (int i = 0; i < lines.size(); i++) {
			EntryLine line = lines.get(i);
			if (line.isBlank()) {
				continue;
			}
			if (line.getAccount() == null) {
				return "Line " + (i + 1) + ": choose an account";
			}
			if (line.getDebit() == null && line.getCredit() == null) {
				return "Line " + (i + 1) + ": enter a debit or a credit";
			}
		}
		return null;
	}

	public EntryRequest toRequest(Long version) {
		List<LineRequest> requests = lines.stream().filter(line -> !line.isBlank())
				.map(line -> new LineRequest(line.getAccount().id(), line.getDebit(), line.getCredit(), line.getMemo()))
				.toList();
		return new EntryRequest(date, reference, description.strip(), requests, version);
	}

	/** Compares what is on screen with what was saved, so Post can refuse unsaved edits. */
	public String snapshot() {
		String rows = lines.stream().filter(line -> !line.isBlank())
				.map(line -> line.getAccountLabel() + "|" + plain(line.getDebit()) + "|" + plain(line.getCredit()) + "|"
						+ orEmpty(line.getMemo()))
				.collect(Collectors.joining("\n"));
		return date + "\n" + orEmpty(reference) + "\n" + orEmpty(description) + "\n" + rows;
	}

	public LocalDate getDate() {
		return date;
	}

	public void setDate(LocalDate date) {
		this.date = date;
	}

	public String getReference() {
		return reference;
	}

	public void setReference(String reference) {
		this.reference = reference;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public List<EntryLine> getLines() {
		return lines;
	}

	private static BigDecimal sum(List<EntryLine> lines, Function<EntryLine, BigDecimal> side) {
		return lines.stream().map(side).filter(amount -> amount != null).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	private static String plain(BigDecimal amount) {
		return amount == null ? "" : amount.stripTrailingZeros().toPlainString();
	}

	private static String orEmpty(String text) {
		return text == null ? "" : text;
	}

}
