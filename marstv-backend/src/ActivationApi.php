<?php

declare(strict_types=1);

final class ApiProblem extends RuntimeException
{
    public function __construct(public readonly string $errorCode, public readonly int $httpStatus, string $safeMessage = '')
    {
        parent::__construct($safeMessage);
    }
}

final class ActivationApi
{
    public function __construct(private readonly PDO $db) {}

    public function preview(array $input, string $clientIp): array
    {
        [$column, $hash] = $this->credential($input);
        $this->limit('activation_preview_ip', $clientIp, 5, 600);
        $this->limit('activation_preview_ip_daily', $clientIp, 30, 86400);
        $this->limit('activation_preview_credential', $hash, 5, 600);
        $row = $this->findSession($column, $hash, false);
        return [
            'device' => [
                'displayName' => $this->platformLabel((string) ($row['platform'] ?? 'android')),
                'deviceCode' => (string) $row['device_code'],
            ],
            'expiresAt' => $this->isoTime((string) $row['expires_at']),
        ];
    }

    public function redeem(array $input, string $clientIp): array
    {
        [$column, $hash] = $this->credential($input);
        $this->limit('activation_redeem_ip', $clientIp, 5, 600);
        $this->limit('activation_redeem_ip_daily', $clientIp, 30, 86400);
        $this->limit('activation_redeem_credential', $hash, 5, 600);

        $browserToken = $this->token();
        $csrfToken = $this->token();
        $this->db->beginTransaction();
        try {
            $row = $this->findSession($column, $hash, true);
            $statement = $this->db->prepare(
                "UPDATE activation_sessions
                 SET status='redeemed', redeemed_at=UTC_TIMESTAMP(),
                     manual_code_hash=NULL, qr_secret_hash=NULL,
                     browser_session_hash=:browser_hash,
                     csrf_token_hash=:csrf_hash,
                     browser_session_expires_at=expires_at
                 WHERE id=:id AND status='pending' AND expires_at > UTC_TIMESTAMP()"
            );
            $statement->execute([
                'browser_hash' => hash('sha256', $browserToken),
                'csrf_hash' => hash('sha256', $csrfToken),
                'id' => $row['id'],
            ]);
            if ($statement->rowCount() !== 1) throw new ApiProblem('ACTIVATION_REDEEMED', 409);
            $this->db->commit();
        } catch (Throwable $error) {
            if ($this->db->inTransaction()) $this->db->rollBack();
            throw $error;
        }

        setcookie('marstv_activation', $browserToken, [
            'expires' => strtotime((string) $row['expires_at'].' UTC'),
            'path' => '/',
            'secure' => config('environment') !== 'local',
            'httponly' => true,
            'samesite' => 'Strict',
        ]);
        return ['status' => 'redeemed', 'csrfToken' => $csrfToken, 'expiresAt' => $this->isoTime((string) $row['expires_at'])];
    }

    public function checkout(array $input, array $headers, array $cookies, string $clientIp): array
    {
        $this->limit('checkout_ip', $clientIp, 10, 3600);
        $session = $this->authorizedBrowserSession($input, $headers, $cookies);
        $this->limit('checkout_device', (string) $session['device_id'], 3, 3600);

        $accepted = ($input['legalAccepted'] ?? false) === true;
        $termsVersion = (string) ($input['termsVersion'] ?? '');
        if (!$accepted || !hash_equals((string) config('legal_terms_version'), $termsVersion)) {
            throw new ApiProblem('LEGAL_ACCEPTANCE_REQUIRED', 422);
        }
        if (!config('freemius_checkout_enabled')) throw new ApiProblem('CHECKOUT_DISABLED', 503);

        $publicKey = (string) config('freemius_public_key');
        $secretKey = (string) config('freemius_secret_key');
        if ((string) config('freemius_product_id') === '') throw new ApiProblem('CHECKOUT_PRODUCT_ID_MISSING', 503);
        if ((string) config('freemius_plan_id') === '') throw new ApiProblem('CHECKOUT_PLAN_ID_MISSING', 503);
        if ($publicKey === '') throw new ApiProblem('CHECKOUT_PUBLIC_KEY_MISSING', 503);
        if ($secretKey === '') throw new ApiProblem('CHECKOUT_SECRET_KEY_MISSING', 503);

        $attemptId = (string) ($session['checkout_attempt_id'] ?? '');
        if ($attemptId === '') $attemptId = $this->uuid();
        $statement = $this->db->prepare(
            "UPDATE activation_sessions
             SET status='checkout_created', checkout_attempt_id=:attempt_id,
                 checkout_attempt_started_at=COALESCE(checkout_attempt_started_at,UTC_TIMESTAMP()),
                 checkout_created_at=COALESCE(checkout_created_at,UTC_TIMESTAMP()),
                 browser_session_expires_at=GREATEST(browser_session_expires_at,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 24 HOUR)),
                 legal_terms_version=:terms_version,
                 legal_accepted_at=COALESCE(legal_accepted_at,UTC_TIMESTAMP())
             WHERE id=:id AND status IN ('redeemed','checkout_created')"
        );
        $statement->execute(['attempt_id' => $attemptId, 'terms_version' => $termsVersion, 'id' => $session['id']]);
        setcookie('marstv_activation', (string) ($cookies['marstv_activation'] ?? ''), [
            'expires' => time() + 86400,
            'path' => '/',
            'secure' => config('environment') !== 'local',
            'httponly' => true,
            'samesite' => 'Strict',
        ]);

