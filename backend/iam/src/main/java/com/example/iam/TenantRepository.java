package com.example.iam;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
interface TenantRepository extends Repository<Tenant, UUID> {

	Optional<Tenant> findById(UUID id);

}
