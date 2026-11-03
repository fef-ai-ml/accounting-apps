package com.example.ui.accounting;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.example.ui.common.UiModule;
import com.example.ui.common.ZkSupport;

/** Entry point of the accounting UI module. A deployable UI app includes it with {@code @Import}. */
@Configuration(proxyBeanMethods = false)
@Import(ZkSupport.class)
@ComponentScan(basePackageClasses = AccountingUiModule.class)
public class AccountingUiModule {

	static final String ID = "accounting";

	private static final String READ = "accounting:read";

	@Bean
	UiModule accountingJournalUi() {
		return UiModule.embedded("accounting-journal", "Journal", 20, "~./accounting/journal.zul").withPermission(READ);
	}

	@Bean
	UiModule accountingAccountsUi() {
		return UiModule.embedded("accounting-accounts", "Accounts", 21, "~./accounting/accounts.zul").withPermission(READ);
	}

	@Bean
	UiModule accountingPeriodsUi() {
		return UiModule.embedded("accounting-periods", "Periods", 22, "~./accounting/periods.zul").withPermission(READ);
	}

	@Bean
	UiModule accountingReportsUi() {
		return UiModule.embedded("accounting-reports", "Reports", 23, "~./accounting/reports.zul").withPermission(READ);
	}

	@Bean
	UiModule accountingAuditUi() {
		return UiModule.embedded("accounting-audit", "Audit", 24, "~./accounting/audit.zul").withPermission("accounting:audit-read");
	}

}
