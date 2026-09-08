<?php

declare(strict_types=1);

require dirname(__DIR__).'/src/bootstrap.php';

$route = page_data(request_path());
send_security_headers((string) $route['page']);
http_response_code($route['status'] ?? 200);
$page = $route['page'];
$title = $route['title'].' | MarsTV';
$apiBase = (string) config('api_base');
$apkAvailable = is_string(config('apk_url')) && config('apk_url') !== '';
?>
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <meta name="theme-color" content="#070A12">
  <meta name="description" content="MarsTV is a provider-neutral player for your authorized IPTV sources.">
  <title><?= e($title) ?></title>
  <link rel="icon" href="/assets/marstv-icon.svg" type="image/svg+xml">
  <link rel="stylesheet" href="/assets/styles.css">
  <?php if ($page === 'activate'): ?><link rel="stylesheet" href="/assets/legal-checkout.css"><?php endif; ?>
</head>
<body data-page="<?= e($page) ?>" data-api-base="<?= e($apiBase) ?>">
  <div class="ambient ambient-a"></div><div class="ambient ambient-b"></div>
  <header class="site-header">
    <a class="brand" href="/" aria-label="MarsTV home"><img src="/assets/marstv-logo.svg" alt="MarsTV"></a>
    <button class="menu-button" type="button" aria-expanded="false" aria-controls="main-nav"><span></span><span></span></button>
    <nav id="main-nav" aria-label="Main navigation">
      <a href="/activate"<?= $page === 'activate' ? ' aria-current="page"' : '' ?>>Activate Pro</a>
      <a href="/download"<?= $page === 'download' ? ' aria-current="page"' : '' ?>>Download</a>
      <a href="/support"<?= $page === 'support' ? ' aria-current="page"' : '' ?>>Support</a>
    </nav>
  </header>

  <main>
<?php if ($page === 'home'): ?>
    <section class="hero shell">
      <div class="hero-copy">
        <span class="eyebrow"><i></i> Built for the big screen</span>
        <h1>Your channels.<br><span>One orbit.</span></h1>
        <p class="lede">Bring your authorized TV sources into one calm, cable-style experience. Live TV, guide, movies and series without the clutter.</p>
        <div class="actions"><a class="button primary" href="/download">Download MarsTV <?= render_icon('download') ?></a><a class="button ghost" href="/activate">Activate Pro</a></div>
        <p class="fineprint">MarsTV is a media player. No channels or subscriptions are included.</p>
      </div>
      <div class="product-stage" aria-label="MarsTV programme guide preview">
        <div class="orbit orbit-one"></div><div class="orbit orbit-two"></div>
        <div class="tv-frame">
          <div class="tv-top"><img src="/assets/marstv-mark.svg" alt=""><span>LIVE GUIDE</span><time>9:41 PM</time></div>
          <div class="guide-feature"><div><small>NOW PLAYING</small><strong>Planet Earth</strong><span>Wild landscapes from around the world</span></div><b>LIVE</b></div>
          <div class="guide-row selected"><span class="channel">101</span><strong>Nature One</strong><span class="show">Planet Earth</span><time>9:00–10:00</time></div>
          <div class="guide-row"><span class="channel">102</span><strong>World News</strong><span class="show">Evening Report</span><time>9:30–10:00</time></div>
          <div class="guide-row"><span class="channel">103</span><strong>Cinema</strong><span class="show">Friday Feature</span><time>8:45–10:30</time></div>
          <div class="progress"><i></i></div>
        </div>
      </div>
    </section>

    <section class="signal-strip"><span>LIVE TV</span><i></i><span>FULL GUIDE</span><i></i><span>MOVIES</span><i></i><span>SERIES</span><i></i><span>CATCH-UP</span></section>

    <section class="section shell">
      <div class="section-heading"><span class="kicker">THE EXPERIENCE</span><h2>TV should feel like TV.</h2><p>Fast navigation, clear focus and the information you need from across the room.</p></div>
      <div class="feature-grid">
        <article class="feature-card coral"><span class="icon-wrap"><?= render_icon('guide') ?></span><em>01</em><h3>Guide first</h3><p>A familiar cable-style grid built for remotes, channel surfing and seeing what is on now.</p></article>
        <article class="feature-card violet"><span class="icon-wrap"><?= render_icon('play') ?></span><em>02</em><h3>Your media, organized</h3><p>Browse movies, series, episodes and favourites from your own authorized sources.</p></article>
        <article class="feature-card green"><span class="icon-wrap"><?= render_icon('devices') ?></span><em>03</em><h3>Made for every screen</h3><p>Designed for Android TV, Google TV, Fire TV, phones and tablets from one app.</p></article>
      </div>
    </section>

    <section class="pro-band">
      <div class="shell pro-layout"><div><span class="eyebrow"><i></i> MARS TV PRO</span><h2>More control.<br>Same orbit.</h2><p>Unlock full playback, multiple sources, the complete guide, profiles, catch-up and advanced controls with a one-time purchase.</p><a class="button primary" href="/activate">Activate this device</a></div><div class="pro-list">
        <?php foreach (['Full movie and series playback','Multiple IPTV sources','Full cable-style programme guide','Profiles and parental controls','Continue watching and catch-up','Lifetime access for one installation'] as $item): ?><span><?= render_icon('check') ?><?= e($item) ?></span><?php endforeach; ?>
      </div></div>
    </section>

    <section class="section shell steps"><div class="section-heading"><span class="kicker">THREE STEPS</span><h2>From code to Pro.</h2></div><div class="step-grid"><article><b>1</b><h3>Open MarsTV</h3><p>Choose Upgrade to Pro and leave the activation screen open.</p></article><article><b>2</b><h3>Enter your code</h3><p>Type the temporary eight-character code shown on your TV.</p></article><article><b>3</b><h3>Complete checkout</h3><p>Pay securely in your browser. Your TV unlocks after payment is verified.</p></article></div></section>

