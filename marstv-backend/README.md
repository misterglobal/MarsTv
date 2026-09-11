# MarsTV licensing backend

Slim 4/PHP 8.2 service for the accountless, device-bound MarsTV Pro licence described in the product requirements.

The backend supports the complete device registration, browser activation, Freemius checkout, signed webhook fulfilment, and device-bound entitlement flow. Keep checkout in sandbox while validating a deployment; enable live checkout only after its environment, product, plan, amount, currency, webhook signature, and entitlement delivery have all been verified.

## Local setup

1. Install PHP 8.2+, Composer, MySQL 8, OpenSSL and PDO MySQL.
2. Run `composer install --no-dev` for production or `composer install` for development.
3. Copy `.env.example` to `.env` and provide secrets outside the public web root. The active configuration uses the `MARSTV_*` names loaded by `src/bootstrap.php`.
4. Apply the numbered files in `database/migrations/` in order with a migration user.
5. Give the runtime database user only the required `SELECT`, `INSERT`, `UPDATE` and `DELETE` privileges on this database; it must not have schema-management privileges.
6. Point the web-server document root at `public/`.

Run `composer test` before deployment. Keep `FREEMIUS_CHECKOUT_ENABLED=false` until the full payment and webhook acceptance suite passes in the target environment. Never commit `.env`, signing private keys, activation/rate-limit peppers, logs, customer information, webhook payloads, or database exports.

For a closed-beta test device that has already registered, grant an idempotent audited licence without editing database rows:

`php bin/marstv-support test-license:grant --device=MARS-XXXX --operator=YOUR_ID --reason="closed beta test"`

The next authenticated device status check returns a freshly signed, non-expiring, device-bound entitlement. This command creates a zero-value support-test purchase and must not be used to represent a real payment.

Protected operator support operations remain available through the CLI:

```text
php bin/marstv-support purchase:lookup --order=PROVIDER_ORDER_ID
php bin/marstv-support purchase:lookup --email=RECEIPT_EMAIL
php bin/marstv-support license:transfer --license=UUID --new-device=MARS-XXXX --operator=YOUR_ID --reason="verified replacement"
php bin/marstv-support license:revoke --license=UUID --operator=YOUR_ID --reason="verified support request"
php bin/marstv-support webhook:replay --event=PROVIDER_EVENT_ID --operator=YOUR_ID --reason="retry after corrected outage"
php bin/marstv-support webhook:work --limit=25
```

Transfers print a summary and require the operator to type `TRANSFER` before making changes. They enforce one completed transfer per rolling 30 days and three per rolling 365 days, shared with the customer portal. The override flag is reserved for documented, reviewed exceptions and is written to `support_actions`. `--non-interactive` is rejected unless `MARSTV_SUPPORT_AUTOMATION_ENABLED=true` is deliberately configured in an approved automation environment. Apply every numbered database migration before deploying matching application code.

Run `webhook:work` every minute from one cron entry. The worker uses a database advisory lock, processes only due `retry_wait` events in a bounded batch, applies increasing retry delays, and writes a redacted heartbeat to `storage/logs/webhook-worker-heartbeat.json`.

Verified `payment.refund`, `payment.dispute.created`, and `payment.dispute.lost` events move the purchase to a terminal state, increment the licence version, close its current assignment, and issue a signed revocation on the device's next authenticated check. `payment.dispute.cancelled`, `payment.dispute.closed`, and `payment.dispute.won` are recorded for reconciliation but never reactivate a terminal purchase automatically.

## Current status

The repository contains the public site, browser activation and checkout flow, device registration, atomic challenge consumption, authenticated status polling, entitlement signing, support CLI, and idempotent Freemius payment-webhook fulfilment. A successful payment is matched to its pending checkout claim before the licence is activated; the Android client then verifies and stores the signed entitlement locally.

## Customer transfer portal

`/transfer` supports accountless transfer of an existing paid Freemius licence. Customers enter their receipt email, order number and a fresh activation code from the destination installation; verify an eight-digit email code; review both Device IDs; and explicitly confirm. No Freemius portal deactivation or licence key is required. Email access proves ownership; an email address or order number alone does not.

