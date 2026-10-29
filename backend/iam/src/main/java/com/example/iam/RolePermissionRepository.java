package com.example.iam;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
interface RolePermissionRepository extends Repository<RolePermission, RolePermissionId> {

	/** Union of the permissions of the given roles. Callers must not pass an empty collection ("in ()"). */
	@Query("select distinct rp.id.permissionCode from RolePermission rp where rp.id.role in :roles")
	Set<String> findPermissionCodesByRoleIn(@Param("roles") Collection<String> roles);

	@Query("select rp from RolePermission rp order by rp.id.role, rp.id.permissionCode")
	List<RolePermission> findAllOrderedByRoleAndPermission();

}
