<?php

declare(strict_types=1);

require_once dirname(__DIR__).'/src/FreemiusWebhook.php';

use PHPUnit\Framework\TestCase;

final class FreemiusWebhookTest extends TestCase
{
    public function testAcceptsMatchingHmacSignature(): void
    {
        $body = '{"id":"123","type":"payment.created"}';
        $signature = hash_hmac('sha256', $body, 'test-secret');

        self::assertTrue(FreemiusWebhook::validSignature($body, $signature, 'test-secret'));
    }

    public function testRejectsInvalidOrMissingSignature(): void
    {
        self::assertFalse(FreemiusWebhook::validSignature('{}', str_repeat('0', 64), 'test-secret'));
        self::assertFalse(FreemiusWebhook::validSignature('{}', '', 'test-secret'));
        self::assertFalse(FreemiusWebhook::validSignature('{}', hash_hmac('sha256', '{}', 'test-secret'), ''));
    }

    public function testPurchaseTransitionKeepsRefundsAndChargebacksTerminal(): void
    {
        self::assertSame('apply', FreemiusWebhook::purchaseTransition('paid', 'refunded'));
        self::assertSame('apply', FreemiusWebhook::purchaseTransition('paid', 'chargeback'));
        self::assertSame('terminal_duplicate', FreemiusWebhook::purchaseTransition('refunded', 'refunded'));
        self::assertSame('reconciliation_required', FreemiusWebhook::purchaseTransition('refunded', 'paid'));
        self::assertSame('reconciliation_required', FreemiusWebhook::purchaseTransition('chargeback', 'paid'));
        self::assertSame('reconciliation_required', FreemiusWebhook::purchaseTransition('cancelled', 'paid'));
        self::assertSame('stale', FreemiusWebhook::purchaseTransition('paid', 'cancelled'));
    }
}
