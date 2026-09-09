<?php
declare(strict_types=1);

namespace MarsTv\Tests\Domain;

use MarsTv\Domain\EntitlementSigner;
use PHPUnit\Framework\TestCase;

final class EntitlementSignerTest extends TestCase
{
    private string $privatePath;
    private string $publicKey;

    protected function setUp(): void
    {
        $key = openssl_pkey_new(['private_key_type' => OPENSSL_KEYTYPE_EC, 'curve_name' => 'prime256v1']);
        self::assertNotFalse($key);
        openssl_pkey_export($key, $pem);
        $this->publicKey = (string) openssl_pkey_get_details($key)['key'];
        $this->privatePath = tempnam(sys_get_temp_dir(), 'mars-key-');
        file_put_contents($this->privatePath, $pem);
    }

    protected function tearDown(): void { @unlink($this->privatePath); }

    public function testSignsAndroidCompatibleLifetimeEntitlement(): void
    {
        $token = (new EntitlementSigner($this->privatePath, 'entitlement-2026-01'))->entitlement(
            ['license_uuid' => 'license-id', 'license_version' => 4, 'plan_id' => 'pro_lifetime_v1'],
            ['device_uuid' => 'device-id', 'public_key_thumbprint' => 'thumbprint'],
            1_800_000_000,
        );
        $parts = explode('.', $token);
        self::assertCount(3, $parts);
        $header = json_decode($this->decode($parts[0]), true, flags: JSON_THROW_ON_ERROR);
        $payload = json_decode($this->decode($parts[1]), true, flags: JSON_THROW_ON_ERROR);
        self::assertSame(['alg' => 'ES256', 'kid' => 'entitlement-2026-01', 'typ' => 'marstv-entitlement+jwt'], $header);
        self::assertSame('https://marstv.online', $payload['iss']);
        self::assertSame('tv.mars.app:direct', $payload['aud']);
        self::assertSame(4, $payload['license_version']);
        self::assertArrayNotHasKey('exp', $payload);
        $jose = $this->decode($parts[2]);
        self::assertSame(64, strlen($jose));
        self::assertSame(1, openssl_verify($parts[0].'.'.$parts[1], $this->joseToDer($jose), $this->publicKey, OPENSSL_ALGO_SHA256));
    }

    public function testSignsAndroidCompatibleRevocation(): void
    {
        $token = (new EntitlementSigner($this->privatePath, 'entitlement-2026-01'))->revocation(
            ['license_uuid' => 'license-id', 'license_version' => 5],
            ['device_uuid' => 'device-id'],
            'refunded',
            1_800_000_000,
        );
        $parts = explode('.', $token);
        self::assertCount(3, $parts);
        $header = json_decode($this->decode($parts[0]), true, flags: JSON_THROW_ON_ERROR);
        $payload = json_decode($this->decode($parts[1]), true, flags: JSON_THROW_ON_ERROR);
        self::assertSame('marstv-revocation+jwt', $header['typ']);
        self::assertSame('device-id', $payload['sub']);
        self::assertSame('license-id', $payload['license_id']);
        self::assertSame(5, $payload['license_version']);
        self::assertSame('revoked', $payload['status']);
        self::assertSame('refunded', $payload['reason_code']);
        self::assertSame(1, openssl_verify($parts[0].'.'.$parts[1], $this->joseToDer($this->decode($parts[2])), $this->publicKey, OPENSSL_ALGO_SHA256));
    }

    private function decode(string $value): string
    {
        return (string) base64_decode(strtr($value, '-_', '+/').str_repeat('=', (4 - strlen($value) % 4) % 4), true);
    }

    private function joseToDer(string $jose): string
    {
        $integer = static function (string $bytes): string {
            $bytes = ltrim($bytes, "\0") ?: "\0";
            return (ord($bytes[0]) & 0x80) !== 0 ? "\0".$bytes : $bytes;
        };
        $r = $integer(substr($jose, 0, 32));
        $s = $integer(substr($jose, 32, 32));
        $body = "\x02".chr(strlen($r)).$r."\x02".chr(strlen($s)).$s;
        return "\x30".chr(strlen($body)).$body;
    }
}
