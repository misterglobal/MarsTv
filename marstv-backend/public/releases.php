<?php
declare(strict_types=1);
require dirname(__DIR__).'/src/bootstrap.php';

header('Content-Type: application/jose');
header('X-Content-Type-Options: nosniff');
header('Cache-Control: no-store');
if (!in_array($_SERVER['REQUEST_METHOD'] ?? '', ['GET', 'HEAD'], true)) {
    header('Allow: GET, HEAD'); http_response_code(405); exit;
}
// The release key stays on the release workstation; the host serves only the signed artifact.
$path = dirname(__DIR__).'/storage/releases/direct-stable.jws';
if (!is_file($path) || !is_readable($path)) { http_response_code(404); exit; }
$token = file_get_contents($path, false, null, 0, 32769);
if ($token === false || strlen($token) > 32768 || !preg_match('/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\s*$/D', $token)) {
    http_response_code(503); exit;
}
if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'HEAD') echo trim($token);