<?php elseif ($page === 'activate'): ?>
    <section class="page-hero compact shell"><span class="eyebrow"><i></i> MARS TV PRO</span><h1>Activate your device</h1><p>Keep the MarsTV activation screen open while you complete these steps.</p></section>
    <section class="activation-shell shell" id="activation-app" aria-live="polite">
      <ol class="stepper" aria-label="Activation progress"><li class="active" data-step-indicator="1"><b>1</b><span>Enter code</span></li><li data-step-indicator="2"><b>2</b><span>Confirm device</span></li><li data-step-indicator="3"><b>3</b><span>Checkout</span></li></ol>
      <div class="activation-card">
        <div class="form-state" data-step="1">
          <span class="card-label">STEP 1 OF 3</span><h2>Enter the code on your screen</h2><p>Codes expire after 10 minutes. They never contain your IPTV login or playlist.</p>
          <form id="activation-form" novalidate>
            <label for="activation-code">Activation code</label>
            <input id="activation-code" name="code" inputmode="text" autocomplete="one-time-code" maxlength="9" placeholder="7K9P-W4QH" aria-describedby="code-help code-error">
            <small id="code-help">Eight letters or numbers, shown as XXXX-XXXX</small><p class="field-error" id="code-error" role="alert"></p>
            <button class="button primary wide" type="submit"><span>Continue</span><span class="spinner" hidden></span></button>
          </form>
          <div class="trust-row"><?= render_icon('shield') ?><span><strong>Private by design</strong>MarsTV never asks for your IPTV username, password or playlist here.</span></div>
        </div>
        <div class="form-state" data-step="2" hidden>
          <span class="card-label">STEP 2 OF 3</span><h2>Confirm this is your device</h2><p>Only continue if these details match the activation screen in MarsTV.</p>
          <dl class="device-summary"><div><dt>Device</dt><dd id="device-name">Android TV</dd></div><div><dt>Device ID</dt><dd id="device-code">MARS-••••</dd></div><div><dt>Code expires</dt><dd id="device-expiry">--:--</dd></div></dl>
          <p class="notice warning">Redeeming this session invalidates both the manual code and QR link. Do not continue on a shared computer.</p>
          <button class="button primary wide" id="confirm-device" type="button">Yes, this is my device</button><button class="text-button" id="cancel-device" type="button">This is not my device</button>
        </div>
        <div class="form-state" data-step="3" hidden>
          <span class="card-label">STEP 3 OF 3</span><h2>Ready for MarsTV Pro</h2><div class="price-row"><span>Lifetime Pro<br><small>One current device</small></span><strong><?= e((string) config('price_label')) ?></strong></div>
          <ul class="mini-list"><li>One-time payment</li><li>No MarsTV account required</li><li>One activated installation</li></ul>
          <div class="legal-use-box">
            <strong>Legal-use confirmation</strong>
            <p>MarsTV is a media player. It does not provide channels, playlists or IPTV subscriptions. Use it only with personal media, licensed providers, private organizational streams, legally available free-to-air streams, or software testing sources you are authorized to access.</p>
            <label class="legal-check"><input id="legal-use-acceptance" type="checkbox"><span>I will not use MarsTV for unauthorized copyrighted content. I accept the <a href="/terms" target="_blank" rel="noopener">Terms</a> and acknowledge the <a href="/privacy" target="_blank" rel="noopener">Privacy Policy</a>.</span></label>
            <p class="field-error" id="legal-use-error" role="alert"></p>
          </div>
          <button class="button primary wide" id="checkout-button" type="button">Accept and continue to checkout</button><p class="form-note">Checkout is provided by Freemius, the merchant of record. MarsTV does not receive your card details.</p>
        </div>
        <div class="form-state status-state" data-step="error" hidden><span class="status-icon">!</span><h2 id="activation-error-title">We could not continue</h2><p id="activation-error-message">Try again from the MarsTV activation screen.</p><button class="button ghost" id="try-again" type="button">Try another code</button></div>
      </div>
      <aside class="activation-help"><h3>Where is my code?</h3><p>Open MarsTV on your device, then go to <strong>Settings → Upgrade to Pro</strong>. The code and QR link appear together.</p><hr><h3>Already purchased?</h3><p>Your licence covers one active installation. Deactivate the old installation in the Freemius Customer Portal before requesting a restore or transfer.</p><a href="/support">Get activation help →</a></aside>
    </section>

