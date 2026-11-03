package com.example.accounting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AccountService {

	private final AccountRepository accounts;

	private final AccountingSettings settings;

	private final AuditTrail audit;

	AccountService(AccountRepository accounts, AccountingSettings settings, AuditTrail audit) {
		this.accounts = accounts;
		this.settings = settings;
		this.audit = audit;
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
		boolean active = request.active() != null ? request.active() : account.isActive();
		Map<String, Map<String, Object>> changes = changes(account, request, active);
		account.update(request.code(), request.name(), request.type(), request.parentId(), request.postable(), active);
		if (!changes.isEmpty()) {
			audit.record(AuditAction.ACCOUNT_CHANGE, "account", id, changes);
		}
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

	private Map<String, Map<String, Object>> changes(Account account, AccountRequest request, boolean active) {
		Map<String, Map<String, Object>> changes = new LinkedHashMap<>();
		note(changes, "code", account.getCode(), request.code());
		note(changes, "name", account.getName(), request.name());
		note(changes, "type", account.getType(), request.type());
		if (!Objects.equals(account.getParentId(), request.parentId())) {
			note(changes, "parent", codeOf(account.getParentId()), codeOf(request.parentId()));
		}
		note(changes, "postable", account.isPostable(), request.postable());
		note(changes, "active", account.isActive(), active);
		return changes;
	}

	private static void note(Map<String, Map<String, Object>> changes, String field, Object from, Object to) {
		if (!Objects.equals(from, to)) {
			// Not Map.of: a parent is null at the top level.
			Map<String, Object> change = new LinkedHashMap<>();
			change.put("from", from);
			change.put("to", to);
			changes.put(field, change);
		}
	}

	private String codeOf(Long id) {
		return id == null ? null : get(id).getCode();
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
