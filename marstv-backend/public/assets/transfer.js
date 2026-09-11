(() => {
  'use strict';
  const request = document.querySelector('#transfer-request');
  if (!request) return;
  const verify = document.querySelector('#transfer-verify');
  const confirm = document.querySelector('#transfer-confirm');
  const success = document.querySelector('#transfer-success');
  const message = document.querySelector('#transfer-message');
  const restart = document.querySelector('#transfer-restart');
  const base = (window.MARS_CONFIG?.apiBase || '/api/v1').replace(/\/$/, '');
  let csrf = '';
  const errors = {
    INVALID_REQUEST: 'Check the email, order number and codes you entered.',
    TRANSFER_UNAVAILABLE: 'Online transfers are not available yet. Please contact support.',
    TRANSFER_RATE_LIMITED: 'Too many attempts. Please wait before trying again, or contact support.',
    TRANSFER_CODE_INVALID: 'That code is incorrect. Check the latest email and try again.',
    TRANSFER_SESSION_EXPIRED: 'This verification session has expired or reached its attempt limit. Please start again.',
    TRANSFER_DETAILS_INVALID: 'We could not match an active paid licence and a valid new-device activation code. Check your receipt and start again with a fresh activation code, or contact support.',
    TRANSFER_DESTINATION_LICENSED: 'The new device already has another Pro licence. Please contact support.',
    TRANSFER_CHANGED: 'The licence or device has changed. Please start again or contact support.',
    TRANSFER_ACTIVATION_EXPIRED: 'The device activation code has expired or was used elsewhere. Start again with a fresh code.',
    TRANSFER_VERIFICATION_REQUIRED: 'Please start again and verify your email before transferring.',
    TRANSFER_LIMIT_REACHED: 'This licence has reached its transfer limit: one per rolling 30 days and three per rolling 365 days. Contact support if you need an exception.',
  };
  function show(panel) {
    [request, verify, confirm, success].forEach(item => { item.hidden = item !== panel; });
    panel.querySelector('h2')?.focus();
    message.textContent = '';
    restart.hidden = panel === success;
  }
  async function send(form, endpoint, payload, next) {
    const button = form.querySelector('button');
    button.disabled = true;
    message.textContent = 'Please wait…';
    try {
      const response = await fetch(`${base}/transfers/${endpoint}`, {
        method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf },
        body: JSON.stringify(payload),
      });
      const result = await response.json();
      if (!response.ok) {
        message.textContent = errors[result.code] || 'The service is temporarily unavailable. Please try again.';
        restart.hidden = false;
        return;
      }
      next(result);
    } catch {
      message.textContent = endpoint === 'confirm'
        ? 'The response was interrupted. Retry Confirm transfer to check the result safely; it will not count twice.'
        : 'Could not reach the service. Check your connection and try again.';
    } finally { button.disabled = false; }
  }
  request.addEventListener('submit', event => {
    event.preventDefault();
    send(request, 'request', Object.fromEntries(new FormData(request)), result => {
      csrf = result.csrfToken;
      request.reset();
      show(verify);
      document.querySelector('#transfer-code').focus();
    });
  });
  verify.addEventListener('submit', event => {
    event.preventDefault();
    send(verify, 'verify', Object.fromEntries(new FormData(verify)), result => {
      document.querySelector('#transfer-old-device').textContent = result.currentDevice || 'Previous installation';
      document.querySelector('#transfer-new-device').textContent = result.newDevice;
      verify.reset();
      show(confirm);
    });
  });
  confirm.addEventListener('submit', event => {
    event.preventDefault();
    send(confirm, 'confirm', { confirmed: true }, () => show(success));
  });
})();
