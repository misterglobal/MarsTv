CREATE TABLE devices (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, device_uuid CHAR(36) NOT NULL, device_code VARCHAR(16) NOT NULL,
 public_key_spki TEXT NOT NULL, public_key_thumbprint CHAR(43) NOT NULL, installation_hash CHAR(64) DEFAULT NULL,
 platform VARCHAR(50) DEFAULT NULL, app_version_code INT UNSIGNED DEFAULT NULL, status VARCHAR(20) NOT NULL DEFAULT 'active',
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, last_seen_at TIMESTAMP NULL DEFAULT NULL,
 PRIMARY KEY (id), UNIQUE KEY uq_device_uuid (device_uuid), UNIQUE KEY uq_device_code (device_code),
 UNIQUE KEY uq_device_key_thumbprint (public_key_thumbprint), KEY ix_installation_hash (installation_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE activation_sessions (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, session_uuid CHAR(36) NOT NULL, device_id BIGINT UNSIGNED NOT NULL,
 manual_code_hash CHAR(64) DEFAULT NULL, qr_secret_hash CHAR(64) DEFAULT NULL, browser_session_hash CHAR(64) DEFAULT NULL,
 browser_session_expires_at TIMESTAMP NULL DEFAULT NULL, status VARCHAR(20) NOT NULL DEFAULT 'pending', expires_at TIMESTAMP NOT NULL,
 redeemed_at TIMESTAMP NULL DEFAULT NULL, checkout_attempt_id CHAR(36) DEFAULT NULL, checkout_attempt_started_at TIMESTAMP NULL DEFAULT NULL,
 provider_checkout_id VARCHAR(191) DEFAULT NULL, checkout_created_at TIMESTAMP NULL DEFAULT NULL,
 provider_checkout_expires_at TIMESTAMP NULL DEFAULT NULL, paid_at TIMESTAMP NULL DEFAULT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY (id), UNIQUE KEY uq_activation_uuid (session_uuid), UNIQUE KEY uq_activation_manual_hash (manual_code_hash),
 UNIQUE KEY uq_activation_qr_hash (qr_secret_hash), UNIQUE KEY uq_activation_checkout_attempt (checkout_attempt_id),
 KEY ix_activation_device (device_id, status), CONSTRAINT fk_activation_device FOREIGN KEY (device_id) REFERENCES devices(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE purchases (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, provider VARCHAR(30) NOT NULL, provider_order_id VARCHAR(191) NOT NULL,
 activation_session_id BIGINT UNSIGNED NOT NULL, customer_email VARCHAR(191) DEFAULT NULL, product_id VARCHAR(100) NOT NULL,
 amount_minor INT UNSIGNED DEFAULT NULL, currency CHAR(3) DEFAULT NULL, status VARCHAR(20) NOT NULL DEFAULT 'pending',
 purchased_at TIMESTAMP NULL DEFAULT NULL, refunded_at TIMESTAMP NULL DEFAULT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY (id), UNIQUE KEY uq_provider_order (provider, provider_order_id),
 UNIQUE KEY uq_purchase_activation_session (activation_session_id), KEY ix_purchase_email (customer_email),
 CONSTRAINT fk_purchase_activation_session FOREIGN KEY (activation_session_id) REFERENCES activation_sessions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE licenses (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, license_uuid CHAR(36) NOT NULL, purchase_id BIGINT UNSIGNED NOT NULL,
 current_device_id BIGINT UNSIGNED DEFAULT NULL, plan_id VARCHAR(100) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'active',
 license_version BIGINT UNSIGNED NOT NULL DEFAULT 1, activated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 last_verified_at TIMESTAMP NULL DEFAULT NULL, revoked_at TIMESTAMP NULL DEFAULT NULL,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY (id), UNIQUE KEY uq_license_uuid (license_uuid), UNIQUE KEY uq_license_purchase (purchase_id),
 UNIQUE KEY uq_license_current_device (current_device_id), CONSTRAINT fk_license_purchase FOREIGN KEY (purchase_id) REFERENCES purchases(id),
 CONSTRAINT fk_license_current_device FOREIGN KEY (current_device_id) REFERENCES devices(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE license_assignments (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, license_id BIGINT UNSIGNED NOT NULL, device_id BIGINT UNSIGNED NOT NULL,
 assigned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, unassigned_at TIMESTAMP NULL DEFAULT NULL,
 assignment_reason VARCHAR(50) NOT NULL, PRIMARY KEY (id), KEY ix_assignment_license (license_id, assigned_at),
 KEY ix_assignment_device (device_id, assigned_at), CONSTRAINT fk_assignment_license FOREIGN KEY (license_id) REFERENCES licenses(id),
 CONSTRAINT fk_assignment_device FOREIGN KEY (device_id) REFERENCES devices(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE device_challenges (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, challenge_uuid CHAR(36) NOT NULL, device_id BIGINT UNSIGNED NOT NULL,
 nonce_b64url CHAR(43) NOT NULL, http_method VARCHAR(10) NOT NULL, canonical_path VARCHAR(191) NOT NULL,
 body_sha256 CHAR(43) NOT NULL, expires_at TIMESTAMP NOT NULL, used_at TIMESTAMP NULL DEFAULT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (id), UNIQUE KEY uq_challenge_uuid (challenge_uuid),
 KEY ix_challenge_device (device_id, expires_at), CONSTRAINT fk_challenge_device FOREIGN KEY (device_id) REFERENCES devices(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE support_actions (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, action_uuid CHAR(36) NOT NULL, operator_id VARCHAR(100) NOT NULL,
 action_type VARCHAR(50) NOT NULL, purchase_id BIGINT UNSIGNED DEFAULT NULL, license_id BIGINT UNSIGNED DEFAULT NULL,
 old_device_id BIGINT UNSIGNED DEFAULT NULL, new_device_id BIGINT UNSIGNED DEFAULT NULL, reason VARCHAR(255) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (id), UNIQUE KEY uq_support_action_uuid (action_uuid),
 KEY ix_support_license (license_id, created_at), CONSTRAINT fk_support_purchase FOREIGN KEY (purchase_id) REFERENCES purchases(id),
 CONSTRAINT fk_support_license FOREIGN KEY (license_id) REFERENCES licenses(id),
 CONSTRAINT fk_support_old_device FOREIGN KEY (old_device_id) REFERENCES devices(id),
 CONSTRAINT fk_support_new_device FOREIGN KEY (new_device_id) REFERENCES devices(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE webhook_events (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, provider VARCHAR(30) NOT NULL, provider_event_id VARCHAR(191) NOT NULL,
 provider_event_type VARCHAR(100) NOT NULL, provider_event_at TIMESTAMP NULL DEFAULT NULL,
 received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, payload_sha256 CHAR(64) NOT NULL,
 payload_reference VARCHAR(255) DEFAULT NULL, purchase_id BIGINT UNSIGNED DEFAULT NULL,
 processing_status VARCHAR(20) NOT NULL DEFAULT 'received', processing_result VARCHAR(100) DEFAULT NULL,
 processing_attempts INT UNSIGNED NOT NULL DEFAULT 0, next_attempt_at TIMESTAMP NULL DEFAULT NULL,
 last_error_code VARCHAR(100) DEFAULT NULL, processed_at TIMESTAMP NULL DEFAULT NULL,
 PRIMARY KEY (id), UNIQUE KEY uq_webhook_provider_event (provider, provider_event_id),
 KEY ix_webhook_purchase (purchase_id, provider_event_at), KEY ix_webhook_due (processing_status, next_attempt_at),
 CONSTRAINT fk_webhook_purchase FOREIGN KEY (purchase_id) REFERENCES purchases(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
