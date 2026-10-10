// Field checks for the checkout and sign-in forms. Each validator returns an
// error message, or null when the value is fine.
const Validation = (() => {
  const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
  const UK_POSTCODE = /^[A-Z]{1,2}\d[A-Z\d]?\s*\d[A-Z]{2}$/i;

  function validateEmail(value) {
    return EMAIL.test(value.trim()) ? null : 'Enter a valid email address.';
  }

  function validatePostcode(value) {
    return UK_POSTCODE.test(value.trim()) ? null : 'Enter a valid UK postcode.';
  }

  // Luhn checksum: catches most mistyped card numbers before a payment call.
  function luhnValid(digits) {
    let sum = 0;
    let double = false;
    for (let i = digits.length - 1; i >= 0; i--) {
      let d = Number(digits[i]);
      if (double) {
        d *= 2;
        if (d > 9) d -= 9;
      }
      sum += d;
      double = !double;
    }
    return sum % 10 === 0;
  }

  function validateCardNumber(value) {
    const digits = value.replace(/\s+/g, '');
    if (!/^\d{13,19}$/.test(digits)) return 'Card numbers are 13 to 19 digits.';
    return luhnValid(digits) ? null : 'That card number is not valid.';
  }

  function validateExpiry(value, now = new Date()) {
    const m = /^(\d{2})\/(\d{2})$/.exec(value.trim());
    if (!m) return 'Use MM/YY.';
    const month = Number(m[1]);
    const year = 2000 + Number(m[2]);
    if (month < 1 || month > 12) return 'Month must be 01 to 12.';
    const endOfMonth = new Date(year, month, 0, 23, 59, 59);
    return endOfMonth < now ? 'This card has expired.' : null;
  }

  function showFieldError(input, message) {
    input.setAttribute('aria-invalid', message ? 'true' : 'false');
    let hint = input.parentElement.querySelector('.field-error');
    if (!hint) {
      hint = document.createElement('span');
      hint.className = 'field-error';
      input.parentElement.appendChild(hint);
    }
    hint.textContent = message || '';
  }

  return { validateEmail, validatePostcode, validateCardNumber, validateExpiry, showFieldError };
})();
