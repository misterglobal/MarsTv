# MarsTV licensing backend

Slim 4/PHP 8.2 service for the accountless, device-bound MarsTV Pro licence described in the product requirements.

This component is deliberately pre-payment. Do not accept real payments until registration, challenge verification, activation, test licence issuance and signed entitlement verification pass end to end.

## Local setup

1. Install PHP 8.2+, Composer, MySQL 8, OpenSSL and PDO MySQL.
2. Run `composer install --no-dev` for production or `composer install` for development.
3. Copy `.env.example` to `.env` and provide secrets outside the public web root. The active configuration uses the `MARSTV_*` names loaded by `src/bootstrap.php`.
4. Apply the numbered files in `database/migrations/` in order with a migration user.
5. Give the runtime database user only the required `SELECT`, `INSERT`, `UPDATE` and `DELETE` privileges on this database; it must not have schema-management privileges.
6. Point the web-server document root at `public/`.

Run `composer test` before deployment. Keep `FREEMIUS_CHECKOUT_ENABLED=false` until the full payment and webhook acceptance suite passes. Never commit `.env`, signing private keys, activation/rate-limit peppers, logs, customer information, or database exports.

For a closed-beta test device that has already registered, grant an idempotent audited licence without editing database rows:

`php bin/marstv-support test-license:grant --device=MARS-XXXX --operator=YOUR_ID --reason="closed beta test"`

The next authenticated device status check returns a freshly signed, non-expiring, device-bound entitlement. This command creates a zero-value support-test purchase and must not be used to represent a real payment.

## Current status

The merged repository contains the public site and browser activation flow plus the core relational invariants, P-256 public-key validation, base64url/HMAC helpers, and exact device-challenge byte contract. Device registration, atomic challenge consumption, authenticated status, entitlement signing, and the support CLI remain the next implementation slice.
