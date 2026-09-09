<?php
declare(strict_types=1);

namespace MarsTv\Domain;

final readonly class DevicePublicKey
{
    private function __construct(public string $spkiBase64, public string $thumbprint) {}

    public static function validate(string $spkiBase64, string $claimedThumbprint): self
    {
        $der = base64_decode($spkiBase64, true);
        if ($der === false || strlen($der) > 512) throw new \InvalidArgumentException('Invalid public key');
        $pem = "-----BEGIN PUBLIC KEY-----\n" . chunk_split(base64_encode($der), 64, "\n") . "-----END PUBLIC KEY-----\n";
        $key = openssl_pkey_get_public($pem);
        $details = $key === false ? false : openssl_pkey_get_details($key);
        if ($details === false || $details['type'] !== OPENSSL_KEYTYPE_EC || ($details['ec']['curve_name'] ?? '') !== 'prime256v1') {
            throw new \InvalidArgumentException('Device key must be P-256');
        }
        $thumbprint = Encoding::base64UrlEncode(hash('sha256', $der, true));
        if (!hash_equals($thumbprint, $claimedThumbprint)) throw new \InvalidArgumentException('Public-key thumbprint mismatch');
        return new self($spkiBase64, $thumbprint);
    }

    public function verify(string $message, string $derSignature): bool
    {
        $pem = "-----BEGIN PUBLIC KEY-----\n" . chunk_split($this->spkiBase64, 64, "\n") . "-----END PUBLIC KEY-----\n";
        return openssl_verify($message, $derSignature, $pem, OPENSSL_ALGO_SHA256) === 1;
    }
}
