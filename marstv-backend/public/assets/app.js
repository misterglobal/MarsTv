(() => {
  'use strict';

  const menuButton = document.querySelector('.menu-button');
  const menu = document.querySelector('#main-nav');
  menuButton?.addEventListener('click', () => {
    const open = menu?.classList.toggle('open') ?? false;
    menuButton.setAttribute('aria-expanded', String(open));
  });

  if (document.body.dataset.page !== 'activate') return;

  const apiBase = (window.MARS_CONFIG?.apiBase || '/api/v1').replace(/\/$/, '');
  const form = document.querySelector('#activation-form');
  const input = document.querySelector('#activation-code');
  const error = document.querySelector('#code-error');
  const submit = form?.querySelector('button[type="submit"]');
  const spinner = submit?.querySelector('.spinner');
  const submitText = submit?.querySelector('span:first-child');
  const confirmButton = document.querySelector('#confirm-device');
  const cancelButton = document.querySelector('#cancel-device');
  const checkoutButton = document.querySelector('#checkout-button');
  const legalAcceptance = document.querySelector('#legal-use-acceptance');
  const legalError = document.querySelector('#legal-use-error');
  const tryAgainButton = document.querySelector('#try-again');
  let credential = null;
  let redemption = null;
  let expiryTimer = null;
  let freemiusLoader = null;
  let freemiusHandler = null;
  let checkoutOpen = false;
  let purchaseClaim = null;

  const params = new URLSearchParams(location.search);
  const qrSecret = params.get('s');
  if (qrSecret) {
    credential = { type: 'qr', value: qrSecret };
    history.replaceState({}, '', '/activate');
    previewCredential();
  }

  input?.addEventListener('input', () => {
    const clean = input.value.toUpperCase().replace(/[^A-HJ-NP-Z2-9]/g, '').slice(0, 8);
    input.value = clean.length > 4 ? `${clean.slice(0, 4)}-${clean.slice(4)}` : clean;
    input.classList.remove('invalid');
    error.textContent = '';
  });

  form?.addEventListener('submit', async (event) => {
    event.preventDefault();
    const clean = input.value.replace(/-/g, '');
    if (!/^[A-HJ-NP-Z2-9]{8}$/.test(clean)) {
      input.classList.add('invalid');
      error.textContent = 'Enter the complete eight-character code.';
      input.focus();
      return;
    }
    credential = { type: 'manual', value: `${clean.slice(0, 4)}-${clean.slice(4)}` };
    await previewCredential();
  });

  confirmButton?.addEventListener('click', async () => {
    if (!credential) return showError('Session unavailable', 'Generate a new activation code in MarsTV and try again.');
    setButtonBusy(confirmButton, true, 'Securing session…');
    try {
      redemption = await api('/activation-sessions/redeem', {
        method: 'POST',
        body: { credentialType: credential.type, credential: credential.value }
      });
      storeRedemption(redemption);
      credential = null;
      showStep(3);
    } catch (reason) {
      showApiError(reason);
    } finally {
      setButtonBusy(confirmButton, false, 'Yes, this is my device');
    }
  });

  checkoutButton?.addEventListener('click', async () => {
    if (checkoutOpen) return;
    redemption ||= restoreRedemption();
    if (!redemption?.csrfToken) {
      try {
        redemption = await api('/activation-sessions/resume', { method: 'POST', body: {} });
        if (!redemption?.csrfToken) throw new ApiError('MALFORMED_API_RESPONSE', 502);
        storeRedemption(redemption);
      } catch (reason) {
        return showApiError(reason);
      }
    }
    if (!legalAcceptance?.checked) {
      legalError.textContent = 'Accept the legal-use terms before continuing.';
      legalAcceptance?.focus();
      return;
    }
    legalError.textContent = '';
    checkoutOpen = true;
    setButtonBusy(checkoutButton, true, 'Opening checkout…');
    try {
      const result = await api('/checkout/create', {
        method: 'POST',
        headers: { 'X-CSRF-Token': redemption.csrfToken },
        body: {
          csrfToken: redemption.csrfToken,
          legalAccepted: true,
          termsVersion: window.MARS_CONFIG?.termsVersion || ''
        }
      });
      if (result.provider !== 'freemius' || !result.checkout) throw new ApiError('INVALID_CHECKOUT_CONFIGURATION', 502);
      await loadFreemius();
      openFreemius(result.checkout);
    } catch (reason) {
      checkoutOpen = false;
      showApiError(reason);
      setButtonBusy(checkoutButton, false, 'Accept and continue to checkout');
    }
  });

  legalAcceptance?.addEventListener('change', () => {
    if (legalAcceptance.checked) legalError.textContent = '';
  });

  cancelButton?.addEventListener('click', reset);
  tryAgainButton?.addEventListener('click', reset);

  async function previewCredential() {
    setFormBusy(true);
    try {
      const result = await api('/activation-sessions/preview', {
        method: 'POST',
        body: { credentialType: credential.type, credential: credential.value }
      });
      document.querySelector('#device-name').textContent = result.device?.displayName || 'Android device';
      document.querySelector('#device-code').textContent = result.device?.deviceCode || 'Unknown';
      startCountdown(result.expiresAt);
      showStep(2);
    } catch (reason) {
      if (qrSecret) showApiError(reason);
      else showInlineError(reason);
    } finally {
      setFormBusy(false);
    }
  }

  async function api(path, options = {}) {
    const response = await fetch(`${apiBase}${path}`, {
      method: options.method || 'GET',
      credentials: 'same-origin',
      headers: { 'Accept': 'application/json', 'Content-Type': 'application/json', ...(options.headers || {}) },
      body: options.body ? JSON.stringify(options.body) : undefined,
      redirect: 'error'
    });
    let payload = {};
    let parsed = true;
    try { payload = await response.json(); } catch (_) { parsed = false; }
    if (!response.ok) throw new ApiError(
      payload.code || 'SERVICE_TEMPORARILY_UNAVAILABLE',
      response.status,
      payload.message,
      response.headers.get('Retry-After'),
      payload.reference || response.headers.get('X-Mars-Error-Reference'),
      [response.headers.get('X-Mars-Error-Type'), response.headers.get('X-Mars-Error-Location')].filter(Boolean).join(' at ')
    );
    if (!parsed) throw new ApiError('MALFORMED_API_RESPONSE', 502);
    return payload;
  }

  function loadFreemius() {
    if (window.FS?.Checkout) return Promise.resolve();
    if (freemiusLoader) return freemiusLoader;
    freemiusLoader = new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = 'https://checkout.freemius.com/js/v1/';
      script.async = true;
      script.onload = () => window.FS?.Checkout ? resolve() : reject(new ApiError('CHECKOUT_LOAD_FAILED', 502));
      script.onerror = () => reject(new ApiError('CHECKOUT_LOAD_FAILED', 502));
      document.head.appendChild(script);
    });
    return freemiusLoader;
  }

  function openFreemius(config) {
    const options = {
      product_id: config.productId,
      plan_id: Number(config.planId),
      public_key: config.publicKey,
      image: config.image,
      billing_cycle: config.billingCycle,
      currency: config.currency,
      licenses: config.licenses,
      disable_licenses_selector: true,
      hide_licenses: true,
      show_confirmation_dialog: true,
      show_refund_badge: true,
      gdpr: 'default'
    };
    if (config.sandbox) options.sandbox = config.sandbox;
    freemiusHandler ||= new window.FS.Checkout(options);
    freemiusHandler.open({
      name: 'MarsTV Pro',
      licenses: 1,
      purchaseCompleted: (response) => {
        purchaseClaim = claimPurchase(response);
        setButtonBusy(checkoutButton, true, 'Verifying payment…');
      },
      success: async (response) => {
        try {
          await (purchaseClaim || claimPurchase(response));
        } catch (_) {
          // The signed Freemius webhook remains authoritative. The status page
          // explains that verification can continue even if this browser call fails.
        }
        location.assign('/payment/success');
      },
      cancel: () => {
        checkoutOpen = false;
        setButtonBusy(checkoutButton, false, 'Accept and continue to checkout');
      }
    });
  }

  async function claimPurchase(response) {
    const purchase = response?.purchase || {};
    const licenseId = purchase.license_id ?? response?.license?.id;
    const planId = purchase.plan_id;
    if (!licenseId || !planId) throw new ApiError('INVALID_PURCHASE_RESPONSE', 502);
    return api('/checkout/freemius/claim', {
      method: 'POST',
      headers: { 'X-CSRF-Token': redemption.csrfToken },
      body: {
        csrfToken: redemption.csrfToken,
        purchaseId: purchase.id ?? null,
        licenseId: licenseId,
        planId: planId
      }
    });
  }

  class ApiError extends Error {
    constructor(code, status, message, retryAfter, reference, diagnostic) {
      super(message || code); this.code = code; this.status = status; this.retryAfter = retryAfter; this.reference = reference; this.diagnostic = diagnostic;
    }
  }

  function showInlineError(reason) {
    input.classList.add('invalid');
    error.textContent = errorMessage(reason);
    input.focus();
  }

  function showApiError(reason) {
    const titles = {
      ACTIVATION_RATE_LIMITED: 'Too many attempts',
      PURCHASE_PENDING: 'Payment is still processing',
      SERVICE_TEMPORARILY_UNAVAILABLE: 'Service temporarily unavailable'
    };
    showError(titles[reason?.code] || 'We could not continue', errorMessage(reason));
  }

  function errorMessage(reason) {
    const messages = {
      INVALID_REQUEST: 'Check the activation code and try again.',
      DEVICE_NOT_FOUND: 'This activation session is no longer available. Generate a new code in MarsTV.',
      ACTIVATION_EXPIRED: 'This code has expired. Generate a new one in MarsTV.',
      ACTIVATION_REDEEMED: 'This activation session was already opened in another browser. Generate a new code in MarsTV.',
      ACTIVATION_RATE_LIMITED: `Too many attempts. Try again${reason?.retryAfter ? ` in ${reason.retryAfter} seconds` : ' shortly'}.`,
      CHECKOUT_DISABLED: 'Purchases are not available yet. Please check back after the Pro launch.',
      CHECKOUT_PRODUCT_ID_MISSING: 'Checkout configuration is missing the Freemius product ID.',
      CHECKOUT_PLAN_ID_MISSING: 'Checkout configuration is missing the Freemius plan ID.',
      CHECKOUT_PUBLIC_KEY_MISSING: 'Checkout configuration is missing the Freemius public key.',
      CHECKOUT_SECRET_KEY_MISSING: 'Checkout configuration is missing the Freemius secret key.',
      MALFORMED_API_RESPONSE: 'The activation service returned an unreadable response. Please try again.',
      LEGAL_ACCEPTANCE_REQUIRED: 'Accept the legal-use terms before opening checkout.',
      PURCHASE_ALREADY_CLAIMED: 'This purchase is already linked to another activation. Contact support before paying again.',
      CHECKOUT_LOAD_FAILED: 'Secure checkout could not load. Check your connection or content blocker and try again.',
      INVALID_CHECKOUT_CONFIGURATION: 'Checkout is temporarily unavailable. No payment was taken.',
      INVALID_PURCHASE_RESPONSE: 'Payment completed, but automatic linking was interrupted. Keep your Freemius receipt and contact support.',
      INVALID_CHECKOUT_URL: 'The checkout provider returned an invalid address. No payment was taken.',
      SERVICE_TEMPORARILY_UNAVAILABLE: 'The activation service could not be reached. Your free player is unaffected; try again shortly.'
    };
    const message = messages[reason?.code] || 'We could not verify this activation session. Generate a new code in MarsTV and try again.';
    const reference = reason?.reference ? ` Reference: ${reason.reference}.` : '';
    const diagnostic = reason?.diagnostic ? ` Diagnostic: ${reason.diagnostic}.` : '';
    return `${message}${reference}${diagnostic}`;
  }

  function showStep(number) {
    document.querySelectorAll('[data-step]').forEach((node) => { node.hidden = node.dataset.step !== String(number); });
    document.querySelectorAll('[data-step-indicator]').forEach((node) => node.classList.toggle('active', Number(node.dataset.stepIndicator) <= Number(number)));
  }

  function showError(title, message) {
    clearInterval(expiryTimer);
    document.querySelector('#activation-error-title').textContent = title;
    document.querySelector('#activation-error-message').textContent = message;
    showStep('error');
  }

  function storeRedemption(value) {
    if (!value?.csrfToken || !value?.expiresAt) return;
    try { sessionStorage.setItem('marstv_redemption', JSON.stringify(value)); } catch (_) { /* storage unavailable */ }
  }

  function restoreRedemption() {
    try {
      const value = JSON.parse(sessionStorage.getItem('marstv_redemption') || 'null');
      const expiresAt = Date.parse(value?.expiresAt || '');
      if (value?.csrfToken && Number.isFinite(expiresAt) && expiresAt > Date.now()) return value;
    } catch (_) { /* invalid or unavailable storage */ }
    clearStoredRedemption();
    return null;
  }

  function clearStoredRedemption() {
    try { sessionStorage.removeItem('marstv_redemption'); } catch (_) { /* storage unavailable */ }
  }

  function reset() {
    clearStoredRedemption();
    clearInterval(expiryTimer);
    credential = null; redemption = null;
    input.value = ''; input.classList.remove('invalid'); error.textContent = '';
    if (legalAcceptance) legalAcceptance.checked = false;
    if (legalError) legalError.textContent = '';
    showStep(1); input.focus();
  }

  function setFormBusy(busy) {
    input.disabled = busy; submit.disabled = busy;
    spinner.hidden = !busy; submitText.textContent = busy ? 'Checking…' : 'Continue';
  }

  function setButtonBusy(button, busy, label) {
    button.disabled = busy; button.textContent = label;
  }

  function startCountdown(value) {
    clearInterval(expiryTimer);
    const deadline = Date.parse(value);
    const output = document.querySelector('#device-expiry');
    if (!Number.isFinite(deadline)) { output.textContent = 'Soon'; return; }
    const tick = () => {
      const remaining = Math.max(0, Math.ceil((deadline - Date.now()) / 1000));
      output.textContent = `${String(Math.floor(remaining / 60)).padStart(2, '0')}:${String(remaining % 60).padStart(2, '0')}`;
      if (remaining === 0) { clearInterval(expiryTimer); showError('Code expired', 'Generate a new activation code in MarsTV and try again.'); }
    };
    tick(); expiryTimer = setInterval(tick, 1000);
  }
})();
