# MarsTV licensing backend

Slim 4/PHP 8.2 service for the accountless, device-bound MarsTV Pro licence described in the product requirements.

This component is deliberately pre-payment. Do not accept real payments until registration, challenge verification, activation, test licence issuance and signed entitlement verification pass end to end.

## Local setup

1. Install PHP 8.2+, Composer, MySQL 8, OpenSSL and PDO MySQL.
2. Run `composer install --no-dev` for production or `composer install` for development.
3. Copy `.env.example` to `.env` and provide secrets outside the public web root.
4. Apply `database/migrations/001_licensing_core.sql` with a migration user.
5. Give the runtime database user only the required `SELECT`, `INSERT`, `UPDATE` and `DELETE` privileges on this database; it must not have schema-management privileges.
6. Point the web-server document root at `public/`.

Run `composer test` before deployment. Never commit `.env`, signing private keys, activation pepper, logs, customer information, or database exports.

## Current status

The repository contains strict configuration, the core relational invariants, P-256 public-key validation, base64url/HMAC helpers, and the exact device-challenge byte contract. HTTP controllers, atomic challenge consumption, activation redemption, entitlement signing, and the support CLI are the next implementation slice.
