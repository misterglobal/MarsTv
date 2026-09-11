<?php

declare(strict_types=1);

function load_private_env(string $path): void
{
    if (!is_file($path) || !is_readable($path)) {
        return;
    }

    foreach (file($path, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) as $line) {
        $line = trim($line);

        if ($line === '' || str_starts_with($line, '#')) {
            continue;
        }

        [$name, $value] = array_pad(explode('=', $line, 2), 2, '');

        $name = trim($name);
        $value = trim($value);

        if (!preg_match('/^[A-Z][A-Z0-9_]*$/', $name)) {
            continue;
        }

        if (
            strlen($value) >= 2 &&
            (($value[0] === '"' && $value[-1] === '"') ||
             ($value[0] === "'" && $value[-1] === "'"))
        ) {
            $value = substr($value, 1, -1);
        }

        // This file is the deployment's private configuration source. Shared
        // hosts may inject empty or stale process variables, so values that are
        // explicitly present here must take precedence.
        putenv($name.'='.$value);
        $_ENV[$name] = $value;
    }
}

load_private_env(dirname(__DIR__).'/.env');

function env_value(string $key, ?string $default = null): ?string
{
    $value = getenv($key);
    return $value === false || $value === '' ? $default : $value;
}

function config(string $key): mixed
{
    static $values;
    $values ??= [
        'app_name' => 'MarsTV',
        'base_url' => rtrim((string) env_value('MARSTV_BASE_URL', 'https://marstv.online'), '/'),
        'api_base' => rtrim((string) env_value('MARSTV_API_BASE', '/api/v1'), '/'),
        'support_email' => env_value('MARSTV_SUPPORT_EMAIL', 'support@marstv.online'),
        'privacy_email' => env_value('MARSTV_PRIVACY_EMAIL', 'privacy@marstv.online'),
        'transfer_portal_enabled' => filter_var(env_value('MARSTV_TRANSFER_PORTAL_ENABLED', 'false'), FILTER_VALIDATE_BOOLEAN),
        'transfer_mail_from' => env_value('MARSTV_TRANSFER_MAIL_FROM'),
        'transfer_code_pepper' => env_value('MARSTV_TRANSFER_CODE_PEPPER'),
        'price_label' => env_value('MARSTV_PRICE_LABEL', 'US $12.99 one time'),
        'apk_url' => env_value('MARSTV_APK_URL'),
        'apk_version' => env_value('MARSTV_APK_VERSION', 'Coming soon'),
        'apk_sha256' => env_value('MARSTV_APK_SHA256'),
        'environment' => env_value('MARSTV_ENV', 'production'),
        'db_dsn' => env_value('MARSTV_DB_DSN'),
        'db_user' => env_value('MARSTV_DB_USER'),
        'db_password' => env_value('MARSTV_DB_PASSWORD'),
        'activation_pepper' => env_value('MARSTV_ACTIVATION_PEPPER'),
        'rate_limit_pepper' => env_value('MARSTV_RATE_LIMIT_PEPPER'),
        'entitlement_private_key_path' => env_value('ENTITLEMENT_PRIVATE_KEY_PATH'),
        'entitlement_key_id' => env_value('ENTITLEMENT_KEY_ID', 'entitlement-2026-01'),
        'legal_terms_version' => env_value('MARSTV_LEGAL_TERMS_VERSION', '2026-09-05'),
        'freemius_checkout_enabled' => filter_var(env_value('FREEMIUS_CHECKOUT_ENABLED', 'false'), FILTER_VALIDATE_BOOLEAN),
        'freemius_mode' => env_value('FREEMIUS_MODE', 'sandbox'),
        'freemius_product_id' => env_value('FREEMIUS_PRODUCT_ID', '38872'),
        'freemius_plan_id' => env_value('FREEMIUS_PLAN_ID', '64606'),
        'freemius_public_key' => env_value('FREEMIUS_PUBLIC_KEY'),
        'freemius_secret_key' => env_value('FREEMIUS_SECRET_KEY'),
        'freemius_api_bearer_token' => env_value('FREEMIUS_API_BEARER_TOKEN'),
        'freemius_amount_minor' => (int) env_value('FREEMIUS_AMOUNT_MINOR', '1299'),
        'freemius_currency' => strtoupper((string) env_value('FREEMIUS_CURRENCY', 'USD')),
    ];
    return $values[$key] ?? null;
}

