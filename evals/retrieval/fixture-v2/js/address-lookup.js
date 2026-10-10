// Type a postcode, pick your house from a list, and the street and city
// fields fill themselves in. Uses a small built-in table instead of a paid
// address API.
const AddressLookup = (() => {
  const ADDRESSES = {
    'SW1A 1AA': [{ street: 'Buckingham Palace', city: 'London' }],
    'M1 1AE': [{ street: '1 Piccadilly', city: 'Manchester' }, { street: '3 Piccadilly', city: 'Manchester' }],
    'EH1 1YZ': [{ street: '10 High Street', city: 'Edinburgh' }]
  };

  function lookupAddress(postcode) {
    const key = postcode.trim().toUpperCase().replace(/\s+/g, ' ');
    return ADDRESSES[key] || [];
  }

  function fillAddress(form, address) {
    form.street.value = address.street;
    form.city.value = address.city;
  }

  function attach(form) {
    form.postcode.addEventListener('blur', () => {
      const found = lookupAddress(form.postcode.value);
      if (found.length === 1) fillAddress(form, found[0]);
    });
  }

  return { lookupAddress, fillAddress, attach };
})();
