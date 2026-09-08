<?php
declare(strict_types=1);

namespace MarsTv\Domain;

final readonly class DeviceChallenge
{
    public function __construct(
        public string $id,
        public string $nonce,
        public string $deviceId,
        public string $method,
        public string $path,
        public string $bodyHash,
        public int $expiresAt,
    ) {}

    public function signedBytes(): string
    {
        return implode("\n", ['MARSTV_DEVICE_AUTH_V1', $this->id, $this->nonce, $this->deviceId, $this->method, $this->path, $this->bodyHash, (string)$this->expiresAt]);
    }
}
