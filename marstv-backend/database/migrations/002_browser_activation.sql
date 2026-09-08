ALTER TABLE activation_sessions
  ADD COLUMN csrf_token_hash CHAR(64) DEFAULT NULL AFTER browser_session_hash,
  ADD COLUMN legal_terms_version VARCHAR(30) DEFAULT NULL AFTER paid_at,
  ADD COLUMN legal_accepted_at TIMESTAMP NULL DEFAULT NULL AFTER legal_terms_version,
  ADD UNIQUE KEY uq_activation_browser_session (browser_session_hash);

CREATE TABLE rate_limits (
  key_hash CHAR(64) NOT NULL,
  scope VARCHAR(50) NOT NULL,
  hit_count INT UNSIGNED NOT NULL,
  window_expires_at TIMESTAMP NOT NULL,
  PRIMARY KEY (key_hash),
  KEY ix_rate_limit_expiry (window_expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE freemius_checkout_claims (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  activation_session_id BIGINT UNSIGNED NOT NULL,
  provider_purchase_id VARCHAR(191) DEFAULT NULL,
  provider_license_id VARCHAR(191) NOT NULL,
  provider_plan_id VARCHAR(191) NOT NULL,
  claim_status VARCHAR(30) NOT NULL DEFAULT 'pending_verification',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_freemius_claim_session (activation_session_id),
  UNIQUE KEY uq_freemius_claim_license (provider_license_id),
  CONSTRAINT fk_freemius_claim_session
    FOREIGN KEY (activation_session_id) REFERENCES activation_sessions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
