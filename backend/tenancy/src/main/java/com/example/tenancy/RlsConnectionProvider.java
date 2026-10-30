package com.example.tenancy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;

/**
 * Hands Hibernate pooled connections with the tenant set as {@code app.tenant_id}, which the row-level security
 * policies compare against. The setting is cleared before a connection goes back to the pool; a connection that
 * cannot be cleared is evicted, because the next borrower would otherwise act as this tenant.
 */
final class RlsConnectionProvider implements MultiTenantConnectionProvider<UUID> {

	private final DataSource dataSource;

	RlsConnectionProvider(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public Connection getAnyConnection() throws SQLException {
		return dataSource.getConnection();
	}

	@Override
	public void releaseAnyConnection(Connection connection) throws SQLException {
		connection.close();
	}

	@Override
	public Connection getConnection(UUID tenantId) throws SQLException {
		Connection connection = getAnyConnection();
		// Root is set too ('' = no tenant), so a value left on the connection by anyone else is overwritten.
		String value = JwtTenantIdentifierResolver.ROOT.equals(tenantId) ? "" : tenantId.toString();
		try {
			setTenant(connection, value);
		}
		catch (SQLException ex) {
			discard(connection);
			throw ex;
		}
		return connection;
	}

	@Override
	public void releaseConnection(UUID tenantId, Connection connection) throws SQLException {
		try {
			setTenant(connection, "");
		}
		catch (SQLException ex) {
			discard(connection);
			throw ex;
		}
		connection.close();
	}

	@Override
	public boolean supportsAggressiveRelease() {
		return false;
	}

	@Override
	public boolean isUnwrappableAs(Class<?> unwrapType) {
		return unwrapType.isInstance(this);
	}

	@Override
	public <T> T unwrap(Class<T> unwrapType) {
		return unwrapType.cast(this);
	}

	private static void setTenant(Connection connection, String tenantId) throws SQLException {
		// Session-level (false): Hibernate may borrow the connection before the transaction begins. Outside
		// autocommit it is committed on its own, or the pool's rollback on close would undo the reset.
		boolean autoCommit = connection.getAutoCommit();
		if (!autoCommit) {
			connection.rollback();
		}
		try (PreparedStatement statement = connection.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
			statement.setString(1, tenantId);
			statement.execute();
		}
		if (!autoCommit) {
			connection.commit();
		}
	}

	private void discard(Connection connection) throws SQLException {
		if (dataSource.isWrapperFor(HikariDataSource.class)) {
			dataSource.unwrap(HikariDataSource.class).evictConnection(connection);
		}
		else {
			connection.close();
		}
	}

}
