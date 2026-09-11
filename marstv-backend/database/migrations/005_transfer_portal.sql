CREATE TABLE transfer_sessions (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 token_hash CHAR(64) NOT NULL, csrf_hash CHAR(64) NOT NULL, code_hash CHAR(64) DEFAULT NULL,
 license_uuid CHAR(36) DEFAULT NULL, license_version BIGINT UNSIGNED DEFAULT NULL,
 activation_id BIGINT UNSIGNED DEFAULT NULL, activation_hash CHAR(64) NOT NULL,
 new_device_code VARCHAR(16) DEFAULT NULL, attempts TINYINT UNSIGNED NOT NULL DEFAULT 0,
 verified_at TIMESTAMP NULL DEFAULT NULL, consumed_at TIMESTAMP NULL DEFAULT NULL,
 expires_at TIMESTAMP NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY (id), UNIQUE KEY uq_transfer_token (token_hash), KEY ix_transfer_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
