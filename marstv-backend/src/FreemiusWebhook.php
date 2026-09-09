<?php

declare(strict_types=1);

final class FreemiusWebhook
{
    private const MAX_BODY_BYTES = 1048576;

    public function __construct(private readonly PDO $db) {}

    public function handle(string $rawBody, string $signature): array
    {
        if ($rawBody === '' || strlen($rawBody) > self::MAX_BODY_BYTES) throw new ApiProblem('INVALID_REQUEST', 413);
        if (!self::validSignature($rawBody, $signature, (string) config('freemius_secret_key'))) {
            throw new ApiProblem('INVALID_WEBHOOK_SIGNATURE', 401);
        }
        $event = json_decode($rawBody, true, 32, JSON_THROW_ON_ERROR);
        if (!is_array($event)) throw new ApiProblem('INVALID_REQUEST', 400);

        $id = $this->identifier($event['id'] ?? null);
        $type = (string) ($event['type'] ?? '');
        $payment = $event['objects']['payment'] ?? null;
        if ($type !== 'payment.created' || !is_array($payment)) {
            return $this->recordIgnored($id, $type, $event, $rawBody);
        }

        $productId = $this->identifier($payment['plugin_id'] ?? $event['plugin_id'] ?? null);
        $paymentId = $this->identifier($payment['id'] ?? $event['data']['payment_id'] ?? null);
        $licenseId = $this->identifier($payment['license_id'] ?? $event['data']['license_id'] ?? null);
        $planId = $this->identifier($payment['plan_id'] ?? null);
        $currency = strtoupper((string) ($payment['currency'] ?? ''));
        $amountMinor = $this->amountMinor($payment['gross'] ?? null);
        $environment = filter_var($payment['environment'] ?? null, FILTER_VALIDATE_INT);
        $expectedEnvironment = config('freemius_mode') === 'sandbox' ? 1 : 0;
        if ($productId !== (string) config('freemius_product_id')) throw new ApiProblem('WEBHOOK_PRODUCT_MISMATCH', 422);
        if ($planId !== (string) config('freemius_plan_id')) throw new ApiProblem('WEBHOOK_PLAN_MISMATCH', 422);
        if ($currency !== strtoupper((string) config('freemius_currency'))) throw new ApiProblem('WEBHOOK_CURRENCY_MISMATCH', 422);
        $expectedAmountMinor = (int) config('freemius_amount_minor');
        if ($amountMinor !== $expectedAmountMinor) {
            throw new ApiProblem("WEBHOOK_AMOUNT_MISMATCH.RECEIVED_{$amountMinor}.EXPECTED_{$expectedAmountMinor}", 422);
        }
        if ($environment !== $expectedEnvironment) throw new ApiProblem('WEBHOOK_ENVIRONMENT_MISMATCH', 422);

        $reference = $this->storeEnvelope($id, $type, $event, $paymentId, $licenseId, $planId, $productId, $amountMinor, $currency);
        $eventRow = $this->ingest($id, $type, (string) ($event['created'] ?? ''), hash('sha256', $rawBody), $reference);
        if ($eventRow['processing_status'] === 'processed') return ['status' => 'duplicate'];
        return $this->fulfill((int) $eventRow['id'], $paymentId, $licenseId, $planId, $productId, $amountMinor, $currency);
    }

    public static function validSignature(string $body, string $signature, string $secret): bool
    {
        return $secret !== '' && preg_match('/^[a-f0-9]{64}$/Di', $signature) === 1 &&
            hash_equals(hash_hmac('sha256', $body, $secret), strtolower($signature));
    }

    private function fulfill(int $eventId, string $paymentId, string $licenseId, string $planId, string $productId, int $amountMinor, string $currency): array
    {
        $this->db->beginTransaction();
        try {
            $event = $this->db->prepare('SELECT * FROM webhook_events WHERE id=:id FOR UPDATE');
            $event->execute(['id' => $eventId]);
            $eventRow = $event->fetch();
            if (($eventRow['processing_status'] ?? '') === 'processed') { $this->db->commit(); return ['status' => 'duplicate']; }
            $claim = $this->db->prepare("SELECT c.*,s.device_id FROM freemius_checkout_claims c JOIN activation_sessions s ON s.id=c.activation_session_id WHERE c.provider_license_id=:license AND c.provider_plan_id=:plan AND (c.provider_purchase_id IS NULL OR c.provider_purchase_id=:payment) LIMIT 1 FOR UPDATE");
            $claim->execute(['license' => $licenseId, 'plan' => $planId, 'payment' => $paymentId]);
            $row = $claim->fetch();
            if (!$row) throw new RuntimeException('No matching checkout claim');

            $purchase = $this->db->prepare("INSERT INTO purchases (provider,provider_order_id,activation_session_id,product_id,amount_minor,currency,status,purchased_at) VALUES ('freemius',:payment,:session,:product,:amount,:currency,'paid',UTC_TIMESTAMP()) ON DUPLICATE KEY UPDATE status='paid',amount_minor=VALUES(amount_minor),currency=VALUES(currency)");
            $purchase->execute(['payment' => $paymentId, 'session' => $row['activation_session_id'], 'product' => $productId, 'amount' => $amountMinor, 'currency' => $currency]);
            $purchaseQuery = $this->db->prepare("SELECT id FROM purchases WHERE provider='freemius' AND provider_order_id=:payment LIMIT 1 FOR UPDATE");
            $purchaseQuery->execute(['payment' => $paymentId]);
            $purchaseId = (int) $purchaseQuery->fetchColumn();
            $license = $this->db->prepare("INSERT INTO licenses (license_uuid,purchase_id,current_device_id,plan_id,status,license_version) VALUES (:uuid,:purchase,:device,:plan,'active',1) ON DUPLICATE KEY UPDATE status='active'");
            $license->execute(['uuid' => self::uuid(), 'purchase' => $purchaseId, 'device' => $row['device_id'], 'plan' => $planId]);
            $licenseQuery = $this->db->prepare('SELECT id FROM licenses WHERE purchase_id=:purchase LIMIT 1 FOR UPDATE');
            $licenseQuery->execute(['purchase' => $purchaseId]);
            $localLicenseId = (int) $licenseQuery->fetchColumn();
            $assignment = $this->db->prepare("INSERT INTO license_assignments (license_id,device_id,assignment_reason) SELECT :license,:device,'verified_payment' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM license_assignments WHERE license_id=:license2 AND device_id=:device2 AND unassigned_at IS NULL)");
            $assignment->execute(['license' => $localLicenseId, 'device' => $row['device_id'], 'license2' => $localLicenseId, 'device2' => $row['device_id']]);
            $this->db->prepare("UPDATE activation_sessions SET status='paid',paid_at=COALESCE(paid_at,UTC_TIMESTAMP()) WHERE id=:id")->execute(['id' => $row['activation_session_id']]);
            $this->db->prepare("UPDATE freemius_checkout_claims SET claim_status='verified' WHERE id=:id")->execute(['id' => $row['id']]);
            $this->db->prepare("UPDATE webhook_events SET purchase_id=:purchase,processing_status='processed',processing_result='license_activated',processing_attempts=processing_attempts+1,processed_at=UTC_TIMESTAMP(),last_error_code=NULL WHERE id=:id")->execute(['purchase' => $purchaseId, 'id' => $eventId]);
            $this->db->commit();
            return ['status' => 'processed'];
        } catch (Throwable $error) {
            if ($this->db->inTransaction()) $this->db->rollBack();
            $this->db->prepare("UPDATE webhook_events SET processing_status='retry_wait',processing_attempts=processing_attempts+1,next_attempt_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 MINUTE),last_error_code='FULFILLMENT_FAILED' WHERE id=:id")->execute(['id' => $eventId]);
            throw $error;
        }
    }

