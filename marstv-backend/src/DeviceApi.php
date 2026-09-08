<?php

declare(strict_types=1);

final class DeviceApi
{
    private const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

    public function __construct(private readonly PDO $db) {}

    public function register(array $input, string $clientIp): array
    {
        $this->limit('device_register_ip_hour', $clientIp, 5, 3600);
        $this->limit('device_register_ip_day', $clientIp, 20, 86400);
        $spki = $this->requiredString($input, 'devicePublicKeySpki', 512);
        $thumbprint = $this->requiredString($input, 'devicePublicKeyThumbprint', 43);
        $der = base64_decode($spki, true);
        if ($der === false || !hash_equals($this->base64Url(hash('sha256', $der, true)), $thumbprint)) {
            throw new ApiProblem('INVALID_DEVICE_KEY', 400);
        }
        $pem = "-----BEGIN PUBLIC KEY-----\n".chunk_split(base64_encode($der), 64, "\n")."-----END PUBLIC KEY-----\n";
        $key = openssl_pkey_get_public($pem);
        $details = $key === false ? false : openssl_pkey_get_details($key);
        if ($details === false || $details['type'] !== OPENSSL_KEYTYPE_EC || ($details['ec']['curve_name'] ?? '') !== 'prime256v1') {
            throw new ApiProblem('INVALID_DEVICE_KEY', 400);
        }
        $installation = $this->requiredString($input, 'installationFingerprint', 64);
        if (!preg_match('/^[A-Fa-f0-9]{64}$/D', $installation)) throw new ApiProblem('INVALID_REQUEST', 400);
        $platform = $this->requiredString($input, 'platform', 50);
        $version = filter_var($input['appVersionCode'] ?? null, FILTER_VALIDATE_INT, ['options' => ['min_range' => 1]]);
        if ($version === false) throw new ApiProblem('INVALID_REQUEST', 400);

        $this->db->beginTransaction();
        try {
            $find = $this->db->prepare('SELECT id,device_uuid,device_code FROM devices WHERE public_key_thumbprint=:thumbprint LIMIT 1 FOR UPDATE');
            $find->execute(['thumbprint' => $thumbprint]);
            $device = $find->fetch();
            if (!$device) {
                $this->limit('device_register_global_hour', 'global', 100, 3600);
                $uuid = $this->uuid();
                $code = $this->uniqueDeviceCode();
                $insert = $this->db->prepare('INSERT INTO devices (device_uuid,device_code,public_key_spki,public_key_thumbprint,installation_hash,platform,app_version_code) VALUES (:uuid,:code,:spki,:thumbprint,:installation,:platform,:version)');
                $insert->execute(compact('uuid', 'code', 'spki', 'thumbprint', 'installation', 'platform', 'version'));
                $device = ['id' => (int) $this->db->lastInsertId(), 'device_uuid' => $uuid, 'device_code' => $code];
            }
            $session = $this->newActivationSession($device);
            $this->db->commit();
            return $session;
        } catch (Throwable $error) {
            if ($this->db->inTransaction()) $this->db->rollBack();
            throw $error;
        }
    }

    public function challenge(string $deviceUuid, array $input, string $clientIp): array
    {
        $this->limit('device_challenge_ip', $clientIp, 120, 60);
        $device = $this->device($deviceUuid);
        $this->limit('device_challenge_device', (string) $device['id'], 30, 60);
        $method = strtoupper($this->requiredString($input, 'target_method', 10));
        $path = $this->requiredString($input, 'canonical_path', 191);
        $bodyHash = $this->requiredString($input, 'body_sha256_b64url', 43);
        $allowed = $method === 'POST' && ($path === "/api/v1/devices/{$deviceUuid}/status" || $path === '/api/v1/activation-sessions');
        if (!$allowed || !preg_match('/^[A-Za-z0-9_-]{43}$/D', $bodyHash)) throw new ApiProblem('INVALID_REQUEST', 400);
        $id = $this->uuid();
        $nonce = $this->base64Url(random_bytes(32));
        $expires = time() + 60;
        $this->db->beginTransaction();
        try {
            $delete = $this->db->prepare('DELETE FROM device_challenges WHERE device_id=:device AND used_at IS NULL AND id NOT IN (SELECT id FROM (SELECT id FROM device_challenges WHERE device_id=:device2 AND used_at IS NULL ORDER BY created_at DESC LIMIT 4) kept)');
            $delete->execute(['device' => $device['id'], 'device2' => $device['id']]);
            $insert = $this->db->prepare('INSERT INTO device_challenges (challenge_uuid,device_id,nonce_b64url,http_method,canonical_path,body_sha256,expires_at) VALUES (:id,:device,:nonce,:method,:path,:body_hash,FROM_UNIXTIME(:expires))');
            $insert->execute(['id' => $id, 'device' => $device['id'], 'nonce' => $nonce, 'method' => $method, 'path' => $path, 'body_hash' => $bodyHash, 'expires' => $expires]);
            $this->db->commit();
        } catch (Throwable $error) {
            if ($this->db->inTransaction()) $this->db->rollBack();
            throw $error;
        }
        return ['challenge_id' => $id, 'nonce_b64url' => $nonce, 'expires_at_epoch_seconds' => $expires, 'http_method' => $method, 'canonical_path' => $path, 'body_sha256_b64url' => $bodyHash];
    }