<?php elseif ($page === 'download'): ?>
    <section class="page-hero shell"><span class="eyebrow"><i></i> DIRECT APK</span><h1>MarsTV for Android</h1><p>Install the official signed release on Android TV, Google TV, Fire TV, phones and tablets.</p></section>
    <section class="download-card shell"><div class="download-mark"><img src="/assets/marstv-icon.svg" alt="MarsTV icon"></div><div class="download-copy"><span class="card-label">LATEST DIRECT RELEASE</span><h2>MarsTV <?= e((string) config('apk_version')) ?></h2><p>Use only the download published here. Android may ask you to allow installs from your browser or file manager.</p><dl><div><dt>Package</dt><dd>tv.mars.app</dd></div><div><dt>Format</dt><dd>Signed APK</dd></div><?php if (config('apk_sha256')): ?><div><dt>SHA-256</dt><dd class="hash"><?= e((string) config('apk_sha256')) ?></dd></div><?php endif; ?></dl>
      <?php if ($apkAvailable): ?><a class="button primary" href="<?= e((string) config('apk_url')) ?>" rel="nofollow">Download APK <?= render_icon('download') ?></a><?php else: ?><button class="button disabled" disabled>Release download coming soon</button><?php endif; ?>
    </div></section>
    <section class="section shell"><div class="two-col"><article class="info-card"><h3>Before you install</h3><ol><li>Download the APK to your Android device.</li><li>Open the downloaded file.</li><li>Approve installation from this source if Android asks.</li><li>Open MarsTV and add your authorized source.</li></ol></article><article class="info-card"><h3>Updates stay compatible</h3><p>Official updates use the same application ID and signing identity so they install over the previous release without erasing local settings.</p><p>Never uninstall solely to update unless support specifically confirms a signing issue.</p></article></div></section>