    private function ingest(string $id, string $type, string $created, string $hash, string $reference): array
    {
        $at = strtotime($created.' UTC');
        $statement = $this->db->prepare("INSERT INTO webhook_events (provider,provider_event_id,provider_event_type,provider_event_at,payload_sha256,payload_reference) VALUES ('freemius',:event,:type,:event_at,:hash,:reference) ON DUPLICATE KEY UPDATE provider_event_id=VALUES(provider_event_id)");
        $statement->execute(['event' => $id, 'type' => $type, 'event_at' => $at === false ? null : gmdate('Y-m-d H:i:s', $at), 'hash' => $hash, 'reference' => $reference]);
        $query = $this->db->prepare("SELECT id,processing_status FROM webhook_events WHERE provider='freemius' AND provider_event_id=:event LIMIT 1");
        $query->execute(['event' => $id]);
        return $query->fetch();
    }

    private function recordIgnored(string $id, string $type, array $event, string $raw): array
    {
        $reference = $this->storeEnvelope($id, $type, $event, '', '', '', '', 0, '');
        $row = $this->ingest($id, $type, (string) ($event['created'] ?? ''), hash('sha256', $raw), $reference);
        $this->db->prepare("UPDATE webhook_events SET processing_status='processed',processing_result='ignored_event',processed_at=UTC_TIMESTAMP() WHERE id=:id AND processing_status<>'processed'")->execute(['id' => $row['id']]);
        return ['status' => 'ignored'];
    }

    private function storeEnvelope(string $id, string $type, array $event, string $payment, string $license, string $plan, string $product, int $amount, string $currency): string
    {
        $directory = dirname(__DIR__).'/storage/webhooks';
        if (!is_dir($directory) && !mkdir($directory, 0700, true) && !is_dir($directory)) throw new RuntimeException('Webhook storage unavailable');
        $name = hash('sha256', 'freemius|'.$id).'.json';
        $payload = json_encode(['id' => $id, 'type' => $type, 'created' => $event['created'] ?? null, 'payment_id' => $payment, 'license_id' => $license, 'plan_id' => $plan, 'product_id' => $product, 'amount_minor' => $amount, 'currency' => $currency], JSON_THROW_ON_ERROR);
        if (file_put_contents($directory.'/'.$name, $payload, LOCK_EX) === false) throw new RuntimeException('Webhook storage unavailable');
        return 'storage/webhooks/'.$name;
    }

    private function identifier(mixed $value): string
    {
        $value = is_int($value) ? (string) $value : (is_string($value) ? $value : '');
        if ($value === '' || strlen($value) > 191 || preg_match('/^[A-Za-z0-9_-]+$/D', $value) !== 1) throw new ApiProblem('INVALID_REQUEST', 400);
        return $value;
    }

    private function amountMinor(mixed $value): int
    {
        if (!is_int($value) && !is_float($value) && !is_string($value)) throw new ApiProblem('INVALID_REQUEST', 400);
        if (!preg_match('/^\d+(?:\.\d{1,2})?$/D', (string) $value)) throw new ApiProblem('INVALID_REQUEST', 400);
        return (int) round((float) $value * 100);
    }

    private static function uuid(): string
    {
        $bytes = random_bytes(16); $bytes[6] = chr((ord($bytes[6]) & 0x0f) | 0x40); $bytes[8] = chr((ord($bytes[8]) & 0x3f) | 0x80); $hex = bin2hex($bytes);
        return substr($hex,0,8).'-'.substr($hex,8,4).'-'.substr($hex,12,4).'-'.substr($hex,16,4).'-'.substr($hex,20);
    }
}
