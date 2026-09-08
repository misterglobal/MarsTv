<?php
declare(strict_types=1);

namespace MarsTv;

final readonly class Config
{
    public function __construct(
        public string $baseUrl,
        public string $dbDsn,
        public string $dbUser,
        public string $dbPassword,
        public string $activationPepper,
        public string $privateKeyPath,
        public string $keyId,
    ) {}

    public static function fromEnvironment(): self
    {
        $baseUrl = rtrim(self::required('APP_BASE_URL'), '/');
        if (!str_starts_with($baseUrl, 'https://')) {
            throw new \RuntimeException('APP_BASE_URL must use HTTPS');
        }
        $pepper = base64_decode(self::required('ACTIVATION_PEPPER_B64'), true);
        if ($pepper === false || strlen($pepper) < 32) {
            throw new \RuntimeException('ACTIVATION_PEPPER_B64 must decode to at least 32 bytes');
        }
        return new self(
            $baseUrl,
            self::required('DB_DSN'),
            self::required('DB_USER'),
            self::required('DB_PASSWORD'),
            $pepper,
            self::required('ENTITLEMENT_PRIVATE_KEY_PATH'),
            self::required('ENTITLEMENT_KEY_ID'),
        );
    }

    private static function required(string $name): string
    {
        $value = getenv($name);
        if ($value === false || trim($value) === '') throw new \RuntimeException("Missing environment variable: {$name}");
        return $value;
    }
}
