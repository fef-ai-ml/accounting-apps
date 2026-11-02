package com.example.accounting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AccountRepository extends JpaRepository<Account, Long> {

	boolean existsByCode(String code);

	boolean existsByParentId(Long parentId);

	@Query(nativeQuery = true, value = "select exists (select 1 from accounting.journal_line where account_id = :id)")
	boolean hasLines(@Param("id") long id);

}
