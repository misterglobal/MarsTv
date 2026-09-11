<?php

declare(strict_types=1);

$path = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH);
$file = __DIR__.($path === '/' ? '' : $path);
if ($path !== '/' && is_file($file)) {
    return false;
}
if ($path === '/api/v1/releases/direct-stable') {
    require __DIR__.'/releases.php';
    return true;
}
if (is_string($path) && str_starts_with($path, '/api/v1/')) {
    require __DIR__.'/api.php';
    return true;
}
require __DIR__.'/index.php';
