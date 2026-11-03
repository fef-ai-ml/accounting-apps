package com.example.accounting;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AccountService {

	private final AccountRepository accounts;

	private final AccountingSettings settings;

	AccountService(AccountRepository accounts, AccountingSettings settings) {
		this.accounts = accounts;
		this.settings = settings;
	}

	@Transactional(readOnly = true)
	public List<AccountResponse> list() {
		return accounts.findAll(Sort.by("code")).stream().map(AccountResponse::from).toList();
	}

	public AccountResponse create(AccountRequest request) {
		settings.require();
		checkCodeIsFree(request.code());
		checkParent(null, request.parentId());
		return AccountResponse.from(accounts.save(new Account(request.code(), request.name(), request.type(),
				request.parentId(), request.postable(), request.activeOrDefault())));
	}

	public AccountResponse update(long id, AccountRequest request) {
		settings.require();
		Account account = get(id);
		if (!account.getCode().equals(request.code())) {
			checkCodeIsFree(request.code());
		}
		checkParent(id, request.parentId());
		if (request.postable() && accounts.existsByParentId(id)) {
			throw AccountingException.badRequest("validation", "An account with children cannot be postable");
		}
		boolean structureChanges = account.getType() != request.type() || account.isPostable() != request.postable();
		if (structureChanges && accounts.hasLiveLines(id)) {
			throw AccountingException.conflict("account-in-use",
					"The type and postability of an account with journal lines cannot change");
		}
		// Unlike create, a PUT without active keeps the current value rather than reactivating the account.
		account.update(request.code(), request.name(), request.type(), request.parentId(), request.postable(),
				request.active() != null ? request.active() : account.isActive());
		return AccountResponse.from(account);
	}

	public void delete(long id) {
		Account account = get(id);
		if (accounts.hasLines(id) || accounts.existsByParentId(id)) {
			throw AccountingException.conflict("account-in-use",
					"An account used by journal lines, even in deleted drafts, or with child accounts cannot be deleted");
		}
		accounts.delete(account);
	}

	private Account get(long id) {
		return accounts.findById(id).orElseThrow(() -> new AccountingNotFoundException("Account " + id));
	}

	private void checkCodeIsFree(String code) {
		if (accounts.existsByCode(code)) {
			throw AccountingException.conflict("account-code-exists", "Account code " + code + " already exists");
		}
	}

	/** The parent must exist, be a non-postable header, and not be the account itself or one of its descendants. */
	private void checkParent(Long id, Long parentId) {
		Long current = parentId;
		boolean first = true;
		while (current != null) {
			if (current.equals(id)) {
				throw AccountingException.badRequest("validation", "An account cannot be its own ancestor");
			}
			Long lookedUp = current;
			Account parent = accounts.findById(lookedUp).orElseThrow(
					() -> AccountingException.badRequest("validation", "Parent account " + lookedUp + " does not exist"));
			if (first && parent.isPostable()) {
				throw AccountingException.badRequest("validation", "A parent account cannot be postable");
			}
			first = false;
			current = parent.getParentId();
		}
	}

}
