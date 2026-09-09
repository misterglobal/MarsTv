-- Reconcile databases created before provider licence identity became mandatory.
-- Dynamic DDL keeps this safe where the column or index already exists.
SET @add_provider_license_id = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE licenses ADD COLUMN provider_license_id VARCHAR(191) NULL AFTER plan_id',
    'SELECT 1'
  )
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'licenses'
    AND COLUMN_NAME = 'provider_license_id'
);
PREPARE add_provider_license_id_stmt FROM @add_provider_license_id;
EXECUTE add_provider_license_id_stmt;
DEALLOCATE PREPARE add_provider_license_id_stmt;

UPDATE licenses AS l
JOIN purchases AS p ON p.id = l.purchase_id
JOIN freemius_checkout_claims AS c ON c.activation_session_id = p.activation_session_id
SET l.provider_license_id = c.provider_license_id
WHERE p.provider = 'freemius'
  AND (l.provider_license_id IS NULL OR l.provider_license_id = '');

UPDATE licenses AS l
JOIN purchases AS p ON p.id = l.purchase_id
SET l.provider_license_id = CONCAT('support_test_', l.license_uuid)
WHERE p.provider = 'support_test'
  AND (l.provider_license_id IS NULL OR l.provider_license_id = '');

ALTER TABLE licenses MODIFY provider_license_id VARCHAR(191) NOT NULL;

SET @add_provider_license_index = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE licenses ADD UNIQUE KEY uq_license_provider_id (provider_license_id)',
    'SELECT 1'
  )
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'licenses'
    AND INDEX_NAME = 'uq_license_provider_id'
);
PREPARE add_provider_license_index_stmt FROM @add_provider_license_index;
EXECUTE add_provider_license_index_stmt;
DEALLOCATE PREPARE add_provider_license_index_stmt;