function database(): PDO
{
    static $pdo;
    if ($pdo instanceof PDO) return $pdo;
    if (!config('db_dsn') || !config('db_user') || !config('activation_pepper') || !config('rate_limit_pepper')) {
        throw new RuntimeException('Backend configuration is incomplete.');
    }
    $pdo = new PDO((string) config('db_dsn'), (string) config('db_user'), (string) config('db_password'), [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        PDO::ATTR_EMULATE_PREPARES => false,
    ]);
    $pdo->exec("SET time_zone = '+00:00'");
    return $pdo;
}

function e(?string $value): string
{
    return htmlspecialchars($value ?? '', ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
}

function request_path(): string
{
    $path = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH);
    $path = is_string($path) ? rtrim($path, '/') : '/';
    return $path === '' ? '/' : $path;
}

function nonce(): string
{
    static $nonce;
    return $nonce ??= rtrim(strtr(base64_encode(random_bytes(18)), '+/', '-_'), '=');
}

function send_security_headers(string $page = ''): void
{
    $nonce = nonce();
    $freemius = $page === 'activate' ? ' https://checkout.freemius.com' : '';
    $frameSrc = $page === 'activate' ? "frame-src https://checkout.freemius.com; " : "frame-src 'none'; ";
    $styleSrc = $page === 'activate' ? "style-src 'self' 'unsafe-inline' https://checkout.freemius.com;" : "style-src 'self';";
    header("Content-Security-Policy: default-src 'self'; script-src 'self' 'nonce-{$nonce}'{$freemius}; {$styleSrc} img-src 'self' data: https://checkout.freemius.com; connect-src 'self'{$freemius}; font-src 'self'; {$frameSrc}frame-ancestors 'none'; form-action 'self' https://checkout.freemius.com; base-uri 'none'; object-src 'none'; upgrade-insecure-requests");
    header('Referrer-Policy: no-referrer');
    header('X-Content-Type-Options: nosniff');
    header('X-Frame-Options: DENY');
    header('Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=()');
    header('Cross-Origin-Opener-Policy: same-origin');
    header('Cache-Control: no-store, max-age=0');
}

function page_data(string $path): array
{
    return match ($path) {
        '/' => ['title' => 'Your channels. One orbit.', 'page' => 'home'],
        '/activate' => ['title' => 'Activate MarsTV Pro', 'page' => 'activate'],
        '/transfer' => ['title' => 'Transfer MarsTV Pro', 'page' => 'transfer'],
        '/download' => ['title' => 'Download MarsTV', 'page' => 'download'],
        '/support' => ['title' => 'MarsTV Support', 'page' => 'support'],
        '/privacy' => ['title' => 'Privacy Policy', 'page' => 'privacy'],
        '/terms' => ['title' => 'Terms of Use', 'page' => 'terms'],
        '/refunds' => ['title' => 'Refund Policy', 'page' => 'refunds'],
        '/payment/success' => ['title' => 'Payment received', 'page' => 'payment-success'],
        '/payment/cancelled' => ['title' => 'Checkout cancelled', 'page' => 'payment-cancelled'],
        default => ['title' => 'Page not found', 'page' => 'not-found', 'status' => 404],
    };
}

function render_icon(string $name): string
{
    $icons = [
        'play' => '<path d="m9 7 8 5-8 5V7Z"/><circle cx="12" cy="12" r="9"/>',
        'guide' => '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M7 8h4M7 12h4M7 16h4M15 8h2M15 12h2M15 16h2"/>',
        'shield' => '<path d="M12 3 5 6v5c0 4.6 2.8 7.5 7 10 4.2-2.5 7-5.4 7-10V6l-7-3Z"/><path d="m9 12 2 2 4-4"/>',
        'devices' => '<rect x="3" y="5" width="14" height="10" rx="2"/><path d="M8 19h4M10 15v4"/><rect x="16" y="9" width="5" height="10" rx="1"/>',
        'check' => '<path d="m5 12 4 4L19 6"/>',
        'download' => '<path d="M12 3v12m0 0 5-5m-5 5-5-5M5 21h14"/>',
        'mail' => '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="m3 7 9 6 9-6"/>',
    ];
    return '<svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">'.($icons[$name] ?? '').'</svg>';
}
