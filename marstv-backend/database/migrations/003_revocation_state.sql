ALTER TABLE licenses
  ADD COLUMN revocation_reason_code VARCHAR(50) DEFAULT NULL AFTER revoked_at;

ALTER TABLE webhook_events
  ADD COLUMN stored_payload_sha256 CHAR(64) DEFAULT NULL AFTER payload_reference;