<?php elseif ($page === 'support'): ?>
    <section class="page-hero shell"><span class="eyebrow"><i></i> SUPPORT</span><h1>Get back to watching.</h1><p>Start with the issue below. Never include IPTV credentials or full playlist URLs in a support message.</p></section>
    <section class="section shell"><div class="support-grid"><article class="info-card"><span class="icon-wrap"><?= render_icon('devices') ?></span><h3>Restore or transfer Pro</h3><p>Deactivate the old installation in the Freemius Customer Portal first. Then include the new public Device ID and your receipt email. Never email your licence key.</p></article><article class="info-card"><span class="icon-wrap"><?= render_icon('play') ?></span><h3>Playback or playlist issue</h3><p>Include device model, Android/Fire OS version, MarsTV version and the exact error. Redact usernames, passwords and URL query strings.</p></article><article class="info-card"><span class="icon-wrap"><?= render_icon('download') ?></span><h3>Installation or update</h3><p>Tell us the installed version and error. Do not uninstall a paid installation before confirming that your licence can be restored.</p></article></div><div class="contact-panel"><div><?= render_icon('mail') ?></div><span><small>EMAIL SUPPORT</small><a href="mailto:<?= e((string) config('support_email')) ?>"><?= e((string) config('support_email')) ?></a><p>Target response: within 2 business days.</p></span></div></section>

<?php elseif (in_array($page, ['privacy','terms','refunds'], true)): ?>
    <article class="legal shell">
      <?php if ($page === 'privacy'): ?><span class="eyebrow"><i></i> LAST UPDATED SEPTEMBER 5, 2026</span><h1>Privacy Policy</h1><p class="legal-lede">MarsTV is designed to keep your television source data on your device. This policy covers the marstv.online website and Pro licensing service.</p><h2>Information we process</h2><p>We process a pseudonymous Device ID and public device key, app and platform version, activation state, Freemius order and licence references, receipt email, product, amount, currency, licence status and the version and time of your legal-use acceptance. Freemius processes payment and billing information as merchant of record; MarsTV does not receive or store full card information.</p><h2>What we do not collect</h2><p>The licensing service does not collect IPTV usernames, passwords, M3U or Xtream URLs, channel names, favourites, viewing history, parental PINs, hardware MAC addresses or advertising identifiers.</p><h2>Why we use this information</h2><p>We use the minimum information needed to activate Pro, verify purchases, prevent abuse, deliver updates and meet accounting obligations.</p><h2>Retention</h2><p>Unlicensed device records are generally removed after 90 days of inactivity. Operational logs are kept for 30 days, with raw IP addresses limited to 7 days. Financial records may be retained for six years after the relevant tax year. Minimal licence records are kept while a lifetime licence remains active so it can be verified.</p><h2>Payment provider</h2><p>Freemius processes checkout, payment, tax and receipt information under its own privacy terms. The Freemius checkout loads only after you confirm the device and accept the legal-use terms.</p><h2>Your choices</h2><p>You may request access, correction or deletion by emailing <a href="mailto:<?= e((string) config('privacy_email')) ?>"><?= e((string) config('privacy_email')) ?></a>. Full licence deletion requires licence surrender and cannot erase a token from a device that never reconnects.</p><h2>Security and contact</h2><p>Licensing traffic uses HTTPS and signed device proofs. No internet service is perfectly secure. Questions or complaints can be sent to the privacy address above.</p>
      <?php elseif ($page === 'terms'): ?><span class="eyebrow"><i></i> LAST UPDATED SEPTEMBER 5, 2026</span><h1>Terms of Use</h1><p class="legal-lede">MarsTV is a provider-neutral media player. It does not sell, supply, bundle, endorse or recommend television channels, playlists or IPTV subscriptions.</p><h2>Permitted use</h2><p>You may use MarsTV to stream your own personal media, access content from official licensed providers, manage authorized private streams for a business, institution or live event, view legally available free-to-air public streams, and perform lawful software development or testing.</p><h2>Prohibited use</h2><p>You must not use MarsTV to obtain or stream copyrighted content without authorization. You are solely responsible for confirming that you have the rights or permission required for every source you add and for complying with applicable laws.</p><h2>Free and Pro</h2><p>Free functionality may have the limits shown in the application. MarsTV Pro costs US $12.99 as a one-time purchase and unlocks the purchased Pro v1 features for one active installation at a time. “Lifetime” means that entitlement has no server-enforced expiry. It does not guarantee support for obsolete Android versions, third-party services or every future feature.</p><h2>Installation and transfer</h2><p>You may move Pro to a replacement or reinstalled device after deactivating the previous Freemius installation. Successful transfers are limited to one per rolling 30 days to prevent abuse. Clearing app data, uninstalling or factory-resetting can remove the local credential, so keep your Freemius receipt and licence key private.</p><h2>Availability</h2><p>We aim to keep licensing and downloads available, but third-party providers, networks and devices remain outside our control. Free local playback and a valid cached lifetime entitlement are designed not to depend on continuous MarsTV backend availability.</p><h2>Acceptable use</h2><p>Do not attack, overload, reverse engineer for abuse, bypass licensing, automate activation attempts, distribute modified MarsTV packages or use the service to infringe rights.</p><h2>Contact</h2><p>Questions can be sent to <a href="mailto:<?= e((string) config('support_email')) ?>"><?= e((string) config('support_email')) ?></a>.</p>
      <?php else: ?><span class="eyebrow"><i></i> DRAFT FOR PAYMENT-PROVIDER REVIEW</span><h1>Refund Policy</h1><p class="legal-lede">The final refund eligibility window must be approved before paid checkout is enabled. Until then, MarsTV must not accept real payments.</p><h2>Before purchasing</h2><p>Use the Free version to confirm that MarsTV loads and plays your authorized source on your device. IPTV-provider availability and content quality are not controlled by MarsTV.</p><h2>Technical problems</h2><p>Contact <a href="mailto:<?= e((string) config('support_email')) ?>"><?= e((string) config('support_email')) ?></a> with your receipt email, public Device ID, device model, MarsTV version and error. Never send IPTV credentials.</p><h2>Refund effects</h2><p>A verified refund or chargeback revokes Pro the next time the licensed device completes an authenticated online check. Local IPTV accounts and viewing data are not deleted by a licence change.</p><h2>Required launch decision</h2><p>This page is intentionally marked as a draft. Configure and publish the final refund window, eligibility rules, exclusions, process and payment-provider requirements before making checkout available.</p><?php endif; ?>
    </article>

