<?php
declare(strict_types=1);

namespace MarsTv\Domain;

final class Encoding
{
    public static function base64UrlEncode(string $bytes): string
    {
        return rtrim(strtr(base64_encode($bytes), '+/', '-_'), '=');
    }

    public static function base64UrlDecode(string $value): string
    {
        if (!preg_match('/^[A-Za-z0-9_-]*$/D', $value)) throw new \InvalidArgumentException('Invalid base64url');
        $decoded = base64_decode(strtr($value, '-_', '+/') . str_repeat('=', (4 - strlen($value) % 4) % 4), true);
        if ($decoded === false) throw new \InvalidArgumentException('Invalid base64url');
        return $decoded;
    }

    public static function activationHash(string $normalizedCredential, string $pepper): string
    {
        return hash_hmac('sha256', $normalizedCredential, $pepper);
    }
}