    public function refreshActivation(array $headers, string $rawBody, string $clientIp): array
    {
        $device = $this->authenticate($headers, 'POST', '/api/v1/activation-sessions', $rawBody);
        $this->limit('activation_session_ip', $clientIp, 20, 3600);
        $this->limit('activation_session_device_10m', (string) $device['id'], 3, 600);
        $this->limit('activation_session_device_day', (string) $device['id'], 10, 86400);
        return $this->transaction(fn() => $this->newActivationSession($device));
    }

    public function status(string $deviceUuid, array $headers, string $rawBody, string $clientIp): array
    {
        $device = $this->authenticate($headers, 'POST', "/api/v1/devices/{$deviceUuid}/status", $rawBody, $deviceUuid);
        $this->limit('device_status_ip', $clientIp, 120, 60);
        $this->limit('device_status_device', (string) $device['id'], 20, 60);
        $touch = $this->db->prepare('UPDATE devices SET last_seen_at=UTC_TIMESTAMP() WHERE id=:id');
        $touch->execute(['id' => $device['id']]);
        return ['status' => 'free', 'server_time' => time()];
    }

    private function authenticate(array $headers, string $method, string $path, string $rawBody, ?string $expectedUuid = null): array
    {
        $challengeId = (string) ($headers['x-mars-challenge-id'] ?? '');
        $signatureText = (string) ($headers['x-mars-device-signature'] ?? '');
        $signature = $this->base64UrlDecode($signatureText);
        if ($challengeId === '' || $signature === null) throw new ApiProblem('DEVICE_AUTH_REQUIRED', 401);
        return $this->transaction(function () use ($challengeId, $signature, $method, $path, $rawBody, $expectedUuid): array {
            $query = $this->db->prepare('SELECT c.id challenge_row,c.challenge_uuid,c.nonce_b64url,c.http_method,c.canonical_path,c.body_sha256,UNIX_TIMESTAMP(c.expires_at) expires_at,d.* FROM device_challenges c JOIN devices d ON d.id=c.device_id WHERE c.challenge_uuid=:challenge AND c.used_at IS NULL AND c.expires_at>UTC_TIMESTAMP() LIMIT 1 FOR UPDATE');
            $query->execute(['challenge' => $challengeId]);
            $row = $query->fetch();
            $bodyHash = $this->base64Url(hash('sha256', $rawBody, true));
            if (!$row || ($expectedUuid !== null && !hash_equals($expectedUuid, (string) $row['device_uuid'])) || !hash_equals($method, (string) $row['http_method']) || !hash_equals($path, (string) $row['canonical_path']) || !hash_equals($bodyHash, (string) $row['body_sha256'])) {
                throw new ApiProblem('INVALID_DEVICE_PROOF', 401);
            }
            $message = implode("\n", ['MARSTV_DEVICE_AUTH_V1', $row['challenge_uuid'], $row['nonce_b64url'], $row['device_uuid'], $row['http_method'], $row['canonical_path'], $row['body_sha256'], (string) $row['expires_at']]);
            $pem = "-----BEGIN PUBLIC KEY-----\n".chunk_split((string) $row['public_key_spki'], 64, "\n")."-----END PUBLIC KEY-----\n";
            if (openssl_verify($message, $signature, $pem, OPENSSL_ALGO_SHA256) !== 1) throw new ApiProblem('INVALID_DEVICE_PROOF', 401);
            $used = $this->db->prepare('UPDATE device_challenges SET used_at=UTC_TIMESTAMP() WHERE id=:id AND used_at IS NULL');
            $used->execute(['id' => $row['challenge_row']]);
            if ($used->rowCount() !== 1) throw new ApiProblem('INVALID_DEVICE_PROOF', 401);
            return $row;
        });
    }

