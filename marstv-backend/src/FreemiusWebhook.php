<?php

declare(strict_types=1);

final class FreemiusWebhook
{
    private const MAX_BODY_BYTES = 1048576;
    private const TERMINAL_EVENT_STATES = [
        'payment.refund' => 'refunded',
        'payment.dispute.created' => 'chargeback',
        'payment.dispute.lost' => 'chargeback',
    ];
    private const RECONCILIATION_EVENTS = [
        'payment.dispute.cancelled',
        'payment.dispute.closed',
        'payment.dispute.won',
    ];

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
        $dispute = $event['objects']['dispute'] ?? null;
        if ($type !== 'payment.created' && !isset(self::TERMINAL_EVENT_STATES[$type]) && !in_array($type, self::RECONCILIATION_EVENTS, true)) {
            return $this->recordIgnored($id, $type, $event, $rawBody);
        }
        if (str_starts_with($type, 'payment.dispute.')) {
            if (!is_array($dispute)) throw new ApiProblem('INVALID_REQUEST', 400);
            $fields = $this->disputeFields($dispute);
        } else {
            if (!is_array($payment)) throw new ApiProblem('INVALID_REQUEST', 400);
            $fields = $this->paymentFields($event, $payment);
        }
        $this->validatePayment($fields['product_id'], $fields['plan_id'], $fields['currency'], $fields['amount_minor'], $fields['environment']);

