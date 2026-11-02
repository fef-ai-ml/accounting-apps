package com.example.accounting;

import java.util.stream.IntStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PeriodService {

	private final FiscalPeriodRepository periods;

	PeriodService(FiscalPeriodRepository periods) {
		this.periods = periods;
	}

	public void openYear(int year) {
		periods.saveAll(IntStream.rangeClosed(1, 12).mapToObj(month -> new FiscalPeriod(year, month)).toList());
	}

}
