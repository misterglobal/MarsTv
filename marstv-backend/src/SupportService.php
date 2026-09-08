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

    private static function uuid(): string
    {
        $bytes = random_bytes(16); $bytes[6] = chr((ord($bytes[6]) & 0x0f) | 0x40); $bytes[8] = chr((ord($bytes[8]) & 0x3f) | 0x80); $hex = bin2hex($bytes);
        return substr($hex,0,8).'-'.substr($hex,8,4).'-'.substr($hex,12,4).'-'.substr($hex,16,4).'-'.substr($hex,20);
    }
}
