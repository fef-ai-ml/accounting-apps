package com.example.tenancy;

import org.springframework.test.context.TestPropertySource;

// Outside autocommit, a session-level set_config rolls back with its transaction; the tenant must still not leak.
@TestPropertySource(properties = { "spring.datasource.hikari.auto-commit=false",
		"spring.jpa.properties.hibernate.connection.provider_disables_autocommit=true" })
class TenancyWithoutAutoCommitTests extends TenancyTests {

}
