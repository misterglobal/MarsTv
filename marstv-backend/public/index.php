<?php
declare(strict_types=1);

use Dotenv\Dotenv;
use MarsTv\Config;
use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\ServerRequestInterface;
use Slim\Factory\AppFactory;

require dirname(__DIR__) . '/vendor/autoload.php';

Dotenv::createImmutable(dirname(__DIR__))->safeLoad();
$config = Config::fromEnvironment();
$app = AppFactory::create();
$app->addBodyParsingMiddleware();
$app->addRoutingMiddleware();

$app->get('/api/v1/health', static function (ServerRequestInterface $request, ResponseInterface $response): ResponseInterface {
    $response->getBody()->write('{"status":"ok"}');
    return $response->withHeader('Content-Type', 'application/json');
});

$errorMiddleware = $app->addErrorMiddleware(false, false, false);
$errorMiddleware->getDefaultErrorHandler()->forceContentType('application/json');
$app->run();
