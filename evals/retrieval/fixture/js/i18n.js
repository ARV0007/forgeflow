// Interface text in English and French. Elements carry a data-i18n key; the
// language comes from the browser, or from a saved choice.
const I18n = (() => {
  const STRINGS = {
    en: {
      'nav.shop': 'Shop', 'nav.cart': 'Cart', 'nav.account': 'Account',
      'hero.title': 'Fresh food at your door in an hour',
      'hero.subtitle': 'Fruit, vegetables and bakery, picked this morning.',
      'hero.cta': 'Start shopping', 'home.featured': 'Featured this week'
    },
    fr: {
      'nav.shop': 'Boutique', 'nav.cart': 'Panier', 'nav.account': 'Compte',
      'hero.title': 'Des produits frais chez vous en une heure',
      'hero.subtitle': 'Fruits, legumes et boulangerie, cueillis ce matin.',
      'hero.cta': 'Commencer vos achats', 'home.featured': 'A la une cette semaine'
    }
  };

  function currentLanguage() {
    const saved = Storage.load('lang', null);
    if (saved && STRINGS[saved]) return saved;
    return (navigator.language || 'en').slice(0, 2) === 'fr' ? 'fr' : 'en';
  }

  function translatePage(lang = currentLanguage()) {
    const table = STRINGS[lang] || STRINGS.en;
    document.documentElement.lang = lang;
    document.querySelectorAll('[data-i18n]').forEach(el => {
      const text = table[el.dataset.i18n];
      if (text) el.firstChild.textContent = text;
    });
  }

  function setLanguage(lang) {
    Storage.save('lang', lang);
    translatePage(lang);
  }

  return { translatePage, setLanguage, currentLanguage };
})();
