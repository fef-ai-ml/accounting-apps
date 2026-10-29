package com.example.iam;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class RolePermissionRepository {

	private final JdbcClient jdbc;

	RolePermissionRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Union of the permissions of the given roles. */
	Set<String> permissionsFor(Set<String> roles) {
		if (roles.isEmpty()) {
			return Set.of(); // "IN ()" is not valid SQL
		}
		return Set.copyOf(jdbc.sql("SELECT DISTINCT permission_code FROM iam.role_permission WHERE role IN (:roles)")
				.param("roles", roles)
				.query(String.class)
				.list());
	}

	/** Every role that has at least one permission, with its permissions, both sorted. */
	Map<String, List<String>> permissionsByRole() {
		Map<String, List<String>> result = new TreeMap<>();
		jdbc.sql("SELECT role, permission_code FROM iam.role_permission ORDER BY role, permission_code")
				.query((rs, rowNum) -> Map.entry(rs.getString("role"), rs.getString("permission_code")))
				.list()
				.forEach(row -> result.computeIfAbsent(row.getKey(), role -> new ArrayList<>()).add(row.getValue()));
		return result;
	}

}
