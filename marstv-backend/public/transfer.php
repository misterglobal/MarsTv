<?php
// Included by index.php after bootstrap and security headers; not a direct endpoint.
if (!function_exists('config')) { http_response_code(404); exit; }
?>
<section class="section shell transfer-page">
  <span class="eyebrow"><i></i> KEEP YOUR PRO LICENCE</span>
  <h1>Move Pro to your device</h1>
  <p class="lede">Replacing your TV or reinstalling MarsTV? Verify your purchase email, then confirm where to move Pro. No new purchase or MarsTV account needed.</p>
  <div class="notice">One licence covers one installation. Transfers, including reinstalls, are limited to one per rolling 30 days and three per rolling 365 days. Contact support for exceptional circumstances.</div>
  <?php if (config('transfer_portal_enabled')): ?>
  <div class="transfer-card">
    <form id="transfer-request">
      <h2>1. Find your purchase</h2>
      <label for="transfer-email">Purchase receipt email</label>
      <input id="transfer-email" name="email" type="email" autocomplete="email" maxlength="191" required>
      <label for="transfer-order">Freemius order number</label>
      <input id="transfer-order" name="order" autocomplete="off" maxlength="191" required aria-describedby="order-help">
      <p id="order-help" class="fineprint">Use the order number from your purchase receipt. Do not enter your licence key.</p>
      <label for="transfer-activation">New device activation code</label>
      <input id="transfer-activation" name="activationCode" autocomplete="off" placeholder="ABCD-EFGH" maxlength="9" required aria-describedby="activation-help">
      <p id="activation-help" class="fineprint">Open the Pro activation screen in MarsTV on the new device and keep it open. Enter its short-lived activation code here.</p>
      <button class="button primary" type="submit">Send verification code</button>
    </form>
    <form id="transfer-verify" hidden>
      <h2 tabindex="-1">2. Check your email</h2>
      <p>If your receipt details match an active paid licence and your device activation code is valid, we will send an eight-digit code to your receipt email. It expires in 10 minutes. Keep this page open. If no email arrives, check spam, verify your details and try again later, or contact support.</p>
      <label for="transfer-code">Email verification code</label>
      <input id="transfer-code" name="code" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{8}" minlength="8" maxlength="8" required>
      <button class="button primary" type="submit">Verify email</button>
    </form>
    <form id="transfer-confirm" hidden>
      <h2 tabindex="-1">3. Confirm your transfer</h2>
      <p>Current device: <strong id="transfer-old-device"></strong></p>
      <p>Move Pro to: <strong id="transfer-new-device"></strong></p>
      <p>The previous device loses Pro at its next successful online check. Stop using Pro there after transferring. Playlists, favourites and settings stay on each device.</p>
      <button class="button primary" type="submit">Confirm transfer</button>
    </form>
    <div id="transfer-success" hidden>
      <h2 tabindex="-1">Pro has been transferred</h2>
      <p>Keep the new device online and reopen MarsTV or refresh its Pro status to unlock. You do not need to pay again.</p>
    </div>
    <p id="transfer-message" role="status" aria-live="polite"></p>
    <a href="/transfer" id="transfer-restart" hidden>Start again with a fresh activation code</a>
  </div>
  <?php else: ?>
  <div class="transfer-card"><h2>Transfer with support</h2><p>Email support with your receipt email or order number and the new public Device ID shown in MarsTV. We will verify purchase ownership before moving Pro. Email verification for self-service transfers is not available yet.</p></div>
  <?php endif; ?>
  <p>Lost access to your receipt email, or need help? <a href="mailto:<?= e((string) config('support_email')) ?>">Contact support</a>. Initial response target: within 2 business days. Never email your licence key or verification code.</p>
</section>
