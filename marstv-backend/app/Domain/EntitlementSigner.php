<?php
declare(strict_types=1);

namespace MarsTv\Domain;

final class EntitlementSigner
{
    public const FEATURES = [
        'multiple_accounts', 'full_epg', 'unrestricted_vod_playback', 'unrestricted_series_playback',
        'global_search', 'favorite_groups', 'multiple_profiles', 'continue_watching', 'catch_up',
        'parental_controls', 'picture_in_picture', 'advanced_themes', 'recording', 'multiview',
    ];

    public function __construct(private readonly string $privateKeyPath, private readonly string $keyId) {}

    public function entitlement(array $license, array $device, ?int $now = null): string
    {
        $issuedAt = $now ?? time();
        return $this->sign('marstv-entitlement+jwt', [
            'iss' => 'https://marstv.online',
            'aud' => 'tv.mars.app:direct',
            'sub' => (string) $device['device_uuid'],
            'license_id' => (string) $license['license_uuid'],
            'license_version' => (int) $license['license_version'],
            'device_key_thumbprint' => (string) $device['public_key_thumbprint'],
            'plan_id' => (string) $license['plan_id'],
            'features' => self::FEATURES,
            'iat' => $issuedAt,
            'refresh_after' => $issuedAt + 86400,
            'token_version' => 1,
        ]);
    }

    private function sign(string $type, array $payload): string
    {
        if ($this->privateKeyPath === '' || !is_readable($this->privateKeyPath)) throw new \RuntimeException('Entitlement signing key is unavailable');
        $key = openssl_pkey_get_private((string) file_get_contents($this->privateKeyPath));
        $details = $key === false ? false : openssl_pkey_get_details($key);
        if ($details === false || $details['type'] !== OPENSSL_KEYTYPE_EC || ($details['ec']['curve_name'] ?? '') !== 'prime256v1') {
            throw new \RuntimeException('Entitlement signing key must be P-256');
        }
        $header = ['alg' => 'ES256', 'kid' => $this->keyId, 'typ' => $type];
        $input = self::base64Url(self::json($header)).'.'.self::base64Url(self::json($payload));
        if (!openssl_sign($input, $der, $key, OPENSSL_ALGO_SHA256)) throw new \RuntimeException('Entitlement signing failed');
        return $input.'.'.self::base64Url(self::derToJose($der));
    }

    private static function derToJose(string $der): string
    {
        $offset = 0;
        if (ord($der[$offset++]) !== 0x30) throw new \RuntimeException('Invalid ECDSA signature');
        self::readLength($der, $offset);
        $r = self::readInteger($der, $offset);
        $s = self::readInteger($der, $offset);
        if ($offset !== strlen($der)) throw new \RuntimeException('Invalid ECDSA signature');
        return self::unsigned32($r).self::unsigned32($s);
    }

    private static function readInteger(string $der, int &$offset): string
    {
        if ($offset >= strlen($der) || ord($der[$offset++]) !== 0x02) throw new \RuntimeException('Invalid ECDSA integer');
        $length = self::readLength($der, $offset);
        if ($length < 1 || $offset + $length > strlen($der)) throw new \RuntimeException('Invalid ECDSA integer');
        $value = substr($der, $offset, $length);
        $offset += $length;
        return $value;
    }

    private static function readLength(string $der, int &$offset): int
    {
        if ($offset >= strlen($der)) throw new \RuntimeException('Invalid DER length');
        $length = ord($der[$offset++]);
        if (($length & 0x80) === 0) return $length;
        $bytes = $length & 0x7f;
        if ($bytes < 1 || $bytes > 2 || $offset + $bytes > strlen($der)) throw new \RuntimeException('Invalid DER length');
        $length = 0;
        while ($bytes-- > 0) $length = ($length << 8) | ord($der[$offset++]);
        return $length;
    }

    private static function unsigned32(string $integer): string
    {
        $integer = ltrim($integer, "\0");
        if (strlen($integer) > 32) throw new \RuntimeException('ECDSA integer too large');
        return str_repeat("\0", 32 - strlen($integer)).$integer;
    }

    private static function json(array $value): string
    {
        return json_encode($value, JSON_UNESCAPED_SLASHES | JSON_THROW_ON_ERROR);
    }

    private static function base64Url(string $bytes): string
    {
        return rtrim(strtr(base64_encode($bytes), '+/', '-_'), '=');
    }
}
