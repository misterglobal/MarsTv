<?php
declare(strict_types=1);

use PHPUnit\Framework\TestCase;

require_once dirname(__DIR__).'/src/ActivationApi.php';
require_once dirname(__DIR__).'/src/SupportService.php';
require_once dirname(__DIR__).'/src/TransferApi.php';
require_once dirname(__DIR__).'/src/FreemiusReceipt.php';

// These tests never load private deployment configuration or send mail.
if (!function_exists('config')) {
    function config(string $key): mixed { return $GLOBALS['transfer_test_config'][$key] ?? null; }
}

final class TransferApiTest extends TestCase
{
    private static bool $schemaReady = false;
    private PDO $db;
    private TransferApi $api;
    private array $cookies = [];
    private array $headers = [];
    private string $code = '';
    private array $recipients = [];
    private const LICENSE = '11111111-1111-4111-8111-111111111111';

    protected function setUp(): void
    {
        $dsn = getenv('MARSTV_TRANSFER_TEST_DSN') ?: '';
        if ($dsn === '') self::markTestSkipped('Set MARSTV_TRANSFER_TEST_DSN for disposable MySQL integration tests.');
        if (!preg_match('/^mysql:host=127\.0\.0\.1;port=[0-9]+;dbname=marstv_transfer_test$/D', $dsn)) {
            self::fail('Integration tests require the dedicated localhost marstv_transfer_test database.');
        }
        $this->db = new PDO($dsn, 'root', 'marstv-isolated-test', [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC, PDO::ATTR_EMULATE_PREPARES => false]);
        $this->db->exec("SET time_zone='+00:00'");
        if (!self::$schemaReady) {
            // The DSN above is restricted to our disposable localhost test database.
            $this->db->exec('SET SESSION explicit_defaults_for_timestamp=ON');
            $this->db->exec('SET FOREIGN_KEY_CHECKS=0');
            foreach ($this->db->query('SHOW TABLES')->fetchAll(PDO::FETCH_COLUMN) as $table) {
                $this->db->exec('DROP TABLE `'.str_replace('`', '``', $table).'`');
            }
            $this->db->exec('SET FOREIGN_KEY_CHECKS=1');
            foreach (glob(dirname(__DIR__).'/database/migrations/*.sql') as $path) $this->db->exec(file_get_contents($path));
            self::$schemaReady = true;
        }
        foreach (['transfer_sessions','freemius_checkout_claims','support_actions','license_assignments','device_challenges',
            'webhook_events','licenses','purchases','activation_sessions','devices','rate_limits'] as $table) $this->db->exec('DELETE FROM '.$table);
        $GLOBALS['transfer_test_config'] = ['transfer_portal_enabled' => true, 'transfer_mail_from' => 'test@example.test',
            'transfer_code_pepper' => str_repeat('x', 32), 'activation_pepper' => 'activation-test',
            'rate_limit_pepper' => 'rate-test', 'environment' => 'local'];
        $this->db->exec("INSERT INTO devices (id,device_uuid,device_code,public_key_spki,public_key_thumbprint)
            VALUES (1,'old','MARS-AAAA','test','old'),(2,'new','MARS-BBBB','test','new'),(3,'third','MARS-CCCC','test','third')");
        $this->db->exec("INSERT INTO activation_sessions (id,session_uuid,device_id,status,expires_at)
            VALUES (1,'purchase-session',1,'paid',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR)),
                   (2,'destination-session',2,'pending',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 10 MINUTE))");
        $this->db->prepare('UPDATE activation_sessions SET manual_code_hash=:hash WHERE id=2')->execute([
            'hash' => hash_hmac('sha256', 'ABCDEFGH', 'activation-test')]);
        $this->db->exec("INSERT INTO purchases (id,provider,provider_order_id,activation_session_id,customer_email,product_id,status)
            VALUES (1,'freemius','12345',1,'owner@example.test','pro_lifetime_v1','paid')");
        $this->db->exec("INSERT INTO licenses (id,license_uuid,purchase_id,current_device_id,plan_id,provider_license_id,status,license_version)
            VALUES (1,'".self::LICENSE."',1,1,'pro_lifetime_v1','test-provider-license','active',1)");
        $this->db->exec("INSERT INTO license_assignments (license_id,device_id,assignment_reason) VALUES (1,1,'purchase')");
        $this->api = new TransferApi($this->db, function (string $email, string $body): bool {
            $this->recipients[] = $email;
            self::assertStringNotContainsString('12345', $body);
            preg_match('/code is: ([0-9]{8})/', $body, $matches);
            $this->code = $matches[1];
            return true;
        }, function (string $name, string $value, array $options): void {
            self::assertTrue($options['httponly']);
            self::assertSame('Strict', $options['samesite']);
            $this->cookies[$name] = $value;
        });
    }

    private function request(string $email = 'owner@example.test'): void
    {
        $result = $this->api->request(['email' => $email, 'order' => '12345', 'activationCode' => 'ABCD-EFGH'], '127.0.0.1');
        self::assertSame('request_received', $result['status']);
        $this->headers = ['x-csrf-token' => $result['csrfToken']];
    }

    private function verify(): array { return $this->api->verify(['code' => $this->code], $this->headers, $this->cookies, '127.0.0.1'); }
    private function confirm(): array { return $this->api->confirm(['confirmed' => true], $this->headers, $this->cookies, '127.0.0.1'); }
    private function problem(string $code, Closure $operation): void
    {
        try { $operation(); self::fail('Expected '.$code); }
        catch (ApiProblem $error) { self::assertSame($code, $error->errorCode); }
    }

    public function testTransferIsAtomicAuditedAndRetryDoesNotCountTwice(): void
    {
        $this->request();
        $preview = $this->verify();
        self::assertSame('MARS-AAAA', $preview['currentDevice']);
        self::assertSame('MARS-BBBB', $preview['newDevice']);
        self::assertSame($preview, $this->verify()); // Lost verification response can be retried.
        self::assertSame('transferred', $this->confirm()['status']);
        self::assertSame('transferred', $this->confirm()['status']);
        $license = $this->db->query('SELECT * FROM licenses WHERE id=1')->fetch();
        self::assertSame(2, (int) $license['current_device_id']);
        self::assertSame(2, (int) $license['license_version']);
        self::assertSame(1, (int) $this->db->query('SELECT COUNT(*) FROM support_actions')->fetchColumn());
        self::assertSame(1, (int) $this->db->query('SELECT COUNT(*) FROM license_assignments WHERE unassigned_at IS NULL')->fetchColumn());
        self::assertNull($this->db->query('SELECT manual_code_hash FROM activation_sessions WHERE id=2')->fetchColumn());
        self::assertSame('customer_email', $this->db->query('SELECT operator_id FROM support_actions')->fetchColumn());
    }

    public function testEmailAndOrderMustMatchBeforePreview(): void
    {
        $this->request('stranger@example.test');
        self::assertSame([], $this->recipients);
        $this->code = '00000000';
        $this->problem('TRANSFER_CODE_INVALID', fn() => $this->verify());
        $this->problem('TRANSFER_VERIFICATION_REQUIRED', fn() => $this->confirm());
        self::assertSame(1, (int) $this->db->query('SELECT current_device_id FROM licenses')->fetchColumn());
    }

    public function testRequiresVerificationAndCsrf(): void
    {
        $this->request();
        $this->problem('TRANSFER_VERIFICATION_REQUIRED', fn() => $this->confirm());
        $this->headers['x-csrf-token'] = str_repeat('0', 64);
        $this->problem('TRANSFER_SESSION_EXPIRED', fn() => $this->verify());
    }

    public function testWrongCodesConsumeAttemptBudget(): void
    {
        $this->request();
        $correct = $this->code;
        $this->code = $correct === '00000000' ? '11111111' : '00000000';
        for ($i = 0; $i < 5; $i++) $this->problem('TRANSFER_CODE_INVALID', fn() => $this->verify());
        $this->code = $correct;
        $this->problem('TRANSFER_SESSION_EXPIRED', fn() => $this->verify());
        self::assertSame(5, (int) $this->db->query('SELECT attempts FROM transfer_sessions')->fetchColumn());
    }

    public function testExpiredSessionCannotVerify(): void
    {
        $this->request();
        $this->db->exec('UPDATE transfer_sessions SET expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND)');
        $this->problem('TRANSFER_SESSION_EXPIRED', fn() => $this->verify());
    }

    public function testExpiredDestinationDoesNotConsumeTransferOrMoveLicense(): void
    {
        $this->request(); $this->verify();
        $this->db->exec('UPDATE activation_sessions SET expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND) WHERE id=2');
        $this->problem('TRANSFER_ACTIVATION_EXPIRED', fn() => $this->confirm());
        self::assertNull($this->db->query('SELECT consumed_at FROM transfer_sessions')->fetchColumn());
        self::assertSame(0, (int) $this->db->query('SELECT COUNT(*) FROM support_actions')->fetchColumn());
    }

    public function testConcurrentLicenseChangeInvalidatesApproval(): void
    {
        $this->request(); $this->verify();
        $this->db->exec('UPDATE licenses SET license_version=2');
        $this->problem('TRANSFER_CHANGED', fn() => $this->confirm());
        self::assertSame(1, (int) $this->db->query('SELECT current_device_id FROM licenses')->fetchColumn());
    }

    public function testRefundBetweenVerificationAndConfirmationIsRejected(): void
    {
        $this->request(); $this->verify();
        $this->db->exec("UPDATE purchases SET status='refunded'");
        $this->problem('TRANSFER_CHANGED', fn() => $this->confirm());
    }

    public function testPortalAndSupportShareThirtyDayLimit(): void
    {
        $this->db->exec("INSERT INTO support_actions (action_uuid,operator_id,action_type,license_id,reason)
            VALUES ('earlier','support','license_transfer',1,'earlier transfer')");
        $this->request(); $this->verify();
        $this->problem('TRANSFER_LIMIT_REACHED', fn() => $this->confirm());
        self::assertSame('pending', $this->db->query('SELECT status FROM activation_sessions WHERE id=2')->fetchColumn());
    }

    public function testAnnualLimitAlsoApplies(): void
    {
        for ($i = 1; $i <= 3; $i++) $this->db->exec("INSERT INTO support_actions (action_uuid,operator_id,action_type,license_id,reason,created_at)
            VALUES ('earlier-{$i}','support','license_transfer_override',1,'earlier transfer',DATE_SUB(UTC_TIMESTAMP(),INTERVAL 60 DAY))");
        $this->request(); $this->verify();
        $this->problem('TRANSFER_LIMIT_REACHED', fn() => $this->confirm());
    }

    public function testDisabledPortalRejectsWithoutCreatingSessions(): void
    {
        $GLOBALS['transfer_test_config']['transfer_portal_enabled'] = false;
        $this->problem('TRANSFER_UNAVAILABLE', fn() => $this->request());
        self::assertSame(0, (int) $this->db->query('SELECT COUNT(*) FROM transfer_sessions')->fetchColumn());
    }

    public function testDeliveryFailureInvalidatesChallenge(): void
    {
        $this->api = new TransferApi($this->db, fn() => false, function ($name, $token) { $this->cookies[$name] = $token; });
        $this->request();
        self::assertNull($this->db->query('SELECT code_hash FROM transfer_sessions')->fetchColumn());
        $this->code = '00000000';
        $this->problem('TRANSFER_CODE_INVALID', fn() => $this->verify());
    }

    public function testAuditFailureRollsBackAssignmentAndSessionTogether(): void
    {
        $this->request(); $this->verify();
        $this->db->exec("CREATE TRIGGER test_audit_failure BEFORE INSERT ON support_actions FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='simulated audit outage'");
        try {
            try { $this->confirm(); self::fail('Expected database failure'); }
            catch (PDOException $error) { self::assertStringContainsString('simulated audit outage', $error->getMessage()); }
            self::assertSame(1, (int) $this->db->query('SELECT current_device_id FROM licenses')->fetchColumn());
            self::assertSame(1, (int) $this->db->query('SELECT license_version FROM licenses')->fetchColumn());
            self::assertSame(1, (int) $this->db->query('SELECT device_id FROM license_assignments WHERE unassigned_at IS NULL')->fetchColumn());
            self::assertNull($this->db->query('SELECT consumed_at FROM transfer_sessions')->fetchColumn());
            self::assertSame('pending', $this->db->query('SELECT status FROM activation_sessions WHERE id=2')->fetchColumn());
        } finally { $this->db->exec('DROP TRIGGER test_audit_failure'); }
        self::assertSame('transferred', $this->confirm()['status']);
    }

    public function testUsedActivationCannotAuthorizeTransfer(): void
    {
        $this->request(); $this->verify();
        $this->db->exec("UPDATE activation_sessions SET status='redeemed',manual_code_hash=NULL WHERE id=2");
        $this->problem('TRANSFER_ACTIVATION_EXPIRED', fn() => $this->confirm());
    }

    public function testSupportTransferStillCommitsAndSameDeviceRetryDoesNotCount(): void
    {
        $service = new SupportService($this->db);
        self::assertTrue($service->transferLicense(self::LICENSE, 'MARS-BBBB', 'support', 'verified fixture')['changed']);
        self::assertFalse($this->db->inTransaction());
        self::assertFalse($service->transferLicense(self::LICENSE, 'MARS-BBBB', 'support', 'retry')['changed']);
        self::assertSame(1, (int) $this->db->query('SELECT COUNT(*) FROM support_actions')->fetchColumn());
    }

    public function testBlockedDestinationIsRejected(): void
    {
        $this->request(); $this->verify();
        $this->db->exec("UPDATE devices SET status='blocked' WHERE id=2");
        $this->problem('TRANSFER_ACTIVATION_EXPIRED', fn() => $this->confirm());
    }
    public function testInvalidActivationDoesNotSendEmail(): void
    {
        $this->db->exec("UPDATE activation_sessions SET status='expired' WHERE id=2");
        $this->request();
        self::assertSame([], $this->recipients);
        self::assertNull($this->db->query('SELECT code_hash FROM transfer_sessions')->fetchColumn());
    }

    public function testRefundedPurchaseDoesNotSendEmail(): void
    {
        $this->db->exec("UPDATE purchases SET status='refunded'");
        $this->request();
        self::assertSame([], $this->recipients);
    }

    public function testMailAlwaysUsesStoredReceiptAddress(): void
    {
        $this->request('OWNER@example.test');
        self::assertSame(['owner@example.test'], $this->recipients);
        self::assertSame('verified', $this->verify()['status']);
    }

    public function testUnknownOrderDoesNotSendEmail(): void
    {
        $this->db->exec("UPDATE purchases SET provider_order_id='other-order'");
        $this->request();
        self::assertSame([], $this->recipients);
        self::assertSame(0, (int) $this->db->query("SELECT COUNT(*) FROM rate_limits WHERE scope='transfer_mail_global'")->fetchColumn());
    }

    public function testMailQuotaExhaustionKeepsGenericResponseAndDoesNotSend(): void
    {
        $this->db->prepare("INSERT INTO rate_limits (key_hash,scope,hit_count,window_expires_at)
            VALUES (:hash,'transfer_mail_global',100,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))")
            ->execute(['hash' => hash_hmac('sha256', 'transfer_mail_global|all', 'rate-test')]);
        $this->request();
        self::assertSame([], $this->recipients);
        self::assertNull($this->db->query('SELECT code_hash FROM transfer_sessions')->fetchColumn());
    }

    public function testMissingReceiptEmailRepairIsAuditedAndEnablesVerification(): void
    {
        $this->db->exec("UPDATE purchases SET customer_email=NULL");
        $this->db->exec("UPDATE licenses SET provider_license_id='456'");
        $api = new FreemiusReceipt(fn($path) => str_contains($path, '/payments/')
            ? ['id' => 12345, 'license_id' => 456, 'user_id' => 789, 'plugin_id' => 38872]
            : ['id' => 789, 'email' => 'owner@example.test']);
        $this->db->exec("UPDATE purchases SET product_id='38872'");
        self::assertTrue($api->repair($this->db, '12345', 'support'));
        self::assertFalse($api->repair($this->db, '12345', 'support'));
        self::assertSame('owner@example.test', $this->db->query('SELECT customer_email FROM purchases')->fetchColumn());
        self::assertSame('receipt_email_recovered', $this->db->query('SELECT action_type FROM support_actions')->fetchColumn());
        self::assertSame(1, (int) $this->db->query('SELECT license_version FROM licenses')->fetchColumn());
        $this->request();
        self::assertSame('verified', $this->verify()['status']);
    }

    public function testReceiptRepairRejectsMismatchedProviderPayment(): void
    {
        $this->db->exec("UPDATE purchases SET customer_email=NULL,product_id='38872'");
        $this->db->exec("UPDATE licenses SET provider_license_id='456'");
        $api = new FreemiusReceipt(fn() => ['id' => 99999, 'license_id' => 456, 'user_id' => 789]);
        try { $api->repair($this->db, '12345', 'support'); self::fail('Expected rejection'); }
        catch (RuntimeException) { self::assertNull($this->db->query('SELECT customer_email FROM purchases')->fetchColumn()); }
        self::assertSame(0, (int) $this->db->query('SELECT COUNT(*) FROM support_actions')->fetchColumn());
    }

}
