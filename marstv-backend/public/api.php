<?php

declare(strict_types=1);

require dirname(__DIR__).'/src/bootstrap.php';
require dirname(__DIR__).'/src/ActivationApi.php';
require dirname(__DIR__).'/src/DeviceApi.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store, max-age=0');
header('X-Content-Type-Options: nosniff');
header('Referrer-Policy: no-referrer');

function api_response(array $payload, int $status = 200): never
{
    http_response_code($status);
    echo json_encode($payload, JSON_UNESCAPED_SLASHES | JSON_THROW_ON_ERROR);
    exit;
}

function request_json(): array
{
    global $requestRawBody;
    $length = (int) ($_SERVER['CONTENT_LENGTH'] ?? 0);
    if ($length > 16384) throw new ApiProblem('INVALID_REQUEST', 413);
    $raw = file_get_contents('php://input');
    $requestRawBody = $raw === false ? '' : $raw;
    if ($raw === false || $raw === '') return [];
    $value = json_decode($raw, true, 16, JSON_THROW_ON_ERROR);
    if (!is_array($value)) throw new ApiProblem('INVALID_REQUEST', 400);
    return $value;
}

function request_headers_lower(): array
{
    $headers = [];
    $incoming = function_exists('getallheaders') ? getallheaders() : [];
    $incoming = is_array($incoming) ? $incoming : [];
    foreach ($incoming as $name => $value) $headers[strtolower($name)] = $value;
    if (!isset($headers['x-csrf-token']) && isset($_SERVER['HTTP_X_CSRF_TOKEN'])) {
        $headers['x-csrf-token'] = (string) $_SERVER['HTTP_X_CSRF_TOKEN'];
    }
    foreach (['x-mars-challenge-id' => 'HTTP_X_MARS_CHALLENGE_ID', 'x-mars-device-signature' => 'HTTP_X_MARS_DEVICE_SIGNATURE'] as $name => $serverName) {
        if (!isset($headers[$name]) && isset($_SERVER[$serverName])) $headers[$name] = (string) $_SERVER[$serverName];
    }
    return $headers;
}

try {
    if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') throw new ApiProblem('INVALID_REQUEST', 405);
    $path = parse_url($_SERVER['REQUEST_URI'] ?? '', PHP_URL_PATH);
    $input = request_json();
    $rawBody = $requestRawBody ?? '';
    $clientIp = (string) ($_SERVER['REMOTE_ADDR'] ?? 'unknown');
    $api = new ActivationApi(database());
    $deviceApi = new DeviceApi(database());
    $result = match (true) {
        $path === '/api/v1/devices/register' => $deviceApi->register($input, $clientIp),
        preg_match('#^/api/v1/devices/([0-9a-f-]{36})/challenges$#D', (string) $path, $matches) === 1 => $deviceApi->challenge($matches[1], $input, $clientIp),
        preg_match('#^/api/v1/devices/([0-9a-f-]{36})/status$#D', (string) $path, $matches) === 1 => $deviceApi->status($matches[1], request_headers_lower(), $rawBody, $clientIp),
        $path === '/api/v1/activation-sessions' => $deviceApi->refreshActivation(request_headers_lower(), $rawBody, $clientIp),
        $path === '/api/v1/activation-sessions/preview' => $api->preview($input, $clientIp),
        $path === '/api/v1/activation-sessions/redeem' => $api->redeem($input, $clientIp),
        $path === '/api/v1/checkout/create' => $api->checkout($input, request_headers_lower(), $_COOKIE, $clientIp),
        $path === '/api/v1/checkout/freemius/claim' => $api->claim($input, request_headers_lower(), $_COOKIE, $clientIp),
        default => throw new ApiProblem('INVALID_REQUEST', 404),
    };
    api_response($result);
} catch (ApiProblem $problem) {
    api_response(['code' => $problem->errorCode], $problem->httpStatus);
} catch (JsonException) {
    api_response(['code' => 'INVALID_REQUEST'], 400);
} catch (Throwable $error) {
    error_log('MarsTV API failure: '.get_class($error));
    api_response(['code' => 'SERVICE_TEMPORARILY_UNAVAILABLE'], 503);
}
