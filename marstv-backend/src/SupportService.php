<?php
declare(strict_types=1);

final class SupportService
{
    public function __construct(private readonly PDO $db) {}

    public function grantTestLicense(string $deviceCode, string $operator, string $reason): array
    {
        if (!preg_match('/^MARS-[A-HJ-NP-Z2-9]{4,12}$/D', $deviceCode)) throw new InvalidArgumentException('Invalid public Device ID');
        if (!preg_match('/^[A-Za-z0-9_.@-]{2,100}$/D', $operator)) throw new InvalidArgumentException('Invalid operator ID');
        if ($reason === '' || strlen($reason) > 255) throw new InvalidArgumentException('Reason is required and must be at most 255 characters');
        $this->db->beginTransaction();
        try {
            $find = $this->db->prepare("SELECT * FROM devices WHERE device_code=:code AND status='active' LIMIT 1 FOR UPDATE");
            $find->execute(['code' => $deviceCode]);
            $device = $find->fetch() ?: throw new RuntimeException('Active device not found');
            $existing = $this->db->prepare("SELECT * FROM licenses WHERE current_device_id=:device AND status='active' LIMIT 1 FOR UPDATE");
            $existing->execute(['device' => $device['id']]);
            $license = $existing->fetch();
            if ($license) { $this->db->commit(); return ['created' => false, 'device' => $device, 'license' => $license]; }

            $sessionUuid = self::uuid();
            $session = $this->db->prepare("INSERT INTO activation_sessions (session_uuid,device_id,status,expires_at,paid_at) VALUES (:uuid,:device,'paid',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
            $session->execute(['uuid' => $sessionUuid, 'device' => $device['id']]);
            $sessionId = (int) $this->db->lastInsertId();
            $orderId = 'test-'.$sessionUuid;
            $purchase = $this->db->prepare("INSERT INTO purchases (provider,provider_order_id,activation_session_id,product_id,amount_minor,currency,status,purchased_at) VALUES ('support_test',:order_id,:session,'pro_lifetime_v1',0,'CAD','paid',UTC_TIMESTAMP())");
            $purchase->execute(['order_id' => $orderId, 'session' => $sessionId]);
            $purchaseId = (int) $this->db->lastInsertId();
            $licenseUuid = self::uuid();
            $insertLicense = $this->db->prepare("INSERT INTO licenses (license_uuid,purchase_id,current_device_id,plan_id,status,license_version) VALUES (:uuid,:purchase,:device,'pro_lifetime_v1','active',1)");
            $insertLicense->execute(['uuid' => $licenseUuid, 'purchase' => $purchaseId, 'device' => $device['id']]);
            $licenseId = (int) $this->db->lastInsertId();
            $assignment = $this->db->prepare("INSERT INTO license_assignments (license_id,device_id,assignment_reason) VALUES (:license,:device,'support_test')");
            $assignment->execute(['license' => $licenseId, 'device' => $device['id']]);
            $action = $this->db->prepare("INSERT INTO support_actions (action_uuid,operator_id,action_type,purchase_id,license_id,new_device_id,reason) VALUES (:uuid,:operator,'test_license_grant',:purchase,:license,:device,:reason)");
            $action->execute(['uuid' => self::uuid(), 'operator' => $operator, 'purchase' => $purchaseId, 'license' => $licenseId, 'device' => $device['id'], 'reason' => $reason]);
            $this->db->commit();
            return ['created' => true, 'device' => $device, 'license' => ['id' => $licenseId, 'license_uuid' => $licenseUuid, 'license_version' => 1, 'plan_id' => 'pro_lifetime_v1']];
        } catch (Throwable $error) {
            if ($this->db->inTransaction()) $this->db->rollBack();
            throw $error;
        }
    }

    public function lookupPurchase(string $providerOrderId): array
    {
        $this->identifier($providerOrderId, 'provider order ID');
        $query = $this->db->prepare(
            "SELECT p.provider,p.provider_order_id,p.product_id,p.amount_minor,p.currency,p.status purchase_status,
                    p.purchased_at,p.refunded_at,l.license_uuid,l.status license_status,l.license_version,
                    d.device_code current_device_code
             FROM purchases p
             LEFT JOIN licenses l ON l.purchase_id=p.id
             LEFT JOIN devices d ON d.id=l.current_device_id
             WHERE p.provider_order_id=:order
             LIMIT 1"
        );
        $query->execute(['order' => $providerOrderId]);
        return $query->fetch() ?: throw new RuntimeException('Purchase not found');
    }

    public function transferLicense(
        string $licenseUuid,
        string $newDeviceCode,
        string $operator,
        string $reason,
        bool $overrideLimit = false,
    ): array {
        $this->licenseUuid($licenseUuid);
        $this->deviceCode($newDeviceCode);
        $this->operator($operator);
        $this->reason($reason);
        $this->db->beginTransaction();
        try {
            $license = $this->lockedLicense($licenseUuid);
            if ($license['status'] !== 'active') throw new RuntimeException('Only an active licence can be transferred');
            $deviceQuery = $this->db->prepare("SELECT * FROM devices WHERE device_code=:code AND status='active' LIMIT 1 FOR UPDATE");
            $deviceQuery->execute(['code' => $newDeviceCode]);
            $newDevice = $deviceQuery->fetch() ?: throw new RuntimeException('Active destination device not found');
            if ((int) $license['current_device_id'] === (int) $newDevice['id']) {
                $this->db->commit();
                return ['changed' => false, 'license' => $license, 'device' => $newDevice];
            }
            $occupied = $this->db->prepare("SELECT license_uuid FROM licenses WHERE current_device_id=:device AND status='active' LIMIT 1 FOR UPDATE");
            $occupied->execute(['device' => $newDevice['id']]);
            if ($occupied->fetchColumn()) throw new RuntimeException('Destination device already has an active licence');
            if (!$overrideLimit) {
                $count = $this->db->prepare(
                    "SELECT
                        SUM(created_at >= DATE_SUB(UTC_TIMESTAMP(),INTERVAL 30 DAY)) count_30,
                        SUM(created_at >= DATE_SUB(UTC_TIMESTAMP(),INTERVAL 365 DAY)) count_365
                     FROM support_actions
                     WHERE license_id=:license AND action_type IN ('license_transfer','license_transfer_override')"
                );
                $count->execute(['license' => $license['id']]);
                $limits = $count->fetch();
                if ((int) ($limits['count_30'] ?? 0) >= 1 || (int) ($limits['count_365'] ?? 0) >= 3) {
                    throw new RuntimeException('Licence transfer limit reached; an explicit reviewed override is required');
                }
            }
            $oldDeviceId = (int) $license['current_device_id'];
            if ($oldDeviceId < 1) throw new RuntimeException('Active licence has no current device');
            $this->db->prepare("UPDATE license_assignments SET unassigned_at=UTC_TIMESTAMP() WHERE license_id=:license AND device_id=:device AND unassigned_at IS NULL")
                ->execute(['license' => $license['id'], 'device' => $oldDeviceId]);
            $this->db->prepare("UPDATE licenses SET current_device_id=:device,license_version=license_version+1,revoked_at=NULL,revocation_reason_code=NULL WHERE id=:id AND status='active'")
                ->execute(['device' => $newDevice['id'], 'id' => $license['id']]);
            $this->db->prepare("INSERT INTO license_assignments (license_id,device_id,assignment_reason) VALUES (:license,:device,'support_transfer')")
                ->execute(['license' => $license['id'], 'device' => $newDevice['id']]);
            $actionType = $overrideLimit ? 'license_transfer_override' : 'license_transfer';
            $this->audit($operator, $actionType, (int) $license['purchase_id'], (int) $license['id'], $oldDeviceId, (int) $newDevice['id'], $reason);
            $this->db->commit();
            $license['license_version'] = (int) $license['license_version'] + 1;
            $license['current_device_id'] = $newDevice['id'];
            return ['changed' => true, 'license' => $license, 'device' => $newDevice];
        } catch (Throwable $error) {
            if ($this->db->inTransaction()) $this->db->rollBack();
            throw $error;
        }
    }

    public function revokeLicense(string $licenseUuid, string $operator, string $reason): array
    {
        $this->licenseUuid($licenseUuid);
        $this->operator($operator);
        $this->reason($reason);
        $this->db->beginTransaction();
        try {
            $license = $this->lockedLicense($licenseUuid);
            if ($license['status'] === 'revoked') {
                $this->db->commit();
                return ['changed' => false, 'license' => $license];
            }
            if ($license['status'] !== 'active') throw new RuntimeException('Licence is not active');
            $oldDeviceId = (int) $license['current_device_id'];
            if ($oldDeviceId < 1) throw new RuntimeException('Active licence has no current device');
            $this->db->prepare("UPDATE license_assignments SET unassigned_at=UTC_TIMESTAMP() WHERE license_id=:license AND device_id=:device AND unassigned_at IS NULL")
                ->execute(['license' => $license['id'], 'device' => $oldDeviceId]);
            $this->db->prepare("UPDATE licenses SET status='revoked',current_device_id=NULL,license_version=license_version+1,revoked_at=UTC_TIMESTAMP(),revocation_reason_code='support_revoked' WHERE id=:id")
                ->execute(['id' => $license['id']]);
            $this->audit($operator, 'license_revoke', (int) $license['purchase_id'], (int) $license['id'], $oldDeviceId, null, $reason);
            $this->db->commit();
            $license['status'] = 'revoked';
            $license['current_device_id'] = null;
            $license['license_version'] = (int) $license['license_version'] + 1;
            return ['changed' => true, 'license' => $license];
        } catch (Throwable $error) {
            if ($this->db->inTransaction()) $this->db->rollBack();
            throw $error;
        }
    }

    public function replayWebhook(string $providerEventId, string $operator, string $reason): array
    {
        $this->identifier($providerEventId, 'provider event ID');
        $this->operator($operator);
        $this->reason($reason);
        $result = (new FreemiusWebhook($this->db))->replay($providerEventId);
        $this->db->beginTransaction();
        try {
            $query = $this->db->prepare(
                "SELECT w.purchase_id,l.id license_id
                 FROM webhook_events w
                 LEFT JOIN licenses l ON l.purchase_id=w.purchase_id
                 WHERE w.provider='freemius' AND w.provider_event_id=:event
                 LIMIT 1 FOR UPDATE"
            );
            $query->execute(['event' => $providerEventId]);
            $row = $query->fetch() ?: throw new RuntimeException('Webhook event not found after replay');
            $statement = $this->db->prepare('INSERT INTO support_actions (action_uuid,operator_id,action_type,purchase_id,license_id,reason) VALUES (:uuid,:operator,\'webhook_replay\',:purchase,:license,:reason)');
            $statement->execute(['uuid' => self::uuid(), 'operator' => $operator, 'purchase' => $row['purchase_id'], 'license' => $row['license_id'], 'reason' => $reason]);
            $this->db->commit();
            return $result;
        } catch (Throwable $error) {
            if ($this->db->inTransaction()) $this->db->rollBack();
            throw $error;
        }
    }

    private function lockedLicense(string $licenseUuid): array
    {
        $query = $this->db->prepare('SELECT * FROM licenses WHERE license_uuid=:uuid LIMIT 1 FOR UPDATE');
        $query->execute(['uuid' => $licenseUuid]);
        return $query->fetch() ?: throw new RuntimeException('Licence not found');
    }

    private function audit(string $operator, string $type, int $purchaseId, int $licenseId, ?int $oldDeviceId, ?int $newDeviceId, string $reason): void
    {
        $statement = $this->db->prepare('INSERT INTO support_actions (action_uuid,operator_id,action_type,purchase_id,license_id,old_device_id,new_device_id,reason) VALUES (:uuid,:operator,:type,:purchase,:license,:old_device,:new_device,:reason)');
        $statement->execute(['uuid' => self::uuid(), 'operator' => $operator, 'type' => $type, 'purchase' => $purchaseId, 'license' => $licenseId, 'old_device' => $oldDeviceId, 'new_device' => $newDeviceId, 'reason' => $reason]);
    }

    private function identifier(string $value, string $label): void
    {
        if ($value === '' || strlen($value) > 191 || !preg_match('/^[A-Za-z0-9_-]+$/D', $value)) throw new InvalidArgumentException("Invalid {$label}");
    }

    private function licenseUuid(string $value): void
    {
        if (!preg_match('/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/iD', $value)) throw new InvalidArgumentException('Invalid licence UUID');
    }

    private function deviceCode(string $value): void
    {
        if (!preg_match('/^MARS-[A-HJ-NP-Z2-9]{4,12}$/D', $value)) throw new InvalidArgumentException('Invalid public Device ID');
    }

    private function operator(string $value): void
    {
        if (!preg_match('/^[A-Za-z0-9_.@-]{2,100}$/D', $value)) throw new InvalidArgumentException('Invalid operator ID');
    }

    private function reason(string $value): void
    {
        if (trim($value) === '' || strlen($value) > 255) throw new InvalidArgumentException('Reason is required and must be at most 255 characters');
    }

    private static function uuid(): string
    {
        $bytes = random_bytes(16); $bytes[6] = chr((ord($bytes[6]) & 0x0f) | 0x40); $bytes[8] = chr((ord($bytes[8]) & 0x3f) | 0x80); $hex = bin2hex($bytes);
        return substr($hex,0,8).'-'.substr($hex,8,4).'-'.substr($hex,12,4).'-'.substr($hex,16,4).'-'.substr($hex,20);
    }
}
