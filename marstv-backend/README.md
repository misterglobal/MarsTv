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

Protected support operations are available only through the CLI:

```text
php bin/marstv-support purchase:lookup --order=PROVIDER_ORDER_ID
php bin/marstv-support license:transfer --license=UUID --new-device=MARS-XXXX --operator=YOUR_ID --reason="verified replacement"
php bin/marstv-support license:revoke --license=UUID --operator=YOUR_ID --reason="verified support request"
php bin/marstv-support webhook:replay --event=PROVIDER_EVENT_ID --operator=YOUR_ID --reason="retry after corrected outage"
php bin/marstv-support webhook:work --limit=25
```

Transfers enforce the published rolling limits. The override flag is reserved for documented, reviewed exceptions and is written to `support_actions`. Apply every numbered database migration before deploying matching application code.

Run `webhook:work` every minute from one cron entry. The worker uses a database advisory lock, processes only due `retry_wait` events in a bounded batch, applies increasing retry delays, and writes a redacted heartbeat to `storage/logs/webhook-worker-heartbeat.json`.

Verified `payment.refund`, `payment.dispute.created`, and `payment.dispute.lost` events move the purchase to a terminal state, increment the licence version, close its current assignment, and issue a signed revocation on the device's next authenticated check. `payment.dispute.closed` and `payment.dispute.won` are recorded for reconciliation but never reactivate a terminal purchase automatically.

## Current status

The repository contains the public site, browser activation and checkout flow, device registration, atomic challenge consumption, authenticated status polling, entitlement signing, support CLI, and idempotent Freemius payment-webhook fulfilment. A successful payment is matched to its pending checkout claim before the licence is activated; the Android client then verifies and stores the signed entitlement locally.
