<?php

declare(strict_types=1);

require dirname(__DIR__).'/src/bootstrap.php';
require dirname(__DIR__).'/src/ActivationApi.php';
require dirname(__DIR__).'/src/FreemiusWebhook.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store, max-age=0');

function webhook_audit(string $outcome): void
{
    $entry = sprintf("[%s] outcome=%s\n", gmdate('c'), preg_replace('/[^A-Z0-9_.-]/i', '_', $outcome));
    $directory = dirname(__DIR__).'/storage/logs';
    if ((is_dir($directory) || @mkdir($directory, 0700, true)) && is_writable($directory)) {
        @error_log($entry, 3, $directory.'/webhook-audit.log');
    }
    error_log('MarsTV webhook '.$outcome);
}

try {
    if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') throw new ApiProblem('INVALID_REQUEST', 405);
    $length = (int) ($_SERVER['CONTENT_LENGTH'] ?? 0);
    if ($length > 1048576) throw new ApiProblem('INVALID_REQUEST', 413);
    $raw = file_get_contents('php://input');
    if ($raw === false) throw new ApiProblem('INVALID_REQUEST', 400);
    $result = (new FreemiusWebhook(database()))->handle($raw, (string) ($_SERVER['HTTP_X_SIGNATURE'] ?? ''));
    webhook_audit('accepted');
    http_response_code(200);
    echo json_encode($result, JSON_UNESCAPED_SLASHES | JSON_THROW_ON_ERROR);
} catch (ApiProblem $problem) {
    webhook_audit($problem->errorCode);
    http_response_code($problem->httpStatus);
    echo json_encode(['code' => $problem->errorCode], JSON_THROW_ON_ERROR);
} catch (Throwable $error) {
    webhook_audit('failure.'.get_class($error));
    http_response_code(503);
    echo json_encode(['code' => 'SERVICE_TEMPORARILY_UNAVAILABLE'], JSON_THROW_ON_ERROR);
}