    private function newActivationSession(array $device): array
    {
        $expire = $this->db->prepare("UPDATE activation_sessions SET status='expired',manual_code_hash=NULL,qr_secret_hash=NULL,browser_session_hash=NULL,csrf_token_hash=NULL WHERE device_id=:device AND status IN ('pending','redeemed') AND checkout_attempt_id IS NULL");
        $expire->execute(['device' => $device['id']]);
        $sessionId = $this->uuid();
        $manual = $this->randomCharacters(8);
        $formatted = substr($manual, 0, 4).'-'.substr($manual, 4);
        $secret = $this->base64Url(random_bytes(32));
        $insert = $this->db->prepare("INSERT INTO activation_sessions (session_uuid,device_id,manual_code_hash,qr_secret_hash,status,expires_at) VALUES (:uuid,:device,:manual,:qr,'pending',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 10 MINUTE))");
        $insert->execute(['uuid' => $sessionId, 'device' => $device['id'], 'manual' => hash_hmac('sha256', $manual, (string) config('activation_pepper')), 'qr' => hash_hmac('sha256', $secret, (string) config('activation_pepper'))]);
        return ['deviceId' => $device['device_uuid'], 'deviceCode' => $device['device_code'], 'activationSessionId' => $sessionId, 'activationCode' => $formatted, 'activationUrl' => rtrim((string) config('base_url'), '/').'/activate', 'qrPayload' => rtrim((string) config('base_url'), '/').'/activate?s='.$secret, 'expiresAt' => gmdate('Y-m-d\TH:i:s\Z', time() + 600)];
    }

    private function device(string $uuid): array
    {
        if (!preg_match('/^[0-9a-f-]{36}$/D', $uuid)) throw new ApiProblem('DEVICE_NOT_FOUND', 404);
        $query = $this->db->prepare("SELECT * FROM devices WHERE device_uuid=:uuid AND status='active' LIMIT 1");
        $query->execute(['uuid' => $uuid]);
        return $query->fetch() ?: throw new ApiProblem('DEVICE_NOT_FOUND', 404);
    }

    private function uniqueDeviceCode(): string
    {
        for ($attempt = 0; $attempt < 10; $attempt++) {
            $code = 'MARS-'.$this->randomCharacters(4);
            $query = $this->db->prepare('SELECT 1 FROM devices WHERE device_code=:code');
            $query->execute(['code' => $code]);
            if (!$query->fetchColumn()) return $code;
        }
        throw new RuntimeException('Could not allocate device code');
    }

    private function randomCharacters(int $length): string
    {
        $output = '';
        for ($i = 0; $i < $length; $i++) $output .= self::CODE_ALPHABET[random_int(0, strlen(self::CODE_ALPHABET) - 1)];
        return $output;
    }

    private function requiredString(array $input, string $key, int $maximum): string
    {
        $value = $input[$key] ?? null;
        if (!is_string($value) || $value === '' || strlen($value) > $maximum) throw new ApiProblem('INVALID_REQUEST', 400);
        return $value;
    }

    private function transaction(callable $operation): mixed
    {
        $this->db->beginTransaction();
        try { $result = $operation(); $this->db->commit(); return $result; }
        catch (Throwable $error) { if ($this->db->inTransaction()) $this->db->rollBack(); throw $error; }
    }

    private function limit(string $scope, string $subject, int $maximum, int $seconds): void
    {
        $key = hash_hmac('sha256', $scope.'|'.$subject, (string) config('rate_limit_pepper'));
        $statement = $this->db->prepare("INSERT INTO rate_limits (key_hash,scope,hit_count,window_expires_at) VALUES (:key,:scope,1,DATE_ADD(UTC_TIMESTAMP(),INTERVAL {$seconds} SECOND)) ON DUPLICATE KEY UPDATE hit_count=IF(window_expires_at<=UTC_TIMESTAMP(),1,hit_count+1),window_expires_at=IF(window_expires_at<=UTC_TIMESTAMP(),DATE_ADD(UTC_TIMESTAMP(),INTERVAL {$seconds} SECOND),window_expires_at)");
        $statement->execute(['key' => $key, 'scope' => $scope]);
        $check = $this->db->prepare('SELECT hit_count,TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(),window_expires_at) retry_after FROM rate_limits WHERE key_hash=:key');
        $check->execute(['key' => $key]);
        $row = $check->fetch();
        if ((int) $row['hit_count'] > $maximum) { header('Retry-After: '.max(1, (int) $row['retry_after'])); throw new ApiProblem('RATE_LIMITED', 429); }
    }

    private function uuid(): string
    {
        $bytes = random_bytes(16); $bytes[6] = chr((ord($bytes[6]) & 0x0f) | 0x40); $bytes[8] = chr((ord($bytes[8]) & 0x3f) | 0x80); $hex = bin2hex($bytes);
        return substr($hex,0,8).'-'.substr($hex,8,4).'-'.substr($hex,12,4).'-'.substr($hex,16,4).'-'.substr($hex,20);
    }
    private function base64Url(string $bytes): string { return rtrim(strtr(base64_encode($bytes), '+/', '-_'), '='); }
    private function base64UrlDecode(string $value): ?string
    {
        if (!preg_match('/^[A-Za-z0-9_-]+$/D', $value)) return null;
        $decoded = base64_decode(strtr($value, '-_', '+/').str_repeat('=', (4 - strlen($value) % 4) % 4), true);
        return $decoded === false ? null : $decoded;
    }
}
