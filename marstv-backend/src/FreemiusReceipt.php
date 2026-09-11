<?php
declare(strict_types=1);

final class FreemiusReceipt
{
    public function __construct(private readonly ?Closure $fetch = null) {}

    public function email(string $product, string $payment, string $license): string
    {
        foreach ([$product, $payment, $license] as $id) {
            if (!preg_match('/^[1-9][0-9]*$/D', $id)) throw new RuntimeException('Invalid Freemius receipt reference');
        }
        $record = $this->get("/products/{$product}/payments/{$payment}.json");
        if ((string) ($record['id'] ?? '') !== $payment || (string) ($record['license_id'] ?? '') !== $license
            || (isset($record['plugin_id']) && (string) $record['plugin_id'] !== $product)) {
            throw new RuntimeException('Freemius payment does not match the local purchase');
        }
        $userId = (string) ($record['user_id'] ?? '');
        if (!preg_match('/^[1-9][0-9]*$/D', $userId)) throw new RuntimeException('Freemius payment has no valid purchaser reference');
        $user = $this->get("/products/{$product}/users/{$userId}.json");
        if ((string) ($user['id'] ?? '') !== $userId) throw new RuntimeException('Freemius purchaser response does not match');
        $email = $user['email'] ?? null;
        if (!is_string($email) || strlen($email) > 191 || !filter_var($email, FILTER_VALIDATE_EMAIL) || preg_match('/[\r\n]/', $email)) {
            throw new RuntimeException('Freemius purchaser has no valid email');
        }
        return $email;
    }

    private function get(string $path): array
    {
        if ($this->fetch !== null) return ($this->fetch)($path);
        $token = (string) config('freemius_api_bearer_token');
        if ($token === '' || preg_match('/[\r\n]/', $token)) throw new RuntimeException('Configure FREEMIUS_API_BEARER_TOKEN in the host private .env');
        if (!function_exists('curl_init')) throw new RuntimeException('PHP cURL is required for Freemius receipt lookup');
        $curl = curl_init('https://api.freemius.com/v1'.$path);
        $body = '';
        curl_setopt_array($curl, [CURLOPT_HTTPHEADER => ['Authorization: Bearer '.$token, 'Accept: application/json'],
            CURLOPT_CONNECTTIMEOUT => 3, CURLOPT_TIMEOUT => 8, CURLOPT_FOLLOWLOCATION => false,
            CURLOPT_SSL_VERIFYPEER => true, CURLOPT_SSL_VERIFYHOST => 2,
            CURLOPT_WRITEFUNCTION => static function ($handle, string $chunk) use (&$body): int {
                if (strlen($body) + strlen($chunk) > 262144) return 0;
                $body .= $chunk;
                return strlen($chunk);
            }]);
        $ok = curl_exec($curl);
        $status = (int) curl_getinfo($curl, CURLINFO_HTTP_CODE);
        curl_close($curl);
        if ($ok === false || $status !== 200) throw new RuntimeException('Freemius receipt lookup failed (HTTP '.$status.'); verify the product API token and retry');
        try { $decoded = json_decode($body, true, 16, JSON_THROW_ON_ERROR); }
        catch (JsonException) { throw new RuntimeException('Freemius receipt response is invalid'); }
        if (!is_array($decoded)) throw new RuntimeException('Freemius receipt response is invalid');
        return $decoded;
    }

    public function repair(PDO $db, string $order, string $operator): bool
    {
        if (!preg_match('/^[1-9][0-9]*$/D', $order) || !preg_match('/^[A-Za-z0-9_.@-]{2,100}$/D', $operator)) {
            throw new RuntimeException('Provide a valid --order and --operator');
        }
        $query = $db->prepare("SELECT p.id,p.product_id,p.customer_email,l.id license_id,l.provider_license_id
            FROM purchases p JOIN licenses l ON l.purchase_id=p.id WHERE p.provider='freemius' AND p.provider_order_id=:order");
        $query->execute(['order' => $order]);
        $row = $query->fetch() ?: throw new RuntimeException('Freemius purchase not found');
        if ($row['customer_email'] !== null && $row['customer_email'] !== '') return false;
        $email = $this->email((string) $row['product_id'], $order, (string) $row['provider_license_id']);
        $db->beginTransaction();
        try {
            $update = $db->prepare("UPDATE purchases SET customer_email=:email WHERE id=:id AND (customer_email IS NULL OR customer_email='')");
            $update->execute(['email' => $email, 'id' => $row['id']]);
            $changed = $update->rowCount() === 1;
            if ($changed) {
                $uuid = bin2hex(random_bytes(16));
                $audit = $db->prepare("INSERT INTO support_actions (action_uuid,operator_id,action_type,purchase_id,license_id,reason)
                    VALUES (:uuid,:operator,'receipt_email_recovered',:purchase,:license,'Missing receipt email recovered from authenticated Freemius payment and purchaser APIs')");
                $audit->execute(['uuid' => $uuid, 'operator' => $operator, 'purchase' => $row['id'], 'license' => $row['license_id']]);
            }
            $db->commit();
            return $changed;
        } catch (Throwable $error) {
            if ($db->inTransaction()) $db->rollBack();
            throw $error;
        }
    }
}