<?php elseif ($page === 'payment-success'): ?>
    <section class="result-page shell"><span class="status-icon success"><?= render_icon('check') ?></span><h1>Payment received</h1><p>Your payment is being verified. Keep MarsTV open. Pro normally unlocks within 10 seconds after the payment webhook is confirmed.</p><div class="notice">A browser success page is not proof of payment. MarsTV unlocks only after server verification.</div><a class="button primary" href="/support">Need help?</a></section>
<?php elseif ($page === 'payment-cancelled'): ?>
    <section class="result-page shell"><span class="status-icon">×</span><h1>Checkout cancelled</h1><p>No Pro licence was issued. If your activation session is still valid, return to it or generate a new code in MarsTV.</p><a class="button primary" href="/activate">Try another code</a></section>
<?php else: ?>
    <section class="result-page shell"><span class="status-icon">404</span><h1>Lost in orbit</h1><p>The page you requested does not exist.</p><a class="button primary" href="/">Return home</a></section>
<?php endif; ?>
  </main>
  <footer><div class="shell footer-grid"><div><a class="brand" href="/"><img src="/assets/marstv-logo.svg" alt="MarsTV"></a><p>Your channels. One orbit.</p></div><div><strong>Product</strong><a href="/download">Download</a><a href="/activate">Activate Pro</a><a href="/support">Support</a></div><div><strong>Legal</strong><a href="/privacy">Privacy</a><a href="/terms">Terms</a><a href="/refunds">Refunds</a></div></div><div class="shell footer-bottom"><span>© <?= date('Y') ?> MarsTV</span><span>Player only. No content included.</span></div></footer>
  <script nonce="<?= e(nonce()) ?>">window.MARS_CONFIG={apiBase:<?= json_encode($apiBase, JSON_HEX_TAG|JSON_HEX_AMP|JSON_HEX_APOS|JSON_HEX_QUOT) ?>,termsVersion:<?= json_encode((string) config('legal_terms_version'), JSON_HEX_TAG|JSON_HEX_AMP|JSON_HEX_APOS|JSON_HEX_QUOT) ?>};</script>
  <script src="/assets/app.js" defer></script>
</body>
</html>