        $checkout = [
            'productId' => (string) config('freemius_product_id'),
            'planId' => (string) config('freemius_plan_id'),
            'publicKey' => $publicKey,
            'billingCycle' => 'lifetime',
            'currency' => 'usd',
            'licenses' => 1,
            'image' => rtrim((string) config('base_url'), '/').'/assets/marstv-icon.svg',
        ];
        if (config('freemius_mode') === 'sandbox') {
            $timestamp = time();
            $checkout['sandbox'] = [
                'ctx' => (string) $timestamp,
                'token' => md5($timestamp.(string) config('freemius_product_id').$secretKey.$publicKey.'checkout'),
            ];
        }
        return [
            'provider' => 'freemius',
            'attemptId' => $attemptId,
            'checkout' => $checkout,
            'termsVersion' => $termsVersion,
        ];
    }

    public function resume(array $cookies, string $clientIp): array
    {
        $this->limit('activation_resume_ip', $clientIp, 10, 600);
        $browserToken = (string) ($cookies['marstv_activation'] ?? '');
        if ($browserToken === '') throw new ApiProblem('ACTIVATION_EXPIRED', 410);

        $csrfToken = $this->token();
        $statement = $this->db->prepare(
            "UPDATE activation_sessions
             SET csrf_token_hash=:csrf_hash
             WHERE browser_session_hash=:browser_hash
               AND status IN ('redeemed','checkout_created')
               AND browser_session_expires_at > UTC_TIMESTAMP()"
        );
        $statement->execute([
            'csrf_hash' => hash('sha256', $csrfToken),
            'browser_hash' => hash('sha256', $browserToken),
        ]);
        if ($statement->rowCount() !== 1) throw new ApiProblem('ACTIVATION_EXPIRED', 410);
        return ['status' => 'redeemed', 'csrfToken' => $csrfToken];
    }

    public function claim(array $input, array $headers, array $cookies, string $clientIp): array
    {
        $this->limit('checkout_claim_ip', $clientIp, 10, 3600);
        $session = $this->authorizedBrowserSession($input, $headers, $cookies);
        if ((string) $session['status'] !== 'checkout_created') throw new ApiProblem('INVALID_REQUEST', 409);

        $purchaseId = $this->providerId($input['purchaseId'] ?? null, false);
        $licenseId = $this->providerId($input['licenseId'] ?? null, true);
        $planId = $this->providerId($input['planId'] ?? null, true);
        if (!hash_equals((string) config('freemius_plan_id'), $planId)) throw new ApiProblem('INVALID_REQUEST', 400);

        $statement = $this->db->prepare(
            "INSERT INTO freemius_checkout_claims
                (activation_session_id, provider_purchase_id, provider_license_id, provider_plan_id, claim_status)
             VALUES (:session_id,:purchase_id,:license_id,:plan_id,'pending_verification')
             ON DUPLICATE KEY UPDATE
                provider_purchase_id=IF(provider_license_id=VALUES(provider_license_id),VALUES(provider_purchase_id),provider_purchase_id),
                provider_plan_id=IF(provider_license_id=VALUES(provider_license_id),VALUES(provider_plan_id),provider_plan_id),
                updated_at=UTC_TIMESTAMP()"
        );
        $statement->execute([
            'session_id' => $session['id'],
            'purchase_id' => $purchaseId,
            'license_id' => $licenseId,
            'plan_id' => $planId,
        ]);

        $check = $this->db->prepare('SELECT provider_license_id FROM freemius_checkout_claims WHERE activation_session_id=:session_id');
        $check->execute(['session_id' => $session['id']]);
        if (!hash_equals($licenseId, (string) $check->fetchColumn())) throw new ApiProblem('PURCHASE_ALREADY_CLAIMED', 409);
        return ['status' => 'pending_verification'];
    }

    private function authorizedBrowserSession(array $input, array $headers, array $cookies): array
    {
        $browserToken = (string) ($cookies['marstv_activation'] ?? '');
        $csrfHeader = (string) ($headers['x-csrf-token'] ?? '');
        $csrfBody = (string) ($input['csrfToken'] ?? '');
        if ($browserToken === '' || $csrfHeader === '' || !hash_equals($csrfHeader, $csrfBody)) {
            throw new ApiProblem('INVALID_REQUEST', 400);
        }
        $statement = $this->db->prepare(
            "SELECT id, device_id, status, checkout_attempt_id FROM activation_sessions
             WHERE browser_session_hash=:browser_hash AND csrf_token_hash=:csrf_hash
               AND status IN ('redeemed','checkout_created')
               AND browser_session_expires_at > UTC_TIMESTAMP()
             LIMIT 1"
        );
        $statement->execute(['browser_hash' => hash('sha256', $browserToken), 'csrf_hash' => hash('sha256', $csrfHeader)]);
        $session = $statement->fetch();
        if (!$session) throw new ApiProblem('ACTIVATION_EXPIRED', 410);
        return $session;
    }

    private function findSession(string $column, string $hash, bool $lock): array
    {
        $sql = "SELECT s.id, s.expires_at, d.device_code, d.platform
                FROM activation_sessions s JOIN devices d ON d.id=s.device_id
                WHERE s.{$column}=:hash AND s.status='pending' AND s.expires_at > UTC_TIMESTAMP()
                LIMIT 1".($lock ? ' FOR UPDATE' : '');
        $statement = $this->db->prepare($sql);
        $statement->execute(['hash' => $hash]);
        $row = $statement->fetch();
        if (!$row) throw new ApiProblem('ACTIVATION_EXPIRED', 410);
        return $row;
    }

    private function credential(array $input): array
    {
        $type = $input['credentialType'] ?? null;
        $value = $input['credential'] ?? null;
        if (!is_string($value) || strlen($value) > 512) throw new ApiProblem('INVALID_REQUEST', 400);
        if ($type === 'manual') {
            $normalized = strtoupper(str_replace('-', '', trim($value)));
            if (!preg_match('/^[A-HJ-NP-Z2-9]{8}$/', $normalized)) throw new ApiProblem('INVALID_REQUEST', 400);
            return ['manual_code_hash', hash_hmac('sha256', $normalized, (string) config('activation_pepper'))];
        }
        if ($type === 'qr') {
            if (strlen($value) < 32 || strlen($value) > 256 || !preg_match('/^[A-Za-z0-9_-]+$/', $value)) throw new ApiProblem('INVALID_REQUEST', 400);
            return ['qr_secret_hash', hash_hmac('sha256', $value, (string) config('activation_pepper'))];
        }
        throw new ApiProblem('INVALID_REQUEST', 400);
    }

    private function limit(string $scope, string $subject, int $maximum, int $windowSeconds): void
    {
        $key = hash_hmac('sha256', $scope.'|'.$subject, (string) config('rate_limit_pepper'));
        $statement = $this->db->prepare(
            "INSERT INTO rate_limits (key_hash, scope, hit_count, window_expires_at)
             VALUES (:key_hash,:scope,1,DATE_ADD(UTC_TIMESTAMP(), INTERVAL :window_seconds SECOND))
             ON DUPLICATE KEY UPDATE
               hit_count=IF(window_expires_at<=UTC_TIMESTAMP(),1,hit_count+1),
               window_expires_at=IF(window_expires_at<=UTC_TIMESTAMP(),DATE_ADD(UTC_TIMESTAMP(), INTERVAL :window_seconds_update SECOND),window_expires_at)"
        );
        $statement->bindValue(':key_hash', $key);
        $statement->bindValue(':scope', $scope);
        $statement->bindValue(':window_seconds', $windowSeconds, PDO::PARAM_INT);
        $statement->bindValue(':window_seconds_update', $windowSeconds, PDO::PARAM_INT);
        $statement->execute();
        $check = $this->db->prepare('SELECT hit_count, TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(),window_expires_at) retry_after FROM rate_limits WHERE key_hash=:key_hash');
        $check->execute(['key_hash' => $key]);
        $row = $check->fetch();
        if ((int) $row['hit_count'] > $maximum) {
            header('Retry-After: '.max(1, (int) $row['retry_after']));
            throw new ApiProblem('ACTIVATION_RATE_LIMITED', 429);
        }
    }

    private function token(): string { return rtrim(strtr(base64_encode(random_bytes(32)), '+/', '-_'), '='); }
    private function uuid(): string
    {
        $bytes = random_bytes(16);
        $bytes[6] = chr((ord($bytes[6]) & 0x0f) | 0x40);
        $bytes[8] = chr((ord($bytes[8]) & 0x3f) | 0x80);
        $hex = bin2hex($bytes);
        return substr($hex, 0, 8).'-'.substr($hex, 8, 4).'-'.substr($hex, 12, 4).'-'.substr($hex, 16, 4).'-'.substr($hex, 20);
    }
    private function providerId(mixed $value, bool $required): ?string
    {
        if (($value === null || $value === '') && !$required) return null;
        $normalized = is_int($value) ? (string) $value : (is_string($value) ? trim($value) : '');
        if ($normalized === '' || strlen($normalized) > 191 || !preg_match('/^[A-Za-z0-9_-]+$/', $normalized)) {
            throw new ApiProblem('INVALID_REQUEST', 400);
        }
        return $normalized;
    }
    private function isoTime(string $value): string { return gmdate('Y-m-d\TH:i:s\Z', strtotime($value.' UTC')); }
    private function platformLabel(string $value): string
    {
        return match (strtolower($value)) {
            'fire_tv' => 'Fire TV', 'android_tv' => 'Android TV', 'google_tv' => 'Google TV',
            'phone' => 'Android phone', 'tablet' => 'Android tablet', default => 'Android device',
        };
    }
}
