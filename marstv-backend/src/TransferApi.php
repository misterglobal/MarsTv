<?php
declare(strict_types=1);

/** Accountless transfer: receipt-email proof, destination activation proof, then explicit confirmation. */
final class TransferApi
{
    public function __construct(
        private readonly PDO $db,
        private readonly ?Closure $mailer = null,
        private readonly ?Closure $cookieWriter = null,
    ) {}

    public function request(array $input, string $ip): array
    {
        $this->enabled();
        $email = trim($this->string($input, 'email', 191));
        $order = $this->string($input, 'order', 191);
        $activation = strtoupper(str_replace('-', '', trim($this->string($input, 'activationCode', 9))));
        if (!filter_var($email, FILTER_VALIDATE_EMAIL) || preg_match('/[\r\n]/', $email)
            || !preg_match('/^[A-Za-z0-9_-]+$/D', $order) || !preg_match('/^[A-HJ-NP-Z2-9]{8}$/D', $activation)) {
            throw new ApiProblem('INVALID_REQUEST', 400);
        }
        $this->limit('transfer_request_ip', $ip, 5, 3600);
        $this->limit('transfer_request_email', strtolower($email), 3, 3600);
        $this->limit('transfer_email_daily', strtolower($email), 6, 86400);
        $this->limit('transfer_ip_daily', $ip, 20, 86400);
        $this->limit('transfer_request_global', 'all', 1000, 3600);
        $activationHash = hash_hmac('sha256', $activation, (string) config('activation_pepper'));
        $purchase = $this->db->prepare("SELECT l.license_uuid,l.license_version,p.customer_email FROM purchases p JOIN licenses l ON l.purchase_id=p.id
            WHERE p.provider='freemius' AND p.provider_order_id=:order AND LOWER(p.customer_email)=LOWER(:email)
              AND p.status='paid' AND l.status='active' LIMIT 1");
        $purchase->execute(['order' => $order, 'email' => $email]);
        $license = $purchase->fetch() ?: [];
        $destination = $this->db->prepare("SELECT a.id,d.device_code FROM activation_sessions a JOIN devices d ON d.id=a.device_id
            WHERE a.manual_code_hash=:hash AND a.status='pending' AND a.expires_at>UTC_TIMESTAMP()
              AND a.checkout_attempt_id IS NULL AND d.status='active' LIMIT 1");
        $destination->execute(['hash' => $activationHash]);
        $device = $destination->fetch() ?: [];
        // Database collations may equate distinct addresses. Send only to the stored receipt address.
        $recipient = trim((string) ($license['customer_email'] ?? ''));
        $eligible = $license !== [] && $device !== [] && strcasecmp($recipient, $email) === 0
            && filter_var($recipient, FILTER_VALIDATE_EMAIL) && !preg_match('/[\r\n]/', $recipient);
        if ($eligible) {
            try {
                $this->limit('transfer_mail_global', 'all', 100, 3600, false);
            } catch (ApiProblem $error) {
                if ($error->errorCode !== 'TRANSFER_RATE_LIMITED') throw $error;
                $eligible = false; // Do not disclose a match when mail capacity is exhausted.
            }
        }
        $token = bin2hex(random_bytes(32));
        $csrf = bin2hex(random_bytes(32));
        $code = sprintf('%08d', random_int(0, 99999999));
        $insert = $this->db->prepare("INSERT INTO transfer_sessions
            (token_hash,csrf_hash,code_hash,license_uuid,license_version,activation_id,activation_hash,new_device_code,expires_at)
            VALUES (:token,:csrf,:code,:license,:version,:activation,:hash,:device,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 10 MINUTE))");
        $insert->execute(['token' => hash('sha256', $token), 'csrf' => hash('sha256', $csrf),
            'code' => $eligible ? $this->codeHash($token, $code) : null, 'license' => $eligible ? $license['license_uuid'] : null,
            'version' => $license['license_version'] ?? null, 'activation' => $device['id'] ?? null,
            'hash' => $activationHash, 'device' => $device['device_code'] ?? null]);
        // Keep the response and cookie shape identical for matches, non-matches and delivery failures.
        // Dummy sessions have no valid code and cannot authorize a transfer.
        if ($eligible) {
            $body = "Your MarsTV Pro transfer verification code is: {$code}\n\nIt expires in 10 minutes. Enter it only in the transfer portal you opened. This code does not move a licence until you confirm the transfer.\n\nIf you did not request this, ignore this email. Never share this code with anyone.";
            try {
                $sent = $this->mailer !== null ? ($this->mailer)($recipient, $body) : @mail($recipient, 'MarsTV transfer verification code', $body, [
                    'From' => (string) config('transfer_mail_from'), 'Content-Type' => 'text/plain; charset=UTF-8',
                ]);
            } catch (Throwable) {
                $sent = false;
            }
            if (!$sent) {
                $this->db->prepare('UPDATE transfer_sessions SET code_hash=NULL WHERE token_hash=:token')
                    ->execute(['token' => hash('sha256', $token)]);
                error_log('MarsTV transfer verification delivery failed');
            }
        }
        $options = ['expires' => time() + 600, 'path' => '/api/v1/transfers',
            'secure' => config('environment') !== 'local', 'httponly' => true, 'samesite' => 'Strict'];
        if ($this->cookieWriter !== null) ($this->cookieWriter)('marstv_transfer', $token, $options);
        else setcookie('marstv_transfer', $token, $options);
        return ['status' => 'request_received', 'csrfToken' => $csrf];
    }

    public function verify(array $input, array $headers, array $cookies, string $ip): array
    {
        $this->enabled();
        $this->limit('transfer_verify_ip', $ip, 20, 600);
        $code = $this->string($input, 'code', 8);
        $this->db->beginTransaction();
        try {
            [$row, $token] = $this->session($headers, $cookies);
            if ($row['consumed_at'] !== null || ($row['verified_at'] === null && (int) $row['attempts'] >= 5)) {
                throw new ApiProblem('TRANSFER_SESSION_EXPIRED', 410);
            }
            if ($row['verified_at'] === null) {
                $this->db->prepare('UPDATE transfer_sessions SET attempts=attempts+1 WHERE id=:id')->execute(['id' => $row['id']]);
                if (!preg_match('/^[0-9]{8}$/D', $code) || !hash_equals((string) $row['code_hash'], $this->codeHash($token, $code))) {
                    $this->db->commit(); // Failed attempts must survive the error response.
                    throw new ApiProblem('TRANSFER_CODE_INVALID', 422);
                }
                if ($row['license_uuid'] === null || $row['activation_id'] === null) {
                    $this->db->prepare('UPDATE transfer_sessions SET code_hash=NULL,consumed_at=UTC_TIMESTAMP() WHERE id=:id')->execute(['id' => $row['id']]);
                    $this->db->commit();
                    throw new ApiProblem('TRANSFER_DETAILS_INVALID', 422);
                }
            }
            $preview = (new SupportService($this->db))->previewTransfer($row['license_uuid'], $row['new_device_code']);
            if ($preview['license_status'] !== 'active' || (int) $preview['license_version'] !== (int) $row['license_version']) {
                throw new ApiProblem('TRANSFER_CHANGED', 409);
            }
            if ($preview['occupying_license_uuid'] !== null && $preview['occupying_license_uuid'] !== $row['license_uuid']) {
                throw new ApiProblem('TRANSFER_DESTINATION_LICENSED', 409);
            }
            $this->db->prepare('UPDATE transfer_sessions SET verified_at=UTC_TIMESTAMP(),code_hash=NULL WHERE id=:id')->execute(['id' => $row['id']]);
            $this->db->commit();
            return ['status' => 'verified', 'currentDevice' => $preview['current_device_code'], 'newDevice' => $row['new_device_code']];
        } catch (Throwable $error) {
            if ($this->db->inTransaction()) $this->db->rollBack();
            throw $error;
        }
    }

    public function confirm(array $input, array $headers, array $cookies, string $ip): array
    {
        $this->enabled();
        $this->limit('transfer_confirm_ip', $ip, 10, 600);
        if (($input['confirmed'] ?? false) !== true) throw new ApiProblem('INVALID_REQUEST', 400);
        $this->db->beginTransaction();
        try {
            [$row] = $this->session($headers, $cookies);
            if ($row['verified_at'] === null) throw new ApiProblem('TRANSFER_VERIFICATION_REQUIRED', 403);
            if ($row['consumed_at'] !== null) {
                $this->db->commit();
                return ['status' => 'transferred', 'newDevice' => $row['new_device_code']];
            }
            $activation = $this->db->prepare("SELECT a.id FROM activation_sessions a JOIN devices d ON d.id=a.device_id
                WHERE a.id=:id AND a.manual_code_hash=:hash AND a.status='pending' AND a.expires_at>UTC_TIMESTAMP()
                  AND a.checkout_attempt_id IS NULL AND d.device_code=:device AND d.status='active' FOR UPDATE");
            $activation->execute(['id' => $row['activation_id'], 'hash' => $row['activation_hash'], 'device' => $row['new_device_code']]);
            if (!$activation->fetch()) throw new ApiProblem('TRANSFER_ACTIVATION_EXPIRED', 410);
            // Recheck payment state inside the same transaction as reassignment.
            $purchase = $this->db->prepare("SELECT p.id,p.status FROM purchases p JOIN licenses l ON l.purchase_id=p.id WHERE l.license_uuid=:uuid FOR UPDATE");
            $purchase->execute(['uuid' => $row['license_uuid']]);
            $paid = $purchase->fetch();
            if (!$paid || $paid['status'] !== 'paid') throw new ApiProblem('TRANSFER_CHANGED', 409);
            try {
                (new SupportService($this->db))->transferLicense($row['license_uuid'], $row['new_device_code'],
                    'customer_email', 'Receipt email verified; portal session '.$row['id'], false, (int) $row['license_version']);
            } catch (PDOException $error) {
                throw $error;
            } catch (RuntimeException $error) {
                $code = str_contains($error->getMessage(), 'transfer limit') ? 'TRANSFER_LIMIT_REACHED' : 'TRANSFER_CHANGED';
                throw new ApiProblem($code, 409);
            }
            $this->db->prepare("UPDATE activation_sessions SET status='transferred',manual_code_hash=NULL,qr_secret_hash=NULL WHERE id=:id")
                ->execute(['id' => $row['activation_id']]);
            $this->db->prepare('UPDATE transfer_sessions SET consumed_at=UTC_TIMESTAMP() WHERE id=:id')->execute(['id' => $row['id']]);
            $this->db->commit();
            return ['status' => 'transferred', 'newDevice' => $row['new_device_code']];
        } catch (Throwable $error) {
            if ($this->db->inTransaction()) $this->db->rollBack();
            throw $error;
        }
    }

    private function session(array $headers, array $cookies): array
    {
        $token = $cookies['marstv_transfer'] ?? '';
        $csrf = $headers['x-csrf-token'] ?? '';
        if (!is_string($token) || !is_string($csrf) || !preg_match('/^[a-f0-9]{64}$/D', $token) || !preg_match('/^[a-f0-9]{64}$/D', $csrf)) {
            throw new ApiProblem('TRANSFER_VERIFICATION_REQUIRED', 403);
        }
        $query = $this->db->prepare('SELECT * FROM transfer_sessions WHERE token_hash=:hash AND expires_at>UTC_TIMESTAMP() FOR UPDATE');
        $query->execute(['hash' => hash('sha256', $token)]);
        $row = $query->fetch();
        if (!$row || !hash_equals($row['csrf_hash'], hash('sha256', $csrf))) throw new ApiProblem('TRANSFER_SESSION_EXPIRED', 410);
        return [$row, $token];
    }

    private function enabled(): void
    {
        $from = (string) config('transfer_mail_from');
        if (!config('transfer_portal_enabled') || strlen((string) config('transfer_code_pepper')) < 32
            || !filter_var($from, FILTER_VALIDATE_EMAIL) || preg_match('/[\r\n]/', $from)) {
            throw new ApiProblem('TRANSFER_UNAVAILABLE', 503);
        }
    }

    private function codeHash(string $token, string $code): string
    {
        return hash_hmac('sha256', $token.'|'.$code, (string) config('transfer_code_pepper'));
    }

    private function string(array $input, string $key, int $maximum): string
    {
        $value = $input[$key] ?? null;
        if (!is_string($value) || $value === '' || strlen($value) > $maximum) throw new ApiProblem('INVALID_REQUEST', 400);
        return $value;
    }

    private function limit(string $scope, string $subject, int $maximum, int $seconds, bool $retryHeader = true): void
    {
        $hash = hash_hmac('sha256', $scope.'|'.$subject, (string) config('rate_limit_pepper'));
        $query = $this->db->prepare("INSERT INTO rate_limits (key_hash,scope,hit_count,window_expires_at)
            VALUES (:hash,:scope,1,DATE_ADD(UTC_TIMESTAMP(),INTERVAL :seconds SECOND))
            ON DUPLICATE KEY UPDATE hit_count=IF(window_expires_at<=UTC_TIMESTAMP(),1,hit_count+1),
            window_expires_at=IF(window_expires_at<=UTC_TIMESTAMP(),DATE_ADD(UTC_TIMESTAMP(),INTERVAL :again SECOND),window_expires_at)");
        $query->execute(['hash' => $hash, 'scope' => $scope, 'seconds' => $seconds, 'again' => $seconds]);
        $query = $this->db->prepare('SELECT hit_count FROM rate_limits WHERE key_hash=:hash');
        $query->execute(['hash' => $hash]);
        if ((int) $query->fetchColumn() > $maximum) {
            if ($retryHeader) header('Retry-After: '.$seconds);
            throw new ApiProblem('TRANSFER_RATE_LIMITED', 429);
        }
    }
}