        [$reference, $storedHash] = $this->storeEnvelope($id, $type, $event, $fields['payment_id'], $fields['license_id'], $fields['plan_id'], $fields['product_id'], $fields['amount_minor'], $fields['currency'], $fields['environment']);
        $eventRow = $this->ingest($id, $type, (string) ($event['created'] ?? ''), hash('sha256', $rawBody), $reference, $storedHash);
        if ($eventRow['processing_status'] === 'processed') return ['status' => 'duplicate'];
        return $this->dispatch((int) $eventRow['id'], $type, $fields);
    }

    public function replay(string $providerEventId): array
    {
        $providerEventId = $this->identifier($providerEventId);
        $query = $this->db->prepare("SELECT * FROM webhook_events WHERE provider='freemius' AND provider_event_id=:event LIMIT 1");
        $query->execute(['event' => $providerEventId]);
        $row = $query->fetch() ?: throw new RuntimeException('Webhook event not found');
        if ($row['processing_status'] === 'processed') return ['status' => 'duplicate'];
        $expectedReference = 'storage/webhooks/'.hash('sha256', 'freemius|'.$providerEventId).'.json';
        if (!hash_equals($expectedReference, (string) $row['payload_reference'])) throw new RuntimeException('Webhook replay reference is invalid');
        $path = dirname(__DIR__).'/'.$expectedReference;
        $stored = is_readable($path) ? file_get_contents($path) : false;
        if ($stored === false || !hash_equals((string) ($row['stored_payload_sha256'] ?? ''), hash('sha256', $stored))) {
            throw new RuntimeException('Webhook replay payload is unavailable or corrupt');
        }
        $payload = json_decode($stored, true, 16, JSON_THROW_ON_ERROR);
        if (!is_array($payload) || ($payload['id'] ?? null) !== $providerEventId || !is_string($payload['type'] ?? null)) {
            throw new RuntimeException('Webhook replay payload is invalid');
        }
        $fields = [
            'payment_id' => $this->identifier($payload['payment_id'] ?? null),
            'license_id' => $this->identifier($payload['license_id'] ?? null),
            'plan_id' => $this->identifier($payload['plan_id'] ?? null),
            'product_id' => $this->identifier($payload['product_id'] ?? null),
            'amount_minor' => (int) ($payload['amount_minor'] ?? -1),
            'currency' => strtoupper((string) ($payload['currency'] ?? '')),
            'environment' => (int) ($payload['environment'] ?? -1),
        ];
        $this->validatePayment($fields['product_id'], $fields['plan_id'], $fields['currency'], $fields['amount_minor'], $fields['environment']);
        return $this->dispatch((int) $row['id'], (string) $payload['type'], $fields);
    }

    public function processDue(int $limit = 25): array
    {
        if ($limit < 1 || $limit > 100) throw new InvalidArgumentException('Worker limit must be between 1 and 100');
        $lock = $this->db->query("SELECT GET_LOCK('marstv_freemius_webhook_worker',0)")->fetchColumn();
        if ((int) $lock !== 1) return ['status' => 'locked', 'attempted' => 0, 'processed' => 0, 'failed' => 0];
        $result = ['status' => 'completed', 'attempted' => 0, 'processed' => 0, 'failed' => 0];
        try {
            $query = $this->db->query(
                "SELECT provider_event_id
                 FROM webhook_events
                 WHERE provider='freemius'
                   AND processing_status='retry_wait'
                   AND (next_attempt_at IS NULL OR next_attempt_at<=UTC_TIMESTAMP())
                 ORDER BY received_at,id
                 LIMIT {$limit}"
            );
            foreach ($query->fetchAll(PDO::FETCH_COLUMN) as $providerEventId) {
                $result['attempted']++;
                try {
                    $outcome = $this->replay((string) $providerEventId);
                    if (in_array($outcome['status'] ?? '', ['processed', 'duplicate'], true)) $result['processed']++;
                    else $result['failed']++;
                } catch (Throwable) {
                    $result['failed']++;
                    $this->scheduleReplayFailure((string) $providerEventId);
                }
            }
            return $result;
        } finally {
            try {
                $this->writeWorkerHeartbeat($result);
            } finally {
                $this->db->query("SELECT RELEASE_LOCK('marstv_freemius_webhook_worker')");
            }
        }
    }

    public static function validSignature(string $body, string $signature, string $secret): bool
    {
        return $secret !== '' && preg_match('/^[a-f0-9]{64}$/Di', $signature) === 1 &&
            hash_equals(hash_hmac('sha256', $body, $secret), strtolower($signature));
    }

    public static function purchaseTransition(string $current, string $incoming): string
    {
        if (!in_array($current, ['pending', 'paid', 'cancelled', 'refunded', 'chargeback'], true) ||
            !in_array($incoming, ['paid', 'cancelled', 'refunded', 'chargeback'], true)) {
            throw new InvalidArgumentException('Unknown purchase state');
        }
        if (in_array($current, ['refunded', 'chargeback'], true)) {
            return $current === $incoming ? 'terminal_duplicate' : 'reconciliation_required';
        }
        if ($incoming === 'paid' && $current === 'cancelled') return 'reconciliation_required';
        if ($incoming === $current) return 'duplicate';
        if ($current === 'paid' && in_array($incoming, ['pending', 'cancelled'], true)) return 'stale';
        return 'apply';
    }

    private function dispatch(int $eventId, string $type, array $fields): array
    {
        if ($type === 'payment.created') {
            return $this->fulfill($eventId, $fields['payment_id'], $fields['license_id'], $fields['plan_id'], $fields['product_id'], $fields['amount_minor'], $fields['currency']);
        }
        if (isset(self::TERMINAL_EVENT_STATES[$type])) {
            return $this->applyTerminalPaymentState($eventId, self::TERMINAL_EVENT_STATES[$type], $fields);
        }
        if (in_array($type, self::RECONCILIATION_EVENTS, true)) {
            return $this->recordReconciliationRequired($eventId, $fields['payment_id'], $fields['license_id']);
        }
        throw new RuntimeException('Stored webhook type is not replayable');
    }

    private function paymentFields(array $event, array $payment): array
    {
        $environment = filter_var($payment['environment'] ?? null, FILTER_VALIDATE_INT);
        if ($environment === false) throw new ApiProblem('INVALID_REQUEST', 400);
        return [
            'product_id' => $this->identifier($payment['plugin_id'] ?? $event['plugin_id'] ?? null),
            'payment_id' => $this->identifier($payment['id'] ?? $event['data']['payment_id'] ?? null),
            'license_id' => $this->identifier($payment['license_id'] ?? $event['data']['license_id'] ?? null),
            'plan_id' => $this->identifier($payment['plan_id'] ?? null),
            'currency' => strtoupper((string) ($payment['currency'] ?? '')),
            'amount_minor' => $this->amountMinor($payment['gross'] ?? null),
            'environment' => $environment,
        ];
    }

    private function disputeFields(array $dispute): array
    {
        $environment = filter_var($dispute['environment'] ?? null, FILTER_VALIDATE_INT);
        $amountMinor = filter_var($dispute['amount'] ?? null, FILTER_VALIDATE_INT);
        if ($environment === false || $amountMinor === false || $amountMinor < 0) {
            throw new ApiProblem('INVALID_REQUEST', 400);
        }
        $paymentId = $this->identifier($dispute['payment_id'] ?? null);
        $productId = $this->identifier($dispute['plugin_id'] ?? null);
        $query = $this->db->prepare(
            "SELECT p.product_id,p.amount_minor,p.currency,l.provider_license_id,l.plan_id
             FROM purchases p
             JOIN licenses l ON l.purchase_id=p.id
             WHERE p.provider='freemius' AND p.provider_order_id=:payment
             LIMIT 1"
        );
        $query->execute(['payment' => $paymentId]);
        $local = $query->fetch();
        if (!$local) throw new ApiProblem('WEBHOOK_PAYMENT_MISMATCH', 422);
        $currency = strtoupper((string) ($dispute['currency'] ?? ''));
        if ($productId !== (string) $local['product_id'] ||
            $amountMinor !== (int) $local['amount_minor'] ||
            $currency !== strtoupper((string) $local['currency'])) {
            throw new ApiProblem('WEBHOOK_PAYMENT_MISMATCH', 422);
        }
        return [
            'product_id' => $productId,
            'payment_id' => $paymentId,
            'license_id' => $this->identifier($local['provider_license_id'] ?? null),
            'plan_id' => $this->identifier($local['plan_id'] ?? null),
            'currency' => $currency,
            'amount_minor' => $amountMinor,
            'environment' => $environment,
        ];
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

            $purchaseQuery = $this->db->prepare("SELECT id FROM purchases WHERE provider='freemius' AND provider_order_id=:payment LIMIT 1 FOR UPDATE");
            $purchaseQuery->execute(['payment' => $paymentId]);
            $purchaseId = $purchaseQuery->fetchColumn();
            if ($purchaseId === false) {
                $purchase = $this->db->prepare("INSERT INTO purchases (provider,provider_order_id,activation_session_id,product_id,amount_minor,currency,status,purchased_at) VALUES ('freemius',:payment,:session,:product,:amount,:currency,'paid',UTC_TIMESTAMP())");
                $purchase->execute(['payment' => $paymentId, 'session' => $row['activation_session_id'], 'product' => $productId, 'amount' => $amountMinor, 'currency' => $currency]);
                $purchaseId = (int) $this->db->lastInsertId();
            } else {
                $stateQuery = $this->db->prepare('SELECT status FROM purchases WHERE id=:id LIMIT 1 FOR UPDATE');
                $stateQuery->execute(['id' => $purchaseId]);
                $purchaseState = (string) $stateQuery->fetchColumn();
                $transition = self::purchaseTransition($purchaseState, 'paid');
                if (in_array($transition, ['reconciliation_required', 'stale'], true)) {
                    $this->db->prepare("UPDATE webhook_events SET purchase_id=:purchase,processing_status='processed',processing_result='ignored_terminal_purchase',processing_attempts=processing_attempts+1,processed_at=UTC_TIMESTAMP(),next_attempt_at=NULL,last_error_code='RECONCILIATION_REQUIRED' WHERE id=:id")
                        ->execute(['purchase' => $purchaseId, 'id' => $eventId]);
                    $this->db->commit();
                    return ['status' => 'ignored_terminal'];
                }
                $this->db->prepare("UPDATE purchases SET status='paid',amount_minor=:amount,currency=:currency,purchased_at=COALESCE(purchased_at,UTC_TIMESTAMP()) WHERE id=:id")
                    ->execute(['amount' => $amountMinor, 'currency' => $currency, 'id' => $purchaseId]);
            }
            $license = $this->db->prepare("INSERT INTO licenses (license_uuid,purchase_id,current_device_id,plan_id,provider_license_id,status,license_version) VALUES (:uuid,:purchase,:device,:plan,:provider_license,'active',1) ON DUPLICATE KEY UPDATE provider_license_id=VALUES(provider_license_id)");
            $license->execute(['uuid' => self::uuid(), 'purchase' => $purchaseId, 'device' => $row['device_id'], 'plan' => $planId, 'provider_license' => $licenseId]);
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
            $this->scheduleEventFailure($eventId, 'FULFILLMENT_FAILED');
            throw $error;
        }
    }

    private function applyTerminalPaymentState(int $eventId, string $incomingState, array $fields): array
    {
        $this->db->beginTransaction();
        try {
            $event = $this->db->prepare('SELECT processing_status FROM webhook_events WHERE id=:id LIMIT 1 FOR UPDATE');
            $event->execute(['id' => $eventId]);
            if ($event->fetchColumn() === 'processed') { $this->db->commit(); return ['status' => 'duplicate']; }

            $purchaseQuery = $this->db->prepare(
                "SELECT DISTINCT p.*
                 FROM purchases p
                 LEFT JOIN licenses l ON l.purchase_id=p.id
                 WHERE p.provider='freemius'
                   AND (p.provider_order_id=:payment OR l.provider_license_id=:license)
                 LIMIT 1 FOR UPDATE"
            );
            $purchaseQuery->execute(['payment' => $fields['payment_id'], 'license' => $fields['license_id']]);
            $purchase = $purchaseQuery->fetch();
            if (!$purchase) {
                $claimQuery = $this->db->prepare(
                    "SELECT c.*,s.device_id
                     FROM freemius_checkout_claims c
                     JOIN activation_sessions s ON s.id=c.activation_session_id
                     WHERE c.provider_license_id=:license
                       AND c.provider_plan_id=:plan
                       AND (c.provider_purchase_id IS NULL OR c.provider_purchase_id=:payment)
                     LIMIT 1 FOR UPDATE"
                );
                $claimQuery->execute(['license' => $fields['license_id'], 'plan' => $fields['plan_id'], 'payment' => $fields['payment_id']]);
                $claim = $claimQuery->fetch() ?: throw new RuntimeException('No matching checkout claim for terminal payment event');
                $insert = $this->db->prepare("INSERT INTO purchases (provider,provider_order_id,activation_session_id,product_id,amount_minor,currency,status,purchased_at,refunded_at) VALUES ('freemius',:payment,:session,:product,:amount,:currency,:status,NULL,UTC_TIMESTAMP())");
                $insert->execute(['payment' => $fields['payment_id'], 'session' => $claim['activation_session_id'], 'product' => $fields['product_id'], 'amount' => $fields['amount_minor'], 'currency' => $fields['currency'], 'status' => $incomingState]);
                $purchase = ['id' => (int) $this->db->lastInsertId(), 'status' => $incomingState];
                $this->db->prepare('UPDATE freemius_checkout_claims SET claim_status=:status WHERE id=:id')
                    ->execute(['status' => $incomingState, 'id' => $claim['id']]);
            } else {
                $currentState = (string) $purchase['status'];
                $transition = self::purchaseTransition($currentState, $incomingState);
                if ($transition !== 'apply') {
                    $result = $transition === 'reconciliation_required' ? 'terminal_reconciliation_required' : 'terminal_duplicate';
                    $this->db->prepare("UPDATE webhook_events SET purchase_id=:purchase,processing_status='processed',processing_result=:result,processing_attempts=processing_attempts+1,processed_at=UTC_TIMESTAMP(),next_attempt_at=NULL,last_error_code=:error WHERE id=:id")
                        ->execute(['purchase' => $purchase['id'], 'result' => $result, 'error' => $result === 'terminal_duplicate' ? null : 'RECONCILIATION_REQUIRED', 'id' => $eventId]);
                    $this->db->commit();
                    return ['status' => $result];
                }
                $this->db->prepare('UPDATE purchases SET status=:status,refunded_at=UTC_TIMESTAMP() WHERE id=:id')
                    ->execute(['status' => $incomingState, 'id' => $purchase['id']]);
                $this->db->prepare('UPDATE freemius_checkout_claims c JOIN purchases p ON p.activation_session_id=c.activation_session_id SET c.claim_status=:status WHERE p.id=:purchase')
                    ->execute(['status' => $incomingState, 'purchase' => $purchase['id']]);
            }

            $licenseQuery = $this->db->prepare('SELECT * FROM licenses WHERE purchase_id=:purchase LIMIT 1 FOR UPDATE');
            $licenseQuery->execute(['purchase' => $purchase['id']]);
            $license = $licenseQuery->fetch();
            if ($license && $license['status'] === 'active') {
                $oldDeviceId = (int) $license['current_device_id'];
                if ($oldDeviceId > 0) {
                    $this->db->prepare('UPDATE license_assignments SET unassigned_at=UTC_TIMESTAMP() WHERE license_id=:license AND device_id=:device AND unassigned_at IS NULL')
                        ->execute(['license' => $license['id'], 'device' => $oldDeviceId]);
                }
                $this->db->prepare("UPDATE licenses SET status='revoked',current_device_id=NULL,license_version=license_version+1,revoked_at=UTC_TIMESTAMP(),revocation_reason_code=:reason WHERE id=:id")
                    ->execute(['reason' => $incomingState, 'id' => $license['id']]);
            }
            $this->db->prepare("UPDATE webhook_events SET purchase_id=:purchase,processing_status='processed',processing_result=:result,processing_attempts=processing_attempts+1,processed_at=UTC_TIMESTAMP(),next_attempt_at=NULL,last_error_code=NULL WHERE id=:id")
                ->execute(['purchase' => $purchase['id'], 'result' => 'purchase_'.$incomingState, 'id' => $eventId]);
            $this->db->commit();
            return ['status' => $incomingState];
        } catch (Throwable $error) {
            if ($this->db->inTransaction()) $this->db->rollBack();
            $this->scheduleEventFailure($eventId, 'TERMINAL_TRANSITION_FAILED');
            throw $error;
        }
    }

    private function recordReconciliationRequired(int $eventId, string $paymentId, string $licenseId): array
    {
        $this->db->beginTransaction();
        try {
            $event = $this->db->prepare('SELECT processing_status FROM webhook_events WHERE id=:id LIMIT 1 FOR UPDATE');
            $event->execute(['id' => $eventId]);
            if ($event->fetchColumn() === 'processed') { $this->db->commit(); return ['status' => 'duplicate']; }
            $purchase = $this->db->prepare("SELECT p.id FROM purchases p LEFT JOIN licenses l ON l.purchase_id=p.id WHERE p.provider='freemius' AND (p.provider_order_id=:payment OR l.provider_license_id=:license) LIMIT 1 FOR UPDATE");
            $purchase->execute(['payment' => $paymentId, 'license' => $licenseId]);
            $purchaseId = $purchase->fetchColumn();
            $this->db->prepare("UPDATE webhook_events SET purchase_id=:purchase,processing_status='processed',processing_result='reconciliation_required',processing_attempts=processing_attempts+1,processed_at=UTC_TIMESTAMP(),next_attempt_at=NULL,last_error_code='RECONCILIATION_REQUIRED' WHERE id=:id")
                ->execute(['purchase' => $purchaseId === false ? null : $purchaseId, 'id' => $eventId]);
            $this->db->commit();
            return ['status' => 'reconciliation_required'];
        } catch (Throwable $error) {
            if ($this->db->inTransaction()) $this->db->rollBack();
            $this->scheduleEventFailure($eventId, 'RECONCILIATION_RECORD_FAILED');
            throw $error;
        }
    }

    private function scheduleEventFailure(int $eventId, string $errorCode): void
    {
        $statement = $this->db->prepare("UPDATE webhook_events SET processing_status='retry_wait',next_attempt_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL (CASE WHEN processing_attempts=0 THEN 1 WHEN processing_attempts=1 THEN 5 WHEN processing_attempts=2 THEN 15 ELSE 60 END) MINUTE),processing_attempts=processing_attempts+1,last_error_code=:error WHERE id=:id");
        $statement->execute(['error' => $errorCode, 'id' => $eventId]);
    }

    private function scheduleReplayFailure(string $providerEventId): void
    {
        $statement = $this->db->prepare(
            "UPDATE webhook_events
             SET next_attempt_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL (CASE WHEN processing_attempts=0 THEN 1 WHEN processing_attempts=1 THEN 5 WHEN processing_attempts=2 THEN 15 ELSE 60 END) MINUTE),
                 processing_attempts=processing_attempts+1,
                 last_error_code='REPLAY_FAILED'
             WHERE provider='freemius'
               AND provider_event_id=:event
               AND processing_status='retry_wait'
               AND (next_attempt_at IS NULL OR next_attempt_at<=UTC_TIMESTAMP())"
        );
        $statement->execute(['event' => $providerEventId]);
    }

    private function writeWorkerHeartbeat(array $result): void
    {
        $path = dirname(__DIR__).'/storage/logs/webhook-worker-heartbeat.json';
        $payload = json_encode(['ran_at' => gmdate('c')] + $result, JSON_UNESCAPED_SLASHES | JSON_THROW_ON_ERROR);
        if (file_put_contents($path, $payload, LOCK_EX) === false) throw new RuntimeException('Webhook worker heartbeat could not be written');
    }

    private function ingest(string $id, string $type, string $created, string $hash, string $reference, string $storedHash): array
    {
        $at = strtotime($created.' UTC');
        $statement = $this->db->prepare("INSERT INTO webhook_events (provider,provider_event_id,provider_event_type,provider_event_at,payload_sha256,payload_reference,stored_payload_sha256) VALUES ('freemius',:event,:type,:event_at,:hash,:reference,:stored_hash) ON DUPLICATE KEY UPDATE provider_event_id=VALUES(provider_event_id)");
        $statement->execute(['event' => $id, 'type' => $type, 'event_at' => $at === false ? null : gmdate('Y-m-d H:i:s', $at), 'hash' => $hash, 'reference' => $reference, 'stored_hash' => $storedHash]);
        $query = $this->db->prepare("SELECT id,processing_status FROM webhook_events WHERE provider='freemius' AND provider_event_id=:event LIMIT 1");
        $query->execute(['event' => $id]);
        return $query->fetch();
    }

    private function recordIgnored(string $id, string $type, array $event, string $raw): array
    {
        [$reference, $storedHash] = $this->storeEnvelope($id, $type, $event, '', '', '', '', 0, '', -1);
        $row = $this->ingest($id, $type, (string) ($event['created'] ?? ''), hash('sha256', $raw), $reference, $storedHash);
        $this->db->prepare("UPDATE webhook_events SET processing_status='processed',processing_result='ignored_event',processed_at=UTC_TIMESTAMP() WHERE id=:id AND processing_status<>'processed'")->execute(['id' => $row['id']]);
        return ['status' => 'ignored'];
    }

    private function storeEnvelope(string $id, string $type, array $event, string $payment, string $license, string $plan, string $product, int $amount, string $currency, int $environment): array
    {
        $directory = dirname(__DIR__).'/storage/webhooks';
        if (!is_dir($directory) && !mkdir($directory, 0700, true) && !is_dir($directory)) throw new RuntimeException('Webhook storage unavailable');
        $name = hash('sha256', 'freemius|'.$id).'.json';
        $payload = json_encode(['id' => $id, 'type' => $type, 'created' => $event['created'] ?? null, 'payment_id' => $payment, 'license_id' => $license, 'plan_id' => $plan, 'product_id' => $product, 'amount_minor' => $amount, 'currency' => $currency, 'environment' => $environment], JSON_THROW_ON_ERROR);
        if (file_put_contents($directory.'/'.$name, $payload, LOCK_EX) === false) throw new RuntimeException('Webhook storage unavailable');
        return ['storage/webhooks/'.$name, hash('sha256', $payload)];
    }

    private function validatePayment(string $productId, string $planId, string $currency, int $amountMinor, int $environment): void
    {
        if ($productId !== (string) config('freemius_product_id')) throw new ApiProblem('WEBHOOK_PRODUCT_MISMATCH', 422);
        if ($planId !== (string) config('freemius_plan_id')) throw new ApiProblem('WEBHOOK_PLAN_MISMATCH', 422);
        if (strtoupper($currency) !== strtoupper((string) config('freemius_currency'))) throw new ApiProblem('WEBHOOK_CURRENCY_MISMATCH', 422);
        $expectedAmountMinor = (int) config('freemius_amount_minor');
        if ($amountMinor !== $expectedAmountMinor) throw new ApiProblem("WEBHOOK_AMOUNT_MISMATCH.RECEIVED_{$amountMinor}.EXPECTED_{$expectedAmountMinor}", 422);
        $expectedEnvironment = config('freemius_mode') === 'sandbox' ? 1 : 0;
        if ($environment !== $expectedEnvironment) throw new ApiProblem('WEBHOOK_ENVIRONMENT_MISMATCH', 422);
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
        if (!preg_match('/^-?\d+(?:\.\d{1,2})?$/D', (string) $value)) throw new ApiProblem('INVALID_REQUEST', 400);
        return abs((int) round((float) $value * 100));
    }

    private static function uuid(): string
    {
        $bytes = random_bytes(16); $bytes[6] = chr((ord($bytes[6]) & 0x0f) | 0x40); $bytes[8] = chr((ord($bytes[8]) & 0x3f) | 0x80); $hex = bin2hex($bytes);
        return substr($hex,0,8).'-'.substr($hex,8,4).'-'.substr($hex,12,4).'-'.substr($hex,16,4).'-'.substr($hex,20);
    }
}
