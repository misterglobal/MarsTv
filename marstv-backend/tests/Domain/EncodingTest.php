<?php
declare(strict_types=1);

namespace MarsTv\Tests\Domain;

use MarsTv\Domain\Encoding;
use PHPUnit\Framework\TestCase;

final class EncodingTest extends TestCase
{
    public function testBase64UrlRoundTrip(): void
    {
        $bytes = random_bytes(32);
        self::assertSame($bytes, Encoding::base64UrlDecode(Encoding::base64UrlEncode($bytes)));
    }

    public function testActivationHashIsPeppered(): void
    {
        self::assertNotSame(Encoding::activationHash('ABCD2345', 'pepper-a'), Encoding::activationHash('ABCD2345', 'pepper-b'));
    }
}
