package com.example.garageops.account;

import com.example.garageops.persistence.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * The persisted identity row backing owner authentication (S-01 Phase 3). The single-owner intent
 * is in the type name; the table is {@code users}.
 *
 * <p>Extends {@link BaseEntity} (id only), <b>not</b> {@code ArchivableEntity}: FR-021 archive-only
 * is for portfolio records (tenants, garages, contracts), not the login identity. There is exactly
 * one owner and it is never archived, so the {@code archived_at}/{@code created_at}/{@code updated_at}
 * columns are deliberately absent — adding them would fail {@code ddl-auto=validate} against
 * {@code V2__users.sql}.
 *
 * <p>The {@code passwordHash} is stored verbatim as a BCrypt hash; this entity never encodes or
 * re-encodes it. Column mapping must match the V2 migration exactly.
 */
@Entity
@Table(name = "users")
public class OwnerAccount extends BaseEntity {

	@Column(name = "username", nullable = false, unique = true)
	private String username;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(name = "email", nullable = false, unique = true)
	private String email;

	protected OwnerAccount() {
		// JPA requires a no-arg constructor.
	}

	/**
	 * Build an owner from its provisioned credentials. The {@code passwordHash} is already a BCrypt
	 * hash and is stored as-is — callers must never pass plaintext.
	 */
	public OwnerAccount(String username, String email, String passwordHash) {
		this.username = username;
		this.email = email;
		this.passwordHash = passwordHash;
	}

	public String getUsername() {
		return username;
	}

	public String getEmail() {
		return email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}
}
