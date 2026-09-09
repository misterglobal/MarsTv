<?php
declare(strict_types=1);

namespace MarsTv\Tests\Domain;

use MarsTv\Domain\DeviceChallenge;
use PHPUnit\Framework\TestCase;

final class DeviceChallengeTest extends TestCase
{
    public function testExactSignedBytes(): void
    {
        $challenge = new DeviceChallenge('id', 'nonce', 'device', 'POST', '/api/v1/activation-sessions', 'hash', 123);
        self::assertSame("MARSTV_DEVICE_AUTH_V1\nid\nnonce\ndevice\nPOST\n/api/v1/activation-sessions\nhash\n123", $challenge->signedBytes());
    }
}
