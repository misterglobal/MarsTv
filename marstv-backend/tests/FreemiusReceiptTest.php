<?php
declare(strict_types=1);
use PHPUnit\Framework\TestCase;
require_once dirname(__DIR__).'/src/FreemiusReceipt.php';

final class FreemiusReceiptTest extends TestCase
{
    public function testFollowsPaymentOwnerAndReturnsVerifiedEmail(): void
    {
        $paths = [];
        $api = new FreemiusReceipt(function ($path) use (&$paths) {
            $paths[] = $path;
            return count($paths) === 1 ? ['id' => 123, 'license_id' => 456, 'user_id' => 789, 'plugin_id' => 999]
                : ['id' => 789, 'email' => 'owner@example.test'];
        });
        self::assertSame('owner@example.test', $api->email('999', '123', '456'));
        self::assertSame(['/products/999/payments/123.json', '/products/999/users/789.json'], $paths);
    }

    public function testRejectsUnrelatedLicenseBeforeLookingUpEmail(): void
    {
        $calls = 0;
        $api = new FreemiusReceipt(function () use (&$calls) { $calls++; return ['id' => 123, 'license_id' => 999, 'user_id' => 789]; });
        try { $api->email('999', '123', '456'); self::fail('Expected rejection'); }
        catch (RuntimeException) { self::assertSame(1, $calls); }
    }

    public function testRejectsUnrelatedUser(): void
    {
        $api = new FreemiusReceipt(fn($path) => str_contains($path, '/payments/')
            ? ['id' => 123, 'license_id' => 456, 'user_id' => 789] : ['id' => 888, 'email' => 'wrong@example.test']);
        $this->expectException(RuntimeException::class);
        $api->email('999', '123', '456');
    }

    public function testRejectsInvalidEmail(): void
    {
        $api = new FreemiusReceipt(fn($path) => str_contains($path, '/payments/')
            ? ['id' => 123, 'license_id' => 456, 'user_id' => 789] : ['id' => 789, 'email' => "owner@example.test\r\nBcc: other@example.test"]);
        $this->expectException(RuntimeException::class);
        $api->email('999', '123', '456');
    }
}