The portal and CLI share the same transactional transfer service and rolling limits. Reinstall recovery counts as a transfer. Support can still handle lost email access and documented limit overrides. A changed/revoked licence, refunded purchase, expired/consumed activation code or already-licensed destination cannot be transferred. Confirmation retries within the verification session return the completed result without moving it twice. Old devices receive signed revocation at their next successful status check; permanently offline lifetime tokens cannot be remotely erased. Local playlists and settings are not copied.

Deployment:

1. Apply `005_transfer_portal.sql` after all earlier migrations.
2. Configure the host's PHP `mail()` transport and `MARSTV_TRANSFER_MAIL_FROM` with an authorized sender. The implementation uses the host mail transport; it does not include its own SMTP client or assume Freemius sends these messages.
   The configured sender is `support@marstv.online`. On the website host, run `php bin/marstv-mail-test RECIPIENT_EMAIL` and confirm the reference arrives in the test inbox. This CLI-only check requires no database migration, sends no verification credential and does not enable the portal. A successful `mail()` return means host acceptance, not confirmed inbox delivery.
3. Set `MARSTV_TRANSFER_CODE_PEPPER` to an independent random secret of at least 32 bytes, kept in private configuration.
4. Keep `MARSTV_TRANSFER_PORTAL_ENABLED=false` until a test receipt email receives a code and the complete flow passes in staging; then enable it in the intended environment. While disabled, `/transfer` displays support recovery instructions.
5. Schedule `php bin/marstv-support transfer:cleanup` hourly. Each run removes up to 500 verification sessions expired for more than 24 hours; repeat if a backlog exists. Durable transfer audit history remains in `support_actions` and `license_assignments`.

Verification codes expire after 10 minutes and permit five guesses. Tokens, CSRF values and codes are stored as hashes; email codes use a separate HMAC secret. Sessions use HttpOnly/SameSite cookies, CSRF checks and explicit confirmation. Request responses use the same generic status and cookie shape for matches, non-matches and mail failures. Email is attempted only for a matching active paid purchase and valid destination code, and is sent to the stored receipt address. Unmatched requests and failed delivery leave no usable verification code. Mail delivery is synchronous, so response timing is not guaranteed to be indistinguishable; this is not a complete account-enumeration defence. Requests are limited per email, per IP and globally (1,000 submissions per hour). A separate global quota permits at most 100 eligible mail attempts per hour; unmatched requests do not consume that quota. Mail-quota exhaustion returns the same generic response and creates no valid code. Verification and confirmation also have IP limits. Configure `MARSTV_BASE_URL` to the exact website origin. Configure the mail provider to avoid retaining message bodies longer than needed.

Integration tests require a **disposable localhost** MySQL database named `marstv_transfer_test`; the suite recreates its schema. Set `MARSTV_TRANSFER_TEST_DSN=mysql:host=127.0.0.1;port=33367;dbname=marstv_transfer_test`, using the isolated test container's root password `marstv-isolated-test`, then run PHPUnit. Tests use an in-memory mail callback and never send email or load deployment secrets. Without that test DSN, database tests are explicitly skipped.

## Missing receipt email recovery

If Freemius confirms a paid purchase but local `customer_email` is NULL, the transfer portal intentionally sends no email. The webhook originally relied solely on `objects.user.email`; a dashboard Trigger/User label is not proof that this object was included in the delivered payload.

Configure the product-scoped `FREEMIUS_API_BEARER_TOKEN` in the server private `.env` (Freemius product Settings, API Token). New payment events missing an email now attempt authenticated payment and purchaser lookups. Payment fulfilment continues if lookup is unavailable; a redacted log message requests recovery.

For an existing purchase, run `php bin/marstv-support purchase:sync-email --order=PAYMENT_ID --operator=YOUR_ID`. This follows the payment's returned user ID, verifies payment/licence and user identity, fills only a missing email and records `receipt_email_recovered`. It never accepts a customer-provided email or user ID, overwrites an existing email, or changes licence state. Replaying an already processed webhook alone does not repair it. The command can also be retried after a provider outage.

API sources: https://docs.freemius.com/api/payments/retrieve and https://docs.freemius.com/api/users/retrieve . Live provider responses still need validation on the host; local tests use synthetic responses and no production credentials.
