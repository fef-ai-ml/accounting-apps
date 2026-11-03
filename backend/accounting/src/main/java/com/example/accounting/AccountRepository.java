package com.example.accounting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AccountRepository extends JpaRepository<Account, Long> {

	boolean existsByCode(String code);

	boolean existsByParentId(Long parentId);

	/** Removed lines count: their foreign key still blocks deleting the account. */
	@Query(nativeQuery = true, value = "select exists (select 1 from accounting.journal_line where account_id = :id)")
	boolean hasLines(@Param("id") long id);

	@Query(nativeQuery = true, value = """
			select exists (select 1 from accounting.journal_line where account_id = :id and deleted_at is null)
			""")
	boolean hasLiveLines(@Param("id") long id);

}
