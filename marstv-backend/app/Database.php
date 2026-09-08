<?php
declare(strict_types=1);

namespace MarsTv;

final class Database
{
    public static function connect(Config $config): \PDO
    {
        return new \PDO($config->dbDsn, $config->dbUser, $config->dbPassword, [
            \PDO::ATTR_ERRMODE => \PDO::ERRMODE_EXCEPTION,
            \PDO::ATTR_DEFAULT_FETCH_MODE => \PDO::FETCH_ASSOC,
            \PDO::ATTR_EMULATE_PREPARES => false,
        ]);
    }

    public static function transaction(\PDO $pdo, callable $operation): mixed
    {
        $pdo->beginTransaction();
        try {
            $result = $operation();
            $pdo->commit();
            return $result;
        } catch (\Throwable $error) {
            if ($pdo->inTransaction()) $pdo->rollBack();
            throw $error;
        }
    }
}
