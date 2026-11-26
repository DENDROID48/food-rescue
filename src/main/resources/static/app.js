/*
 * Food Rescue web client. Plain JavaScript, no build step, talks to the REST API under /api/v1.
 *
 * Sections: constants and helpers · photos · dialogs, toasts, forms · session and navigation ·
 * customer (search, reserve, orders) · store owner · administrator · event wiring.
 */
'use strict';

// ---------- constants and helpers ----------

const API = '/api/v1';
const TOKEN_KEY = 'foodrescue.token';
const LOCATION_KEY = 'foodrescue.location';
const PREFS_KEY = 'foodrescue.prefs';
const DEFAULT_LOCATION = { lat: 45.7537, lng: 21.2257, label: 'Timișoara center' };
/** foodrescue.max-quantity-per-reservation, loaded from GET /settings at start-up. The server enforces it. */
let maxPerReservation = 5;
/** Mirrors FoodItemPhotoService.MAX_BYTES, so people learn about a too-large photo before uploading. */
const MAX_PHOTO_BYTES = 2 * 1024 * 1024;
const PHOTO_TYPES = ['image/jpeg', 'image/png', 'image/webp'];
const RADII = [1, 2, 3, 5, 10, 25];
/** Food types a shop can state for a product (FoodTag on the server). */
const FOOD_TAGS = [['BAKERY', 'Bakery'], ['FRUIT_VEG', 'Fruit & veg'], ['READY_MEALS', 'Ready meals'], ['DAIRY', 'Dairy'],
  ['MEAT_FISH', 'Meat & fish'], ['SWEETS', 'Sweets'], ['GROCERIES', 'Groceries'], ['VEGETARIAN', 'Vegetarian'], ['VEGAN', 'Vegan']];
const TAG_LABEL = Object.fromEntries(FOOD_TAGS);

function tagChips(container, selected = [], name = 'tag') {
  container.innerHTML = FOOD_TAGS.map(([value, label]) =>
    `<label class="chip"><input type="checkbox" name="${name}" value="${value}"${selected.includes(value) ? ' checked' : ''}><span>${label}</span></label>`).join('');
}
const checkedTags = (container) => $$('input:checked', container).map((i) => i.value);

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
const hasMaps = typeof window.L !== 'undefined';

let me = null;
let token = null;

function save(key, value) {
  try { value === null ? localStorage.removeItem(key) : localStorage.setItem(key, JSON.stringify(value)); } catch { /* storage unavailable */ }
}
function restore(key) {
  try { return JSON.parse(localStorage.getItem(key)); } catch { return null; }
}

async function api(path, { method = 'GET', body, form } = {}) {
  const headers = {};
  if (token) headers.Authorization = 'Bearer ' + token;
  let payload;
  if (form) payload = form;
  else if (body !== undefined) { headers['Content-Type'] = 'application/json'; payload = JSON.stringify(body); }
  let res;
  try {
    res = await fetch(API + path, { method, headers, body: payload });
  } catch {
    throw new Error('Could not reach Food Rescue. Check your connection and try again.');
  }
  if (res.status === 204) return null;
  const data = await res.json().catch(() => null);
  if (res.status === 401 && token) { signOut(); throw new Error('Your session has ended, please sign in again'); }
  if (!res.ok) throw new Error(data?.detail || data?.title || `Something went wrong (${res.status})`);
  return data;
}

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const icon = (name, cls = '') => `<svg class="${cls}" aria-hidden="true"><use href="#i-${name}"/></svg>`;
const money = (n) => Number(n).toFixed(2) + ' lei';
const isFree = (amount) => !Number(amount);
const shortPlace = (label) => label.split(',').slice(0, 2).join(',').trim();
const initials = (name) => name.split(/\s+/).filter(Boolean).slice(0, 2).map((p) => p[0].toUpperCase()).join('');
const plural = (n, one, many = one + 's') => `${n} ${n === 1 ? one : many}`;
const spacedDigits = (code) => String(code).split('').join(' ');
const distanceText = (km) => (km < 0.1 ? 'under 100 m away' : `${km} km away`);

function sameDay(a, b) { return a.toDateString() === b.toDateString(); }
/** "18:30" today, "tomorrow 09:00", or "Tue 18:30". */
function fmtTime(iso) {
  const d = new Date(iso), now = new Date();
  const time = d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
  if (sameDay(d, now)) return time;
  const tomorrow = new Date(now); tomorrow.setDate(now.getDate() + 1);
  if (sameDay(d, tomorrow)) return 'tomorrow ' + time;
  return d.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' }) + ', ' + time;
}
const timeOnly = (iso) => new Date(iso).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
/** "18:00–20:00", with the day in front when it is not today. */
const fmtWindow = (start, end) => `${fmtTime(start)}–${timeOnly(end)}`;
function minutesUntil(iso) { return Math.round((new Date(iso) - Date.now()) / 60000); }
function untilText(iso) {
  const mins = minutesUntil(iso);
  if (mins <= 0) return 'time is up';
  if (mins < 60) return `in ${mins} min`;
  const h = Math.floor(mins / 60), m = mins % 60;
  if (h >= 24) return `in ${Math.round(h / 24)} ${Math.round(h / 24) === 1 ? 'day' : 'days'}`;
  return m ? `in ${h} h ${m} min` : `in ${h} h`;
}
/** datetime-local works in local time without a zone; convert from an ISO instant. */
function toLocalInput(iso) {
  const d = new Date(iso);
  d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
  return d.toISOString().slice(0, 16);
}

// ---------- photos ----------

/**
 * Illustrative photos for listings without a photo from the store. They are chosen from words in
 * the item's name (then its description) and are always labelled "Illustrative photo", so they
 * never pass for the real product. A photo uploaded by the store always wins.
 */
const PHOTO_RULES = [
  ['pizza', /pizza|focaccia/i],
  ['sandwich', /sandwich|wrap|panini|burger|toastie|bagel|kebab|shawarma/i],
  ['salad', /salad|poke|slaw/i],
  ['cake', /cake|dessert|brownie|\btarts?\b|cookie|muffin|cupcake|\bpies?\b|chocolate|cheesecake|pr[aă]jitur/i],
  ['pastries', /pastr|croissant|danish|\bbuns?\b|\brolls?\b|donut|doughnut|viennois|strudel|cinnamon|bakery|baked goods|patiser|covrig/i],
  ['bread', /bread|\bloaf|loaves|baguette|sourdough|\brye\b|pita|ciabatta|p[aâ]ine/i],
  ['fruit', /fruit|berr|apple|banana|orange|grape|melon|peach|\bpears?\b|plum|citrus|fructe/i],
  ['vegetables', /vegetable|veggie|\bveg\b|produce|greens|tomato|potato|carrot|cucumber|pepper|lettuce|legume|grocer/i],
  ['meal', /meal|lunch|dinner|\brice\b|pasta|soup|curry|stew|\bdish|plate|noodle|ready|prepared|hot food|m[aâ]ncare/i],
];
const PHOTO_CREDITS = {
  pastries: ['Croissants in Madrid', 'David Adam Kess', 'CC BY-SA 4.0', 'https://commons.wikimedia.org/wiki/File:Photography_by_David_Adam_Kess,_Croissant_in_Madrid_A_croissant_is_a_buttery,_flaky,_viennoiserie-pastry_named_for_its_well-known_crescent_shape.jpg'],
  bread: ['Sourdough loaf', 'Dana Hutchinson', 'CC BY 3.0', 'https://commons.wikimedia.org/wiki/File:Sourdough_Loaf_(217346645).jpeg'],
  cake: ['Piece of chocolate cake', 'Daria Yakovleva', 'CC0', 'https://commons.wikimedia.org/wiki/File:Piece_of_chocolate_cake_on_a_white_plate_decorated_with_chocolate_sauce.jpg'],
  vegetables: ['Fresh vegetables and fruits at the market', 'FranHogan', 'CC BY-SA 4.0', 'https://commons.wikimedia.org/wiki/File:Fresh_vegetables_and_fruits_at_the_market.jpg'],
  fruit: ['Bowl of fresh fruit', 'Arun Agrawal', 'CC BY-SA 3.0', 'https://commons.wikimedia.org/wiki/File:Bowl_Of_Fresh_(15445955).jpeg'],
  sandwich: ['Halloumi & roasted vegetables sandwich', 'Andy Li', 'CC0', 'https://commons.wikimedia.org/wiki/File:Halloumi_%26_Roasted_Vegetables_sandwich_-_Sunbirds_2023-09-14.jpg'],
  salad: ['Salad bowl, Kiedrich', 'Gerda Arendt', 'CC0', 'https://commons.wikimedia.org/wiki/File:Salad_bowl,_Kiedrich.jpg'],
  pizza: ['Pizza by the slice in Roma', 'Jon Gudorf Photography', 'CC BY-SA 2.0', 'https://commons.wikimedia.org/wiki/File:Pizza_by_the_slice_in_Roma,_Italy.jpg'],
  meal: ['Kraphao moo', 'Takeaway', 'CC BY-SA 3.0', 'https://commons.wikimedia.org/wiki/File:Kraphao_moo.jpg'],
  'hero-bakery': ['Bakery products on the counter', 'Andy Li', 'CC0', 'https://commons.wikimedia.org/wiki/File:Bakery_products_on_the_counter_of_Jacob%27s_Bakery_%2B_Cafe_2024-04-15.jpg'],
};

function illustrativeKey(name, description) {
  for (const text of [name, description]) {
    if (!text) continue;
    const rule = PHOTO_RULES.find(([, re]) => re.test(text));
    if (rule) return rule[0];
  }
  return null;
}

function fallbackTile(label = 'No photo yet') {
  if (!label) return `<div class="photo-fallback" aria-hidden="true">${icon('leaf')}</div>`;
  return `<div class="photo-fallback" role="img" aria-label="${esc(label)}">${icon('leaf')}<b aria-hidden="true">${esc(label)}</b></div>`;
}

/**
 * Contents of a .photo-frame: the store's photo, else an illustrative photo with a label, else a
 * branded tile. Broken images are swapped for the tile (see the error listener at the bottom).
 */
function photoHtml(item, { sizes = '(max-width: 720px) 100vw, 320px', illustrative = true, tag = true, emptyLabel = 'No photo yet' } = {}) {
  if (item.photoUrl) return `<img src="${esc(item.photoUrl)}" alt="${esc(item.name)}" loading="lazy" decoding="async">`;
  const key = illustrative && illustrativeKey(item.name, item.description);
  if (!key) return fallbackTile(emptyLabel);
  return `<img src="photos/${key}-480.webp" srcset="photos/${key}-480.webp 480w, photos/${key}-960.webp 960w" sizes="${sizes}" alt="" loading="lazy" decoding="async">`
    + (tag ? '<span class="photo-tag">Illustrative photo</span>' : '');
}

/** Images already in the cache can finish loading before the listener sees them. */
function settleImages(root) {
  for (const img of $$('.photo-frame img', root)) if (img.complete && img.naturalWidth) img.classList.add('is-loaded');
}

// ---------- dialogs, toasts, forms ----------

/**
 * Opens a modal dialog and, when it closes, returns focus to whatever opened it (or to the
 * element named in data-focus-fallback if that one was re-rendered meanwhile).
 */
function openDialog(dialog) {
  if (dialog.open) return;
  const opener = document.activeElement;
  dialog.showModal();
  dialog.addEventListener('close', () => {
    const fallback = dialog.dataset.focusFallback && $(dialog.dataset.focusFallback);
    (opener?.isConnected ? opener : fallback)?.focus?.();
  }, { once: true });
}

/** Accessible replacement for confirm(). Resolves to true when the person confirms. */
function confirmDialog({ title, body, confirmLabel = 'Confirm', cancelLabel = 'Cancel', danger = false }) {
  const d = $('#confirmDialog');
  $('#confirmTitle').textContent = title;
  $('#confirmBody').textContent = body;
  $('#confirmOk').textContent = confirmLabel;
  $('#confirmOk').className = 'btn ' + (danger ? 'btn-danger-solid' : 'btn-primary');
  $('#confirmCancel').textContent = cancelLabel;
  d.returnValue = '';
  return new Promise((resolve) => {
    d.addEventListener('close', () => resolve(d.returnValue === 'confirm'), { once: true });
    openDialog(d);
  });
}

function toast(message, kind = 'ok') {
  const box = $('#toasts');
  const el = document.createElement('div');
  el.className = 'toast ' + kind;
  el.innerHTML = icon(kind === 'error' ? 'alert' : 'check') + `<span>${esc(message)}</span>`;
  box.append(el);
  while (box.children.length > 3) box.firstElementChild.remove();
  setTimeout(() => {
    el.classList.add('is-leaving');
    setTimeout(() => el.remove(), 260);
  }, kind === 'error' ? 6500 : 4000);
}
const fail = (e) => toast(e.message, 'error');

/** Shows a spinner on the button and ignores further clicks until the action finishes. */
async function withLoading(button, action) {
  if (!button || button.classList.contains('is-loading')) return;
  button.classList.add('is-loading');
  button.setAttribute('aria-busy', 'true');
  try { return await action(); }
  finally { button.classList.remove('is-loading'); button.removeAttribute('aria-busy'); }
}

function fieldMessage(input) {
  const v = input.validity, label = input.dataset.label || 'This field';
  if (v.valueMissing) return input.type === 'file' ? 'Choose a photo' : `${label} is required`;
  if (v.typeMismatch && input.type === 'email') return 'Enter an email address like name@example.com';
  if (v.tooShort) return `Use at least ${input.minLength} characters`;
  if (v.tooLong) return `Use at most ${input.maxLength} characters`;
  if (v.badInput) return 'Enter a number';
  if (v.rangeUnderflow) return `${label} must be at least ${input.min}`;
  if (v.rangeOverflow) return `${label} must be at most ${input.max}`;
  if (v.stepMismatch) return input.step === '1' ? 'Use a whole number' : 'Use at most 2 decimals';
  if (v.patternMismatch) return input.dataset.patternMessage || 'Check the format';
  return input.validationMessage;
}
function errorElementFor(input) {
  const id = (input.getAttribute('aria-describedby') || '').split(' ').find((x) => x.endsWith('Error'));
  return id ? document.getElementById(id) : null;
}
function showFieldError(input, message) {
  const el = errorElementFor(input);
  if (el) el.textContent = message || '';
  if (message) input.setAttribute('aria-invalid', 'true'); else input.removeAttribute('aria-invalid');
}
/** Checks every field, shows messages next to them and focuses the first problem. */
function validateForm(form) {
  let first = null;
  for (const input of $$('input, select, textarea', form)) {
    if (input.type === 'hidden' || input.type === 'radio' || input.type === 'file') continue;
    const ok = input.checkValidity();
    showFieldError(input, ok ? '' : fieldMessage(input));
    if (!ok && !first) first = input;
  }
  first?.focus();
  return !first;
}
function setAlert(selector, message) { $(selector).textContent = message || ''; }

// ---------- session and navigation ----------

const ROLE_TEXT = { CONSUMER: 'Customer', STORE_OWNER: 'Store owner', ADMIN: 'Administrator' };
const VIEWS = {
  CONSUMER: [['shop', 'Discover', 'search'], ['orders', 'My orders', 'receipt'], ['impact', 'My impact', 'star']],
  STORE_OWNER: [['store', 'My store', 'store']],
  ADMIN: [['admin', 'Admin', 'shield']],
};
const VIEW_TITLES = { auth: 'Welcome', shop: 'Discover', orders: 'My orders', impact: 'My impact', store: 'My store', admin: 'Admin' };
let currentView = null;

function showView(name, { focus = false } = {}) {
  const changed = currentView !== name;
  currentView = name;
  for (const s of $$('main > section')) s.hidden = s.id !== 'view-' + name;
  for (const b of $$('[data-view]')) {
    if (b.dataset.view === name) b.setAttribute('aria-current', 'page'); else b.removeAttribute('aria-current');
  }
  document.title = `${VIEW_TITLES[name]} · Food Rescue`;
  if (changed) window.scrollTo({ top: 0 });
  if (name === 'shop') shopMap?.invalidateSize();
  if (name === 'orders') loadMyOrders();
  if (name === 'impact') loadImpact();
  if (focus) $(`#view-${name} h1`)?.focus({ preventScroll: true });
}

function renderNav() {
  const views = VIEWS[me.role];
  const button = ([v, label, ic]) => `<button type="button" data-view="${v}">${icon(ic)}<span>${label}</span>${v === 'orders' ? '<span class="nav-dot" data-orders-dot hidden></span>' : ''}</button>`;
  $('#mainNav').innerHTML = views.map(button).join('');
  $('#mainNav').hidden = views.length < 2;
  $('#bottomNav').innerHTML = views.map(button).join('');
  $('#bottomNav').hidden = views.length < 2;
}

async function signIn(auth) {
  token = auth.token;
  save(TOKEN_KEY, token);
  await startSession();
}

async function signOut() {
  // A shared phone or computer must not keep receiving the previous person's alerts.
  if (me?.role === 'CONSUMER') await Promise.race([releasePush(), new Promise((r) => setTimeout(r, 1500))]);
  token = null; me = null; currentStoreId = null; myOrders = []; historyShown = HISTORY_PAGE; nearbyItems = [];
  radar = null; favStores = new Set(); favProducts = new Set(); radarProfile = null;
  $('#forYou').hidden = true;
  stopInbox();
  $('#inboxBtn').hidden = true;
  save(TOKEN_KEY, null);
  $('#toasts').innerHTML = '';
  delete document.body.dataset.role;
  $('#mainNav').hidden = true;
  $('#bottomNav').hidden = true;
  $('#account').hidden = true;
  showView('auth');
}

async function startSession() {
  try {
    me = await api('/users/me');
  } catch {
    signOut();
    return;
  }
  document.body.dataset.role = me.role;
  $('#accountName').textContent = me.fullName;
  $('#accountRole').textContent = ROLE_TEXT[me.role];
  $('#accountAvatar').textContent = initials(me.fullName);
  $('#account').hidden = false;
  renderNav();
  showView(VIEWS[me.role][0][0]);
  $('#inboxBtn').hidden = me.role !== 'CONSUMER';
  if (me.role === 'CONSUMER') {
    initShopMap(); renderEligibility(); search(); loadMyOrders(); startInbox(); syncPush();
    api('/me/radar-profile').then((p) => { radarProfile = p; })
      .catch(() => { radarProfile = { freeItemsOffer: true }; }) // can't tell, so show it as before
      .finally(() => { if (me?.role === 'CONSUMER') renderEligibility(); });
    openLinkedAlert();
  }
  if (me.role === 'STORE_OWNER') loadStores();
  if (me.role === 'ADMIN') loadAdmin();
}

function setAuthTab(which) {
  const login = which === 'login';
  $('#tabLogin').setAttribute('aria-selected', String(login));
  $('#tabRegister').setAttribute('aria-selected', String(!login));
  $('#tabLogin').tabIndex = login ? 0 : -1;
  $('#tabRegister').tabIndex = login ? -1 : 0;
  $('#loginForm').hidden = !login;
  $('#registerForm').hidden = login;
}

async function login(event) {
  event.preventDefault();
  const f = event.target;
  setAlert('#loginAlert', '');
  if (!validateForm(f)) return;
  await withLoading($('[type=submit]', f), async () => {
    try {
      await signIn(await api('/auth/login', { method: 'POST', body: { email: f.email.value.trim(), password: f.password.value } }));
      f.reset(); // no "welcome back" toast: the account button already shows who is signed in
    } catch (e) { setAlert('#loginAlert', e.message); }
  });
}

async function register(event) {
  event.preventDefault();
  const f = event.target;
  setAlert('#registerAlert', '');
  if (!validateForm(f)) return;
  await withLoading($('[type=submit]', f), async () => {
    try {
      const auth = await api('/users', { method: 'POST', body: {
        fullName: f.fullName.value.trim(), email: f.email.value.trim(), password: f.password.value, role: f.role.value } });
      f.reset();
      await signIn(auth);
      toast(`Welcome to Food Rescue, ${auth.user.fullName.split(' ')[0]}!`);
    } catch (e) { setAlert('#registerAlert', e.message); }
  });
}

function togglePassword(button) {
  const input = document.getElementById(button.dataset.togglePassword);
  const show = input.type === 'password';
  input.type = show ? 'text' : 'password';
  button.setAttribute('aria-pressed', String(show));
  button.setAttribute('aria-label', show ? 'Hide password' : 'Show password');
  button.innerHTML = icon(show ? 'eye-off' : 'eye');
}

// ---------- customer: where am I ----------

let location_ = restore(LOCATION_KEY) || DEFAULT_LOCATION;
let radiusKm = RADII.includes(restore(PREFS_KEY)?.radius) ? restore(PREFS_KEY).radius : 3;
let shopMap, youMarker, radiusCircle, storeLayer;

function tileLayer() {
  return L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '&copy; OpenStreetMap contributors' });
}

function initShopMap() {
  if (!hasMaps) {
    $('#mapCard').hidden = true;
    $('#mapToggle').hidden = true;
    $('#browse').classList.add('no-map');
    return;
  }
  if (shopMap) return;
  shopMap = L.map('shopMap', { scrollWheelZoom: false }).setView([location_.lat, location_.lng], 14);
  tileLayer().addTo(shopMap);
  storeLayer = L.layerGroup().addTo(shopMap);
  shopMap.on('click', async (e) => {
    const { lat, lng } = e.latlng;
    setLocation({ lat, lng, label: 'the selected point' });
    try { const place = await api(`/geocode/reverse?lat=${lat}&lng=${lng}`); setLocation({ lat, lng, label: shortPlace(place.label) }, false); }
    catch { /* keep the generic label */ }
  });
}

function setLocation(loc, refresh = true) {
  location_ = loc;
  save(LOCATION_KEY, loc);
  updateWhere();
  closeSuggestions();
  setSearchOpen(false);
  if (refresh) search();
}

function updateWhere() {
  $('#locationLabel').textContent = location_.label;
  $('#summaryPlace').textContent = location_.label;
  $('#summaryRadius').textContent = `within ${radiusKm} km`;
  $('#radiusText').textContent = radiusKm + ' km';
  $('#heroWhere').textContent = `within ${radiusKm} km of ${location_.label}`;
  const radio = $(`input[name="radius"][value="${radiusKm}"]`);
  if (radio) radio.checked = true;
}

/** Phones only (the summary line is hidden on wider screens): open or close the full search form. */
function setSearchOpen(open) {
  const form = $('#placeForm');
  const hadFocus = form.contains(document.activeElement) && document.activeElement !== $('#placeSummary');
  form.classList.toggle('is-collapsed', !open);
  $('#placeSummary').setAttribute('aria-expanded', String(open));
  $('#summaryAction').textContent = open ? 'Done' : 'Change';
  // Focus must not stay on a control that has just been hidden.
  if (!open && hadFocus && $('#placeSummary').offsetParent) $('#placeSummary').focus();
}

function closeSuggestions() {
  $('#placeSuggestions').hidden = true;
  $('#placeSuggestions').innerHTML = '';
}

async function searchPlace(event) {
  event.preventDefault();
  const input = $('#placeQuery');
  const q = input.value.trim();
  if (q.length < 3) { toast('Type at least 3 letters of a place', 'error'); input.focus(); return; }
  await withLoading($('#placeForm .search-submit'), async () => {
    try {
      const places = await api('/geocode?q=' + encodeURIComponent(q));
      if (!places.length) { toast('No place found with that name. Try adding the city.', 'error'); return; }
      if (places.length === 1) { choosePlace(places[0]); return; }
      const box = $('#placeSuggestions');
      box.places = places;
      box.innerHTML = '<div class="suggestions-title">Did you mean</div>' + places.map((p, i) =>
        `<button type="button" data-place="${i}">${icon('pin')}<span>${esc(p.label)}</span></button>`).join('');
      box.hidden = false;
      $('button', box).focus();
    } catch (e) { fail(e); }
  });
}

function choosePlace(place) {
  $('#placeQuery').value = '';
  setLocation({ lat: place.latitude, lng: place.longitude, label: shortPlace(place.label) });
}

function useMyLocation(button) {
  if (!navigator.geolocation) { toast('Location is not available in this browser. Search a place instead.', 'error'); return; }
  withLoading(button, () => new Promise((resolve) => {
    navigator.geolocation.getCurrentPosition(async (pos) => {
      const lat = pos.coords.latitude, lng = pos.coords.longitude;
      setLocation({ lat, lng, label: 'your location' });
      resolve();
      try { const place = await api(`/geocode/reverse?lat=${lat}&lng=${lng}`); setLocation({ lat, lng, label: shortPlace(place.label) }, false); }
      catch { /* keep "your location" */ }
    }, () => {
      toast('Could not get your location. Allow location access, or search a place instead.', 'error');
      resolve();
    }, { timeout: 15000, maximumAge: 60000 });
  }));
}

// ---------- customer: food ----------

let nearbyItems = [];
/** The results as shown (after filters and sorting); "For you" leaves out the first of them. */
let shownItems = [];
const FOR_YOU_SKIP_TOP = 2;
let sortBy = 'distance';
let freeOnly = false;
let searchSeq = 0;

const SORTERS = {
  distance: (a, b) => a.distanceKm - b.distanceKm,
  price: (a, b) => a.discountedPrice - b.discountedPrice,
  discount: (a, b) => b.discountPercent - a.discountPercent,
  deadline: (a, b) => new Date(a.expiresAt) - new Date(b.expiresAt),
};

function skeletonCards(n = 6) {
  return Array.from({ length: n }, () => `
    <div class="card skeleton-card" aria-hidden="true">
      <div class="photo-frame"><div class="skeleton"></div></div>
      <div class="food-body"><div class="skeleton skeleton-line" style="width:45%"></div>
        <div class="skeleton skeleton-line" style="width:80%;height:18px"></div>
        <div class="skeleton skeleton-line" style="width:60%"></div>
        <div class="skeleton skeleton-line" style="width:35%;height:22px;margin-top:18px"></div></div>
    </div>`).join('');
}

/** quiet: refresh live availability in place, without the loading skeleton (used for Rescue Drops). */
async function search({ quiet = false } = {}) {
  const seq = ++searchSeq;
  updateWhere();
  const box = $('#nearby');
  if (!quiet) {
    box.setAttribute('aria-busy', 'true');
    box.innerHTML = skeletonCards();
    $('#resultCount').textContent = `Looking for food near ${location_.label}…`;
    $('#heroCount').textContent = 'Looking…';
  }
  const params = new URLSearchParams({ lat: location_.lat, lng: location_.lng, radiusKm });
  try {
    // Signed-in customers also get their Rescue Radar (picks and favourites) for the same place.
    const [items, personal] = await Promise.all([
      api('/food-items/nearby?' + params),
      me?.role === 'CONSUMER' ? api('/me/radar?' + params).catch(() => null) : null,
    ]);
    if (seq !== searchSeq) return; // a newer search started meanwhile
    nearbyItems = items;
    if (personal) applyRadar(personal);
    renderNearby();
    drawShopMap();
  } catch (e) {
    if (seq !== searchSeq || quiet) return;
    nearbyItems = [];
    box.innerHTML = `<div class="empty" style="grid-column:1/-1"><div class="empty-icon">${icon('alert')}</div>
      <h3>We couldn't load food right now</h3><p>${esc(e.message)}</p>
      <button class="btn btn-primary" type="button" data-retry-search>${icon('refresh')}<span>Try again</span></button></div>`;
    $('#resultCount').textContent = 'Search failed';
    $('#heroCount').textContent = '—';
  } finally {
    if (seq === searchSeq) box.setAttribute('aria-busy', 'false');
  }
}

function renderNearby() {
  const box = $('#nearby');
  const total = nearbyItems.length;
  const freeCount = nearbyItems.filter((i) => i.payItForwardQuantity > 0).length;
  // Live Rescue Drops first: they end soonest.
  const list = nearbyItems.filter((i) => (!freeOnly || i.payItForwardQuantity > 0) && (!favOnly || isFavourite(i)))
    .sort((a, b) => (b.rescueDrop - a.rescueDrop) || SORTERS[sortBy](a, b));
  shownItems = list;
  renderDropStrip();
  renderForYou();
  $('#favOnly').hidden = me?.role !== 'CONSUMER' || (!favStores.size && !favProducts.size && !favOnly);

  $('#heroCount').textContent = total ? plural(total, 'find', 'finds') : 'Nothing yet';
  $('#resultCount').textContent = total
    ? `${plural(list.length, 'find', 'finds')}${freeOnly ? ' with free units' : ''}${favOnly ? ' from your favourites' : ''} within ${radiusKm} km of ${location_.label}`
    : `No food within ${radiusKm} km of ${location_.label}`;

  if (!total) {
    const bigger = RADII.find((r) => r > radiusKm);
    box.innerHTML = `<div class="empty" style="grid-column:1/-1"><div class="empty-icon">${icon('search')}</div>
      <h3>Nothing available nearby right now</h3>
      <p>Shops usually add surplus food later in the day. Try a bigger distance or another place.</p>
      <div class="empty-actions">
        ${bigger ? `<button class="btn btn-primary" type="button" data-radius="${bigger}">Search within ${bigger} km</button>` : ''}
        <button class="btn" type="button" data-focus-search>Choose another place</button>
      </div>
      <p class="muted small">Or <button class="link-btn" type="button" data-open-inbox="settings">turn on Rescue Drop alerts</button> to hear when a shop nearby launches one.</p></div>`;
    return;
  }
  if (!list.length && favOnly) {
    box.innerHTML = `<div class="empty" style="grid-column:1/-1"><div class="empty-icon">${icon('star')}</div>
      <h3>None of your favourites nearby right now</h3><p>Turn on favourite alerts to hear when they are on offer.</p>
      <div class="empty-actions"><button class="btn" type="button" data-show-all>Show all food</button>
      <button class="btn" type="button" data-open-inbox="settings">Alert settings</button></div></div>`;
    return;
  }
  if (!list.length) {
    box.innerHTML = `<div class="empty" style="grid-column:1/-1"><div class="empty-icon">${icon('heart')}</div>
      <h3>No free items in this area</h3><p>${plural(freeCount, 'item has', 'items have')} free units right now. Try a bigger distance, or show all food.</p>
      <button class="btn" type="button" data-show-all>Show all food</button></div>`;
    return;
  }
  const canClaim = me?.freeClaimEligible;
  box.innerHTML = list.map((i, idx) => {
    const mins = minutesUntil(i.expiresAt);
    const urgent = mins <= 60;
    const timeFact = i.rescueDrop || urgent ? `Ends <span data-countdown="${i.expiresAt}">${untilText(i.expiresAt)}</span>`
      : i.pickupStartsAt ? `Pick up ${fmtWindow(i.pickupStartsAt, i.expiresAt)}` : `Until ${fmtTime(i.expiresAt)}`;
    return `
    <article class="card food-card${i.rescueDrop ? ' is-drop' : ''}" id="food-${i.id}" style="--i:${Math.min(idx, 10)}" aria-labelledby="food-title-${i.id}">
      <div class="photo-frame">
        ${photoHtml(i)}
        ${i.discountPercent > 0 ? `<span class="save-badge">−${i.discountPercent}%</span>` : ''}
        ${i.payItForwardQuantity ? `<span class="free-badge">${icon('heart')}${i.payItForwardQuantity} free</span>` : ''}
        ${i.rescueDrop ? `<span class="drop-badge">${icon('bolt')}Rescue Drop</span>` : ''}
      </div>
      <div class="food-body">
        <div class="food-store">${icon('store')}<strong>${esc(i.storeName)}</strong><span class="sep" aria-hidden="true">·</span><span class="nowrap">${distanceText(i.distanceKm)}</span>${followButton(i)}</div>
        ${i.kind === 'SURPRISE_BAG' ? `<span class="kind-label">${icon('box')}Surprise bag</span>` : ''}
        <div class="food-title"><h3 id="food-title-${i.id}">${esc(i.name)}</h3>${starButton(i)}</div>
        ${i.description ? `<p class="food-desc">${esc(i.description)}</p>` : ''}
        ${i.tags?.length ? `<p class="food-tags"><span class="visually-hidden">Food type, as stated by the shop: </span>${i.tags.map((t) => esc(TAG_LABEL[t] ?? t)).join(' · ')}</p>` : ''}
        <div class="food-facts">
          <span class="fact${urgent ? ' is-urgent' : ''}">${icon('clock')}${timeFact}</span>
          <span class="fact">${icon('box')}${i.quantityAvailable ? `${i.quantityAvailable} left` : 'Sold out'}</span>
          ${i.unitWeightGrams ? `<span class="fact" title="Approximate weight, as stated by the shop">≈ ${kgText(i.unitWeightGrams / 1000)}</span>` : ''}
        </div>
        <div class="food-foot">
          <div class="price">
            <span class="price-now">${money(i.discountedPrice)}</span>
            <span class="price-was"><span class="visually-hidden">instead of </span>${money(i.originalPrice)}</span>
          </div>
          <div class="food-actions">
            ${i.payItForwardQuantity && canClaim ? `<button class="btn btn-sm" type="button" data-claim="${i.id}" aria-label="Claim ${esc(i.name)} for free">${icon('heart')}<span>Claim free</span></button>` : ''}
            ${i.quantityAvailable
              ? `<button class="btn btn-primary btn-sm" type="button" data-reserve="${i.id}" aria-label="Reserve ${esc(i.name)}"><span>Reserve</span></button>`
              : (canClaim && i.payItForwardQuantity ? '' : '<span class="sold-out">Sold out</span>')}
          </div>
        </div>
      </div>
    </article>`;
  }).join('');
  settleImages(box);
}

/** "2 Rescue Drops live nearby: Bread · Soup" above the results. */
function renderDropStrip() {
  const drops = nearbyItems.filter((i) => i.rescueDrop && (i.quantityAvailable || i.payItForwardQuantity));
  $('#dropStrip').innerHTML = drops.length
    ? `<strong>${icon('bolt')}${plural(drops.length, 'Rescue Drop', 'Rescue Drops')} live nearby</strong>`
      + drops.slice(0, 4).map((d) => `<button class="link-btn" type="button" data-scroll-to="${d.id}">${esc(d.name)} · ${esc(d.storeName)}</button>`).join('<span aria-hidden="true">·</span>')
    : '';
}

function drawShopMap() {
  if (!shopMap) return;
  const here = [location_.lat, location_.lng];
  youMarker?.remove(); radiusCircle?.remove(); storeLayer.clearLayers();
  youMarker = L.marker(here, { icon: L.divIcon({ className: '', html: '<div class="you-dot"></div>', iconSize: [18, 18] }), keyboard: false })
    .addTo(shopMap).bindTooltip(location_.label === 'your location' ? 'You' : location_.label);
  radiusCircle = L.circle(here, { radius: radiusKm * 1000, color: '#1f6a4e', weight: 1.5, fillColor: '#2f7f5f', fillOpacity: .06 }).addTo(shopMap);
  const byStore = new Map();
  for (const i of nearbyItems) {
    if (!byStore.has(i.storeId)) byStore.set(i.storeId, { name: i.storeName, address: i.storeAddress, lat: i.storeLatitude, lng: i.storeLongitude, items: [] });
    byStore.get(i.storeId).items.push(i);
  }
  for (const s of byStore.values()) {
    if (s.lat == null) continue;
    const pin = L.divIcon({ className: '', html: `<div class="pin"><span>${s.items.length}</span></div>`, iconSize: [34, 34], iconAnchor: [17, 40], popupAnchor: [0, -38] });
    L.marker([s.lat, s.lng], { icon: pin, title: s.name, alt: `${s.name}, ${plural(s.items.length, 'item', 'items')}` }).addTo(storeLayer).bindPopup(
      `<div class="map-popup"><strong>${esc(s.name)}</strong><span class="muted">${esc(s.address)}</span><ul>${s.items.map((i) =>
        `<li><span>${esc(i.name)}</span><b>${money(i.discountedPrice)}</b></li>`).join('')}</ul>
        <button type="button" data-scroll-to="${s.items[0].id}">See ${s.items.length === 1 ? 'it' : 'them'} in the list</button></div>`);
  }
  $('#mapStoreCount').textContent = byStore.size ? plural(byStore.size, 'shop', 'shops') : '';
  shopMap.invalidateSize();
  shopMap.fitBounds(radiusCircle.getBounds(), { padding: [10, 10] });
}

function scrollToFood(id) {
  const card = document.getElementById('food-' + id);
  if (!card) return;
  shopMap?.closePopup();
  card.scrollIntoView({ behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth', block: 'center' });
  card.classList.add('is-highlighted');
  setTimeout(() => card.classList.remove('is-highlighted'), 1800);
  $('[data-reserve], [data-claim]', card)?.focus({ preventScroll: true });
}

function toggleMap() {
  const card = $('#mapCard');
  const show = card.classList.toggle('is-collapsed') === false;
  $('#mapToggle').setAttribute('aria-expanded', String(show));
  $('#mapToggle span').textContent = show ? 'Hide map' : 'Show map';
  if (show) { shopMap?.invalidateSize(); if (radiusCircle) shopMap.fitBounds(radiusCircle.getBounds(), { padding: [10, 10] }); }
}

/** Statuses where the box asks the customer to get verified. They can stop it asking ("Don't ask again"). */
const ASKS_FOR_VERIFICATION = ['NOT_REQUESTED', 'REJECTED'];

function renderEligibility() {
  const box = $('#eligibilityBox');
  const status = me.eligibilityStatus;
  $('#freeOnly').hidden = !me.freeClaimEligible;
  $('#freeOfferBlock').hidden = !ASKS_FOR_VERIFICATION.includes(status);
  // Wait for the profile before asking, so a dismissed box doesn't flash up and vanish.
  if (ASKS_FOR_VERIFICATION.includes(status) && !radarProfile?.freeItemsOffer) { box.hidden = true; return; }
  const text = {
    NOT_REQUESTED: ['Going through a hard time?', 'Verified people can claim one donated item for free each day. Only the outcome of the check is stored, never the reason.', 'Ask to be verified'],
    REJECTED: ['Free items', 'Your last verification request was not approved. You can ask again.', 'Ask again'],
    PENDING: ['Free items', 'Your verification request is waiting for review. We will show free items here once it is approved.', null],
    APPROVED: ['You can claim free items', 'Look for the “free” label and claim one item per day. Use the Free items filter to see them first.', null],
  }[status];
  const badge = status === 'APPROVED' ? '<span class="badge">Verified</span>' : status === 'PENDING' ? '<span class="badge badge-grey">Pending review</span>' : '';
  box.className = 'notice' + (status === 'APPROVED' ? ' is-verified' : '');
  box.innerHTML = `<span class="notice-icon">${icon(status === 'APPROVED' ? 'shield' : 'heart')}</span>
    <div class="notice-text"><strong>${text[0]} ${badge}</strong><p>${text[1]}</p></div>
    ${text[2] ? `<div class="notice-actions">
      <button class="btn btn-sm" type="button" id="requestEligibility">${text[2]}</button>
      <button class="link-btn" type="button" id="hideEligibility">Don't ask again</button></div>` : ''}`;
  box.hidden = false;
}

async function requestEligibility(button) {
  await withLoading(button, async () => {
    try {
      me = await api('/users/me/eligibility-request', { method: 'POST' });
      toast('Request sent. An administrator will review it.');
      renderEligibility();
    } catch (e) { fail(e); }
  });
}

/** Turns the offer to get verified off ("Don't ask again") or back on. */
async function setFreeItemsOffer(show) {
  radarProfile = await api('/me/free-items-offer', { method: 'PUT', body: { show } });
  renderEligibility();
}

async function hideEligibility() {
  try {
    await setFreeItemsOffer(false);
    toast('We won\'t ask again. You can turn it back on in Alerts → Settings.');
  } catch (e) { fail(e); }
}

// ---------- customer: Rescue Drop alerts ----------

let inbox = { unread: 0, items: [] };
let newestAlertId = null;
let inboxTimer = null;
let alertPlace = null;

function startInbox() {
  newestAlertId = null;
  pollInbox();
  clearInterval(inboxTimer);
  inboxTimer = setInterval(() => { if (!document.hidden) pollInbox(); }, 60000);
}

function stopInbox() {
  clearInterval(inboxTimer);
  inboxTimer = null;
  inbox = { unread: 0, items: [] };
}

/** Checks the inbox; a new alert is announced once (as a toast, or a system notification when the tab is in the background). */
async function pollInbox() {
  if (me?.role !== 'CONSUMER') return;
  try { inbox = await api('/me/notifications'); } catch { return; }
  const dot = $('#inboxDot');
  dot.hidden = !inbox.unread;
  dot.textContent = inbox.unread > 9 ? '9+' : inbox.unread;
  $('#inboxBtn').setAttribute('aria-label', inbox.unread ? `Alerts, ${inbox.unread} new` : 'Alerts');
  const newest = inbox.items[0];
  if (newest && newestAlertId !== null && newest.id > newestAlertId && !newest.read) announce(newest);
  if (newest) newestAlertId = Math.max(newestAlertId ?? 0, newest.id);
  else if (newestAlertId === null) newestAlertId = 0;
  if ($('#inboxDialog').open) renderInbox();
}

function announce(n) {
  // With push on, the service worker shows background alerts; this covers the rest.
  if (document.hidden && !pushOn && 'Notification' in window && Notification.permission === 'granted') {
    const note = new Notification(n.title, { body: n.body, tag: 'alert-' + n.id });
    note.onclick = () => { window.focus(); openAlert(n); note.close(); };
  } else if (!document.hidden) {
    toast(`${n.title}: ${n.body}`);
  }
  if (currentView === 'shop') search({ quiet: true });
}

const KIND_ICON = { DROP: 'bolt', FAVOURITE: 'star', DIGEST: 'spark', TEST: 'bell', REMINDER: 'clock' };

function renderInbox() {
  $('#inboxList').innerHTML = inbox.items.length ? inbox.items.map((n) => {
    const action = n.kind === 'REMINDER'
      ? `<button class="btn btn-sm" type="button" data-open-alert="${n.id}"><span>My orders</span></button>`
      : n.foodItemId == null
      ? `<button class="btn btn-sm" type="button" data-open-alert="${n.id}"><span>Show me</span></button>`
      : n.live ? `<button class="btn btn-sm btn-drop-solid" type="button" data-open-alert="${n.id}"><span>See it</span></button>`
        : '<span class="badge badge-grey plain">Ended</span>';
    return `<div class="inbox-item${n.read ? '' : ' is-unread'} is-${(n.kind || 'DROP').toLowerCase()}">${icon(KIND_ICON[n.kind] || 'bolt')}
      <div><strong>${esc(n.title)}</strong><p>${esc(n.body)}</p><time datetime="${n.createdAt}">${fmtTime(n.createdAt)}</time></div>
      ${action}</div>`;
  }).join('')
    : `<div class="inbox-empty"><p>No alerts yet.</p><button class="btn btn-sm" type="button" data-inbox-tab="settings">Choose what to hear about</button></div>`;
}

function setInboxTab(tab) {
  for (const t of $$('#inboxDialog [role=tab]')) {
    const on = t.dataset.inboxTab === tab;
    t.setAttribute('aria-selected', String(on));
    t.tabIndex = on ? 0 : -1;
  }
  $('#inboxPanelList').hidden = tab !== 'list';
  $('#alertsForm').hidden = tab !== 'settings';
  if (tab === 'settings') loadSettings();
}

async function openInbox(tab = 'list') {
  renderInbox();
  setInboxTab(tab);
  openDialog($('#inboxDialog'));
  if (inbox.unread) {
    try {
      await api('/me/notifications/read', { method: 'POST' });
      inbox.unread = 0;
      $('#inboxDot').hidden = true;
      $('#inboxBtn').setAttribute('aria-label', 'Alerts');
    } catch { /* stays unread; harmless */ }
  }
}

/** Fills the Settings tab from the server: drop alert place, Radar profile and favourites. */
async function loadSettings() {
  renderPushStatus();
  try {
    const [pref, profile, favs] = await Promise.all([api('/me/drop-alerts'), api('/me/radar-profile'), api('/me/favourites')]);
    radarProfile = profile;
    alertPlace = pref.latitude != null ? { lat: pref.latitude, lng: pref.longitude, label: 'your saved place' } : { ...location_ };
    if (pref.latitude != null && Math.abs(pref.latitude - location_.lat) < 1e-6 && Math.abs(pref.longitude - location_.lng) < 1e-6) alertPlace.label = location_.label;
    $('#alertsOn').checked = pref.enabled;
    $('#alertsRadius').value = String(pref.radiusKm);
    $('#alertsMax').value = String(pref.maxPerDay);
    $('#alertsPlace').textContent = alertPlace.label;
    $('#notifyFavourites').checked = profile.notifyFavourites;
    $('#digestOn').checked = !!profile.digestTime;
    if (profile.digestTime) $('#digestTime').value = hhmm(profile.digestTime);
    $('#quietOn').checked = !!profile.quietStart;
    if (profile.quietStart) { $('#quietStart').value = hhmm(profile.quietStart); $('#quietEnd').value = hhmm(profile.quietEnd); }
    $('#personalised').checked = profile.personalised;
    $('#pickupReminders').checked = profile.pickupReminders;
    $('#freeItemsOffer').checked = profile.freeItemsOffer;
    tagChips($('#interestChips'), profile.interests, 'interest');
    $('#throttledNote').hidden = !profile.throttled;
    renderFavList(favs);
  } catch (e) { fail(e); }
}

function renderFavList(favs) {
  const rows = [
    ...favs.products.map((p) => `<li>${icon('star')}<span><strong>${esc(p.name)}</strong> · ${esc(p.storeName)}</span><button class="link-btn" type="button" data-unfav="product" data-fav-id="${p.templateId}">Remove</button></li>`),
    ...favs.stores.map((st) => `<li>${icon('store')}<span><strong>${esc(st.name)}</strong> · shop you follow</span><button class="link-btn" type="button" data-unfav="store" data-fav-id="${st.id}">Unfollow</button></li>`),
  ];
  $('#favList').innerHTML = rows.length ? `<ul>${rows.join('')}</ul>`
    : '<p class="field-hint">No favourites yet. Tap ☆ on a product or “Follow” on a shop while browsing.</p>';
}

async function saveAlerts(event) {
  event.preventDefault();
  const quiet = $('#quietOn').checked, digest = $('#digestOn').checked;
  if (quiet && (!$('#quietStart').value || !$('#quietEnd').value)) return toast('Choose both times for quiet hours.', 'error');
  if (digest && !$('#digestTime').value) return toast('Choose a time for the daily summary.', 'error');
  const button = $('button[type=submit]', event.currentTarget);
  await withLoading(button, async () => {
    try {
      // The daily summary uses the same place as drop alerts, so the place is saved either way.
      await Promise.all([
        api('/me/drop-alerts', { method: 'PUT', body: {
          enabled: $('#alertsOn').checked, latitude: alertPlace.lat, longitude: alertPlace.lng,
          radiusKm: Number($('#alertsRadius').value), maxPerDay: Number($('#alertsMax').value),
        } }),
        api('/me/radar-profile', { method: 'PUT', body: {
          personalised: $('#personalised').checked, interests: checkedTags($('#interestChips')),
          notifyFavourites: $('#notifyFavourites').checked,
          quietStart: quiet ? $('#quietStart').value : null, quietEnd: quiet ? $('#quietEnd').value : null,
          digestTime: digest ? $('#digestTime').value : null, timeZone: BROWSER_TZ || 'UTC',
          pickupReminders: $('#pickupReminders').checked, freeItemsOffer: $('#freeItemsOffer').checked,
        } }).then((p) => { radarProfile = p; renderEligibility(); }),
      ]);
      toast('Settings saved.');
      if (!pushOn && pushSupported() && Notification.permission === 'default'
          && ($('#alertsOn').checked || $('#notifyFavourites').checked || digest)) {
        $('#pushStatus').textContent = 'Saved. Turn on notifications to get these alerts even when Food Rescue is closed.';
      }
      if (currentView === 'shop') search({ quiet: true });
    } catch (e) { fail(e); }
  });
}

/** Shows the offer of an alert: search around the shop, then highlight its card. */
async function openAlert(n) {
  if (!n) return;
  $('#inboxDialog').close();
  if (n.kind === 'REMINDER') { showView('orders', { focus: true }); return; }
  showView('shop');
  if (n.foodItemId == null) { search(); return; } // a daily summary: show what's around now
  if (n.storeLatitude != null) {
    location_ = { lat: n.storeLatitude, lng: n.storeLongitude, label: n.storeName || 'the shop' };
    save(LOCATION_KEY, location_);
    await search();
  }
  if (document.getElementById('food-' + n.foodItemId)) scrollToFood(n.foodItemId);
  else toast('This offer has just ended.', 'error');
}

/** Opened from a push notification (?alert=12): show that alert once the inbox is loaded. */
async function openLinkedAlert(url = location.href) {
  const id = Number(new URL(url, location.origin).searchParams.get('alert'));
  if (!id) return;
  history.replaceState(null, '', '/');
  await pollInbox();
  openAlert(inbox.items.find((n) => n.id === id));
}

// ---------- customer: Rescue Radar (favourites and picks) ----------

let radar = null;
let favStores = new Set();
let favProducts = new Set();
let favOnly = false;
let radarProfile = null;
let favHintShown = false;

function applyRadar(r) {
  radar = r;
  favStores = new Set(r.favouriteStoreIds);
  favProducts = new Set(r.favouriteTemplateIds);
}

const isFavourite = (i) => favStores.has(i.storeId) || (i.templateId != null && favProducts.has(i.templateId));

function followButton(i) {
  if (me?.role !== 'CONSUMER') return '';
  const on = favStores.has(i.storeId);
  return `<button class="follow-btn" type="button" data-fav="store" data-fav-id="${i.storeId}" aria-pressed="${on}" aria-label="${on ? 'Unfollow' : 'Follow'} ${esc(i.storeName)}">${on ? 'Following' : 'Follow'}</button>`;
}

function starButton(i) {
  if (me?.role !== 'CONSUMER' || i.templateId == null) return '';
  const on = favProducts.has(i.templateId);
  return `<button class="star-btn" type="button" data-fav="product" data-fav-id="${i.templateId}" aria-pressed="${on}" aria-label="${on ? 'Remove' : 'Save'} ${esc(i.name)} ${on ? 'from' : 'as a'} favourite${on ? 's' : ''}">${icon('star')}</button>`;
}

/** "For you": the Radar's picks among the current results, each with its reason. */
function renderForYou() {
  const section = $('#forYou');
  // Only picks that are in the list but not already at the top of it: those are easy to see anyway.
  const byId = new Map(shownItems.slice(FOR_YOU_SKIP_TOP).map((i) => [i.id, i]));
  const picks = (radar?.personalised ? radar.picks : []).filter((p) => byId.has(p.itemId));
  section.hidden = !picks.length;
  if (!picks.length) return;
  $('#forYouList').innerHTML = picks.map((p) => {
    const i = byId.get(p.itemId);
    return `<button class="for-you-card" type="button" data-scroll-to="${i.id}" aria-label="${esc(i.name)} at ${esc(i.storeName)}, ${money(i.discountedPrice)}. ${esc(p.reasons.join('. '))}">
      <div class="photo-frame">${photoHtml(i, { sizes: '64px', tag: false, emptyLabel: '' })}</div>
      <span class="for-you-text"><span class="for-you-reason">${esc(p.reasons[0])}</span><strong>${esc(i.name)}</strong>
        <span class="for-you-meta"><span>${esc(i.storeName)}</span><b>${money(i.discountedPrice)}</b></span></span>
    </button>`;
  }).join('');
  settleImages(section);
}

async function toggleFavourite(button) {
  const kind = button.dataset.fav, id = Number(button.dataset.favId);
  const set = kind === 'store' ? favStores : favProducts;
  const on = !set.has(id);
  if (on) set.add(id); else set.delete(id);
  syncFavButtons();
  try {
    await api(`/me/favourites/${kind === 'store' ? 'stores' : 'products'}/${id}`, { method: on ? 'PUT' : 'DELETE' });
    if (on && !radarProfile?.notifyFavourites && !favHintShown) {
      favHintShown = true;
      toast(`${kind === 'store' ? 'Following.' : 'Saved.'} Turn on favourite alerts in Alerts → Settings to hear when it’s available.`);
    } else {
      toast(on ? (kind === 'store' ? 'Following this shop.' : 'Saved to favourites.') : (kind === 'store' ? 'Unfollowed.' : 'Removed from favourites.'));
    }
    refreshRadar();
  } catch (e) {
    if (on) set.delete(id); else set.add(id);
    syncFavButtons();
    fail(e);
  }
}

function syncFavButtons() {
  for (const b of $$('#nearby [data-fav]')) {
    const id = Number(b.dataset.favId);
    const on = b.dataset.fav === 'store' ? favStores.has(id) : favProducts.has(id);
    b.setAttribute('aria-pressed', String(on));
    if (b.dataset.fav === 'store') {
      b.textContent = on ? 'Following' : 'Follow';
      b.setAttribute('aria-label', `${on ? 'Unfollow' : 'Follow'} ${b.closest('.food-store').querySelector('strong').textContent}`);
    } else {
      const name = b.closest('.food-title').querySelector('h3').textContent;
      b.setAttribute('aria-label', on ? `Remove ${name} from favourites` : `Save ${name} as a favourite`);
    }
  }
  $('#favOnly').hidden = !favStores.size && !favProducts.size && !favOnly;
}

/** Re-ranks the picks after a change, without redrawing the results. */
async function refreshRadar() {
  const params = new URLSearchParams({ lat: location_.lat, lng: location_.lng, radiusKm });
  try { applyRadar(await api('/me/radar?' + params)); renderForYou(); syncFavButtons(); } catch { /* picks stay as they were */ }
}

async function unfavourite(button) {
  const kind = button.dataset.unfav, id = Number(button.dataset.favId);
  try {
    await api(`/me/favourites/${kind === 'store' ? 'stores' : 'products'}/${id}`, { method: 'DELETE' });
    (kind === 'store' ? favStores : favProducts).delete(id);
    button.closest('li').remove();
    syncFavButtons();
    refreshRadar();
  } catch (e) { fail(e); }
}

// ---------- customer: Web Push on this device ----------

let swRegistration = null;
let pushOn = false;
const pushSupported = () => 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
const isAppleMobile = () => /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);

function base64UrlBytes(s) {
  const b = atob(s.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (s.length % 4)) % 4));
  return Uint8Array.from(b, (c) => c.charCodeAt(0));
}

async function serviceWorker() {
  if (!('serviceWorker' in navigator)) return null;
  if (!swRegistration) {
    try { swRegistration = await navigator.serviceWorker.register('/sw.js'); } catch { return null; }
  }
  return swRegistration;
}

async function currentPushSubscription() {
  const reg = await serviceWorker();
  return reg?.pushManager ? reg.pushManager.getSubscription() : null;
}

/** Subscribes this browser (again, if the server key changed) and tells the server. */
async function subscribePush() {
  const reg = await serviceWorker();
  if (!reg) throw new Error('the browser refused the notification helper');
  await navigator.serviceWorker.ready;
  const key = base64UrlBytes((await api('/push/public-key')).publicKey);
  let sub = await reg.pushManager.getSubscription();
  const current = sub?.options?.applicationServerKey ? new Uint8Array(sub.options.applicationServerKey) : null;
  if (sub && (!current || current.length !== key.length || current.some((b, i) => b !== key[i]))) {
    await sub.unsubscribe();
    sub = null;
  }
  if (!sub) sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key });
  await api('/me/push-subscriptions', { method: 'POST', body: sub.toJSON() });
  pushOn = true;
}

async function enablePush(button) {
  if (!pushSupported()) {
    $('#pushStatus').textContent = isAppleMobile()
      ? 'On iPhone and iPad: tap Share → “Add to Home Screen”, open Food Rescue from there, then turn notifications on.'
      : 'This browser can’t show notifications. Alerts will still appear here while Food Rescue is open.';
    return;
  }
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') {
    $('#pushStatus').textContent = 'Notifications are blocked for this site. Allow them in your browser’s site settings, then try again.';
    return;
  }
  await withLoading(button, async () => {
    try {
      await subscribePush();
      toast('Notifications are on for this device.');
    } catch (e) {
      $('#pushStatus').textContent = `Couldn’t turn notifications on: ${e.message}. Alerts still appear here while the app is open.`;
      return;
    }
    renderPushStatus();
  });
}

async function disablePush() {
  await releasePush();
  toast('Notifications are off on this device.');
  renderPushStatus();
}

/** Removes this browser's subscription, on the server and in the browser. */
async function releasePush() {
  try {
    const sub = await currentPushSubscription();
    if (!sub) return;
    await api('/me/push-subscriptions', { method: 'DELETE', body: { endpoint: sub.endpoint } }).catch(() => {});
    await sub.unsubscribe();
  } catch { /* nothing to release */ }
  pushOn = false;
}

async function testPush(button) {
  await withLoading(button, async () => {
    try {
      const { sent } = await api('/me/push-subscriptions/test', { method: 'POST' });
      toast(sent ? 'Test sent. It should arrive within a few seconds.' : 'The test could not be delivered. Turn notifications off and on again.', sent ? 'ok' : 'error');
    } catch (e) { fail(e); }
  });
}

async function renderPushStatus() {
  const status = $('#pushStatus');
  let subscribed = false;
  if (pushSupported() && Notification.permission === 'granted') {
    try { subscribed = !!(await currentPushSubscription()); } catch { subscribed = false; }
  }
  pushOn = subscribed;
  $('#pushEnable').hidden = subscribed || (pushSupported() && Notification.permission === 'denied');
  $('#pushTest').hidden = !subscribed;
  $('#pushDisable').hidden = !subscribed;
  status.textContent = subscribed ? 'Notifications are on for this device, even when Food Rescue is closed.'
    : !pushSupported() ? (isAppleMobile() ? 'On iPhone and iPad, add Food Rescue to your Home Screen first (Share → Add to Home Screen).'
      : 'This browser can’t show notifications. Alerts appear here while Food Rescue is open.')
      : Notification.permission === 'denied' ? 'Notifications are blocked for this site in your browser settings.'
        : 'Get alerts on this device even when Food Rescue is closed.';
}

/** On every visit: keep the server's copy of this browser's subscription current. */
async function syncPush() {
  if (!pushSupported() || Notification.permission !== 'granted') return;
  try { if (await currentPushSubscription()) await subscribePush(); } catch { pushOn = false; }
}

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.addEventListener('message', (e) => {
    if (me?.role !== 'CONSUMER') return;
    if (e.data?.type === 'alert-received') pollInbox();
    else if (e.data?.type === 'open-url') openLinkedAlert(e.data.url);
    else if (e.data?.type === 'resubscribe') syncPush();
  });
}

// ---------- customer: reserving ----------

let reserving = null; // { item, qty, mode: 'reserve' | 'claim' }

function openReserve(itemId, mode) {
  const item = nearbyItems.find((i) => i.id === itemId);
  if (!item) return;
  reserving = { item, qty: 1, mode };
  renderReserveForm();
  openDialog($('#reserveDialog'));
}

function maxQty(item) { return Math.max(1, Math.min(maxPerReservation, item.quantityAvailable)); }

function renderReserveForm() {
  const { item, qty, mode } = reserving;
  const claim = mode === 'claim';
  const total = claim ? 0 : item.discountedPrice * qty;
  const saved = claim ? item.originalPrice : (item.originalPrice - item.discountedPrice) * qty;
  $('#reserveDialog').innerHTML = `
    <form id="reserveForm" novalidate>
      <div class="modal-head">
        <div><h2 id="reserveTitle">${claim ? 'Claim a free item' : 'Reserve food'}</h2><p>${claim ? 'Donated by the shop and the community.' : 'You pay at the shop when you collect.'}</p></div>
        <button type="button" class="btn btn-ghost btn-icon close-btn" data-close aria-label="Close">${icon('x')}</button>
      </div>
      <div class="modal-body">
        <div class="reserve-item">
          <div class="photo-frame">${photoHtml(item, { sizes: '96px', tag: false })}</div>
          <div><h3>${esc(item.name)}</h3><p>${esc(item.storeName)} · ${distanceText(item.distanceKm)}</p>
            ${item.photoUrl ? '' : (illustrativeKey(item.name, item.description) ? '<p class="small">Illustrative photo</p>' : '')}</div>
        </div>
        ${claim ? '' : `
        <div class="qty-row">
          <div><strong>Quantity</strong><span>${item.quantityAvailable} left · up to ${maxQty(item)} per order</span></div>
          <div class="stepper" role="group" aria-label="Quantity">
            <button type="button" data-step="-1" aria-label="One less" ${qty <= 1 ? 'disabled' : ''}>${icon('minus')}</button>
            <output aria-live="polite" aria-label="Quantity">${qty}</output>
            <button type="button" data-step="1" aria-label="One more" ${qty >= maxQty(item) ? 'disabled' : ''}>${icon('plus')}</button>
          </div>
        </div>`}
        <div class="total-row"><span>${claim ? 'To pay' : 'Total, paid at the shop'}<br><span class="small">You save ${money(saved)}</span></span><strong>${claim ? 'Free' : money(total)}</strong></div>
        <ul class="info-list">
          <li>${icon('pin')}<span>${esc(item.storeAddress)}</span></li>
          <li>${icon('clock')}<span>${item.pickupStartsAt
            ? `Pickup time: ${fmtWindow(item.pickupStartsAt, item.expiresAt)}. We hold it for you until the end of this window.`
            : `We hold it for you for a limited time. You'll see the exact pickup deadline after you ${claim ? 'claim' : 'reserve'}.`}</span></li>
          ${item.kind === 'SURPRISE_BAG' ? `<li>${icon('box')}<span>Surprise bag: the exact contents depend on what is left today.</span></li>` : ''}
          ${claim ? `<li>${icon('heart')}<span>Free items are limited to one per day, so there is enough for everyone.</span></li>` : ''}
        </ul>
        <div class="form-alert" id="reserveError" role="alert"></div>
      </div>
      <div class="modal-foot">
        <button type="button" class="btn" data-close>Cancel</button>
        <button type="submit" class="btn btn-primary" id="reserveSubmit" autofocus><span>${claim ? 'Claim for free' : `Reserve · ${money(total)}`}</span></button>
      </div>
    </form>`;
  settleImages($('#reserveDialog'));
}

function stepQty(delta) {
  const { item } = reserving;
  reserving.qty = Math.min(maxQty(item), Math.max(1, reserving.qty + delta));
  const focusedStep = document.activeElement?.dataset?.step;
  renderReserveForm();
  // Keep focus on the stepper (it was re-rendered), or on the submit button if that step is now disabled.
  const again = $(`#reserveDialog [data-step="${focusedStep}"]`);
  (again && !again.disabled ? again : $('#reserveSubmit')).focus();
}

async function submitReserve(event) {
  event.preventDefault();
  const { item, qty, mode } = reserving;
  const button = $('#reserveSubmit');
  await withLoading(button, async () => {
    try {
      const order = mode === 'claim'
        ? await api(`/food-items/${item.id}/free-claims`, { method: 'POST' })
        : await api(`/food-items/${item.id}/reservations`, { method: 'POST', body: { quantity: qty } });
      renderReserveSuccess(order);
      search();
      loadMyOrders();
    } catch (e) {
      setAlert('#reserveError', e.message);
      search(); // stock may have changed
    }
  });
}

function renderReserveSuccess(order) {
  const free = isFree(order.totalPrice);
  $('#reserveDialog').innerHTML = `
    <div class="success">
      <svg class="check-anim" viewBox="0 0 76 76" aria-hidden="true"><circle cx="38" cy="38" r="36"/><path d="M24 39l9 9 19-20"/></svg>
      <h2 id="reserveTitle" tabindex="-1">${free ? 'Claimed, it’s yours!' : 'Reserved, it’s yours!'}</h2>
      <p>Show this code at ${esc(order.storeName)} to collect your food.</p>
    </div>
    <div class="ticket">
      <div class="ticket-code">
        <small>Pickup code</small>
        <span class="code" aria-label="${spacedDigits(order.pickupCode)}">${esc(order.pickupCode)}</span>
        <button type="button" class="btn btn-sm" data-copy="${esc(order.pickupCode)}">${icon('copy')}<span>Copy code</span></button>
      </div>
      <div class="ticket-details">
        <div><span>Item</span><strong>${esc(order.foodItemName)} × ${order.quantity}</strong></div>
        <div><span>${free ? 'To pay' : 'To pay at the shop'}</span><strong>${free ? 'Free' : money(order.totalPrice)}</strong></div>
        <div><span>${order.pickupOpensAt ? 'Pickup time' : 'Collect by'}</span><strong>${order.pickupOpensAt ? fmtWindow(order.pickupOpensAt, order.pickupDeadline) : fmtTime(order.pickupDeadline)} <span class="muted">(${order.pickupOpensAt && minutesUntil(order.pickupOpensAt) > 0 ? 'opens ' + untilText(order.pickupOpensAt) : 'ends ' + untilText(order.pickupDeadline)})</span></strong></div>
        <div><span>Shop</span><strong>${esc(order.storeName)}</strong></div>
        <div class="span-2"><span>Address</span><strong>${esc(order.storeAddress)}</strong></div>
      </div>
    </div>
    <ol class="steps">
      <li>${order.pickupOpensAt ? `Go to the shop ${fmtWindow(order.pickupOpensAt, order.pickupDeadline)}.` : `Go to the shop before ${fmtTime(order.pickupDeadline)}.`} After that the food goes back on sale.</li>
      <li>Show the code at the counter.</li>
      <li>${free ? 'Take your free item. Nothing to pay.' : 'Pay at the shop and enjoy.'}</li>
    </ol>
    <div class="modal-foot">
      <button type="button" class="btn" data-goto-orders>See my orders</button>
      <button type="button" class="btn btn-primary" data-close autofocus>Done</button>
    </div>`;
  $('#reserveTitle').focus();
}

async function copyCode(button) {
  const code = button.dataset.copy;
  try {
    await navigator.clipboard.writeText(code);
  } catch {
    const tmp = Object.assign(document.createElement('textarea'), { value: code });
    document.body.append(tmp); tmp.select();
    try { document.execCommand('copy'); } catch { /* ignore */ }
    tmp.remove();
  }
  const label = $('span', button);
  if (label) {
    const before = label.textContent;
    label.textContent = 'Copied';
    setTimeout(() => { if (button.isConnected) label.textContent = before; }, 1800);
  }
  toast(`Code ${code} copied`);
}

// ---------- customer: orders ----------

let myOrders = [];
/** Past orders come in pages, so a long history doesn't make the page endless. */
const HISTORY_PAGE = 10;
let historyShown = HISTORY_PAGE;
const STATUS = {
  RESERVED: ['Reserved', ''],
  PICKED_UP: ['Collected', ''],
  CANCELLED: ['Cancelled', 'badge-grey'],
  EXPIRED: ['Expired', 'badge-red'],
};

async function loadMyOrders() {
  if (me?.role !== 'CONSUMER') return;
  try {
    myOrders = await api('/orders/mine');
    renderOrders();
  } catch (e) { fail(e); }
}

// ---------- customer: impact, streaks and achievements ----------

const SEEN_ACHIEVEMENTS_KEY = 'foodrescue.seenAchievements';

async function loadImpact() {
  if (me?.role !== 'CONSUMER') return;
  const body = $('#impactBody');
  body.setAttribute('aria-busy', 'true');
  try {
    renderImpact(await api('/me/impact'));
  } catch (e) {
    body.innerHTML = `<div class="empty"><h3>Your impact could not be loaded</h3><p>${esc(e.message)}</p>
      <button class="btn" type="button" onclick="loadImpact()">${icon('refresh')}<span>Try again</span></button></div>`;
  } finally { body.setAttribute('aria-busy', 'false'); }
}

const meter = (percent, label) => `<div class="meter" role="progressbar" aria-label="${esc(label)}" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${percent}"><span style="width:${percent}%"></span></div>`;

function streakText(s) {
  if (s.rescuedThisWeek) return 'This week already counts. Collect something next week to keep it going.';
  if (s.currentWeeks) return 'Collect something by Sunday to keep your streak.';
  return 'Collect rescued food this week to start a streak.';
}

function renderImpact(r) {
  // Achievements unlocked since this person last looked are marked as new.
  const seenKey = `${SEEN_ACHIEVEMENTS_KEY}.${me.id}`;
  const seen = new Set(restore(seenKey) || []);
  const firstVisit = restore(seenKey) === null;
  const unlocked = r.achievements.filter((a) => a.unlocked).map((a) => a.code);
  const fresh = firstVisit ? [] : unlocked.filter((c) => !seen.has(c));
  save(seenKey, unlocked);

  const kg = r.kgRescued ? `<small>${r.kgRescued.toLocaleString(undefined, { maximumFractionDigits: 1 })} kg</small>` : '';
  const tile = (label, value, accent = false) => `<div class="card stat${accent ? ' is-accent' : ''}"><span>${label}</span><strong>${value}</strong></div>`;
  const c = r.challenge;
  const achievements = r.achievements.map((a) => `
    <li class="achievement${a.unlocked ? ' is-unlocked' : ''}">
      <span class="achievement-icon" aria-hidden="true">${icon(a.unlocked ? 'star' : 'lock')}</span>
      <div>
        <h3>${esc(a.title)}${fresh.includes(a.code) ? ' <span class="badge badge-sun">New</span>' : ''}</h3>
        <p>${esc(a.description)}</p>
        ${a.unlocked ? `<p class="achievement-when">${icon('check')}Unlocked ${dayLabel(a.unlockedOn, { day: 'numeric', month: 'long', year: 'numeric' })}</p>`
          : `${meter(a.percent, a.title + ' progress')}<p class="achievement-when">${esc(a.progressText)}</p>`}
      </div>
    </li>`).join('');

  $('#impactBody').innerHTML = `
    ${r.rescues ? '' : `<div class="notice"><span class="notice-icon">${icon('leaf')}</span><div><strong>No rescues yet</strong>
      <p>Collect your first reservation and your savings, streak and achievements start here.</p></div>
      <button class="btn btn-primary btn-sm" type="button" data-view="shop">${icon('search')}<span>Find food</span></button></div>`}
    <div class="stats impact-stats">
      ${tile('Food rescued', `${r.units} items${kg}`, true)}
      ${tile('Money saved', money(r.moneySaved))}
      ${tile('Rescues', r.rescues)}
      ${tile('Shops supported', r.shops)}
    </div>
    ${r.unitsWithoutWeight ? `<p class="muted small impact-note">${plural(r.unitsWithoutWeight, 'item')} had no weight from the shop, so ${r.unitsWithoutWeight === 1 ? 'it is' : 'they are'} not in the kg figure.</p>` : ''}
    <div class="impact-grid">
      <article class="card impact-card streak-card">
        <span class="eyebrow">Rescue streak</span>
        <p class="streak-number"><strong>${r.streak.currentWeeks}</strong> ${r.streak.currentWeeks === 1 ? 'week' : 'weeks'} in a row</p>
        <p>${streakText(r.streak)}</p>
        <p class="muted small">Best so far: ${plural(r.streak.bestWeeks, 'week')}. Weeks run Monday to Sunday.</p>
      </article>
      <article class="card impact-card">
        <span class="eyebrow">Community challenge</span>
        <h2>${esc(c.title)}</h2>
        ${meter(c.percent, 'Community challenge progress')}
        <p><strong>${c.communityUnits} of ${c.targetUnits}</strong> rescued so far · ${c.daysLeft ? plural(c.daysLeft, 'day') + ' left' : 'last day'}</p>
        <p class="muted small">${c.yourUnits ? `You rescued ${plural(c.yourUnits, 'item')} of them this month.` : 'Collect something this month to add to it.'}</p>
      </article>
    </div>
    <div class="section-head"><div><h2>Achievements</h2><p>${unlocked.length} of ${r.achievements.length} unlocked</p></div></div>
    <ul class="achievements">${achievements}</ul>`;
  if (fresh.length) toast(fresh.length === 1 ? `New achievement: ${r.achievements.find((a) => a.code === fresh[0]).title}` : `${fresh.length} new achievements`);
}

function renderOrders() {
  const active = myOrders.filter((o) => o.status === 'RESERVED').sort((a, b) => new Date(a.pickupDeadline) - new Date(b.pickupDeadline));
  const past = myOrders.filter((o) => o.status !== 'RESERVED');
  $('#view-shop .hero').classList.toggle('is-compact', myOrders.length > 0);
  for (const dot of $$('[data-orders-dot]')) { dot.textContent = active.length; dot.hidden = !active.length; }

  $('#activeOrders').innerHTML = active.length ? active.map((o, idx) => {
    const free = isFree(o.totalPrice);
    return `
    <article class="card order-active" style="--i:${idx}" aria-label="Reservation for ${esc(o.foodItemName)}">
      <div class="order-main"><div class="order-info">
        <div><span class="badge">Ready to collect</span> ${free ? '<span class="badge badge-sun plain">Free</span>' : ''}</div>
        <h3>${esc(o.foodItemName)} × ${o.quantity}</h3>
        <p class="order-meta"><strong>${esc(o.storeName)}</strong> · ${esc(o.storeAddress)}</p>
        <p class="order-meta">${icon('clock', 'inline-icon')}${o.pickupOpensAt ? `Pickup <strong>${fmtWindow(o.pickupOpensAt, o.pickupDeadline)}</strong>` : `Collect by <strong>${fmtTime(o.pickupDeadline)}</strong>`} · ends <span data-countdown="${o.pickupDeadline}">${untilText(o.pickupDeadline)}</span></p>
      </div></div>
      <div class="order-side">
        <div class="code-box"><small>Pickup code</small><span class="code" aria-label="${spacedDigits(o.pickupCode)}">${esc(o.pickupCode)}</span></div>
        <p class="order-meta">${free ? 'Nothing to pay' : `To pay at the shop: <strong>${money(o.totalPrice)}</strong>`}</p>
        <div class="order-side-actions">
          <button class="btn btn-sm" type="button" data-copy="${esc(o.pickupCode)}">${icon('copy')}<span>Copy</span></button>
          <button class="btn btn-sm btn-danger" type="button" data-cancel="${o.orderId}">Cancel</button>
        </div>
      </div>
    </article>`;
  }).join('') : `
    <div class="empty"><div class="empty-icon">${icon('receipt')}</div>
      <h3>${myOrders.length ? 'Nothing waiting to be collected' : 'No reservations yet'}</h3>
      <p>When you reserve food, your pickup code shows up here.</p>
      <button class="btn btn-primary" type="button" data-view="shop">${icon('search')}<span>Find food nearby</span></button></div>`;

  $('#historyBlock').hidden = !past.length;
  const older = past.length - historyShown;
  $('#orderHistory').innerHTML = past.slice(0, historyShown).map((o) => {
    const [text, cls] = STATUS[o.status];
    return `<div class="history-row">
      <div><strong>${esc(o.foodItemName)} × ${o.quantity}</strong><p class="order-meta">${esc(o.storeName)} · ${fmtTime(o.pickupDeadline)}</p></div>
      <span class="history-price">${isFree(o.totalPrice) ? 'Free' : money(o.totalPrice)}</span>
      <span class="badge ${cls}">${text}</span>
    </div>`;
  }).join('') + (older > 0 ? `<div class="history-more"><button class="btn btn-sm" type="button" id="historyMore">
      Show older orders (${older})</button></div>` : '');
}

function showMoreHistory() {
  const firstNew = historyShown;
  historyShown += HISTORY_PAGE;
  renderOrders();
  // Keep keyboard users in place: focus the first newly shown order.
  const row = $$('#orderHistory .history-row')[firstNew];
  if (row) { row.tabIndex = -1; row.focus(); }
}

async function cancelOrder(orderId) {
  const o = myOrders.find((x) => x.orderId === orderId);
  const ok = await confirmDialog({
    title: 'Cancel this reservation?',
    body: `${o ? `${o.foodItemName} × ${o.quantity}` : 'The food'} goes back on sale for other people. This can't be undone.`,
    confirmLabel: 'Cancel reservation', cancelLabel: 'Keep it', danger: true,
  });
  if (!ok) return;
  try {
    await api(`/orders/${orderId}/cancel`, { method: 'POST' });
    toast('Reservation cancelled. The food is available again.');
    loadMyOrders();
    search();
  } catch (e) { fail(e); }
}

// ---------- store owner: the store itself ----------

let myStores = [];
let currentStoreId = null;
let storeItems = [];
let storeOrders = [];
let storeMap, storePin;
let storePinMoved = false;

function currentStore() { return myStores.find((s) => s.id === currentStoreId); }

async function loadStores() {
  try {
    myStores = await api('/stores/mine');
  } catch (e) { fail(e); return; }
  const select = $('#storeSelect');
  select.innerHTML = myStores.map((s) => `<option value="${s.id}">${esc(s.name)}</option>`).join('');
  select.hidden = myStores.length < 2;
  $('#editStore').hidden = !myStores.length;
  $('#addStore').hidden = !myStores.length;
  $('#storeEmpty').hidden = !!myStores.length;
  $('#storeContent').hidden = !myStores.length;
  if (!myStores.length) {
    currentStoreId = null;
    $('#storeTitle').textContent = `Welcome, ${me.fullName.split(' ')[0]}`;
    $('#storeSub').textContent = 'Let’s set up your store.';
    $('#storeSuspended').hidden = true;
    return;
  }
  if (!myStores.some((s) => s.id === currentStoreId)) currentStoreId = myStores[0].id;
  select.value = currentStoreId;
  loadStoreDetails();
}

function renderStoreHead() {
  const s = currentStore();
  $('#storeTitle').textContent = s.name;
  $('#storeSub').innerHTML = `${icon('pin', 'inline-icon')}<span>${esc(s.address)}</span> ${s.active ? '<span class="badge">Visible to customers</span>' : '<span class="badge badge-red">Suspended</span>'}`;
  $('#storeSuspended').hidden = s.active;
}

let today = null;          // TodayResponse of the current store
let storeTz = 'Europe/Bucharest';
let storeTemplates = [];
const BROWSER_TZ = Intl.DateTimeFormat().resolvedOptions().timeZone;
const STATE_BADGE = {
  ACTIVE: ['Live', ''], SOLD_OUT: ['Sold out', 'badge-grey'], PAUSED: ['Paused', 'badge-grey'],
  DRAFT: ['Draft', 'badge-draft'], EXPIRED: ['Expired', 'badge-red'],
};
const STATE_ORDER = { DRAFT: 0, ACTIVE: 1, PAUSED: 2, SOLD_OUT: 3, EXPIRED: 4 };

/** "18:00" in the store's own time zone, whatever zone this device uses. */
function storeClock(iso) {
  return new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23', timeZone: storeTz }).format(new Date(iso));
}
const nowInStore = () => storeClock(new Date().toISOString());
const hhmm = (time) => (time ? String(time).slice(0, 5) : '');
const offerWindow = (o) => (o.pickupStartsAt ? `${storeClock(o.pickupStartsAt)}–${storeClock(o.expiresAt)}` : `until ${storeClock(o.expiresAt)}`);
function storeDateLabel() {
  return new Intl.DateTimeFormat(undefined, { weekday: 'long', day: 'numeric', month: 'long', timeZone: storeTz }).format(new Date(today?.now ?? Date.now()));
}

async function loadStoreDetails() {
  renderStoreHead();
  if (!today) $('#todayOffers').innerHTML = '<div class="skeleton" style="height:84px"></div><div class="skeleton" style="height:84px"></div>';
  try {
    [storeOrders, today] = await Promise.all([
      api(`/stores/${currentStoreId}/orders?status=RESERVED`),
      api(`/stores/${currentStoreId}/today`),
    ]);
  } catch (e) { fail(e); return; }
  storeTz = today.timeZone;
  storeItems = today.offers;
  storeTemplates = today.templates;
  renderStoreAlerts();
  renderStoreStats();
  renderStoreOrders();
  renderTodayOffers();
  renderTemplates();
  renderReminder();
}

/** Figures computed by the server from today's offers and orders. */
function renderStoreStats() {
  const i = today.insights;
  const stat = (label, value, accent, extra = '') => `<div class="card stat${accent ? ' is-accent' : ''}"><span>${label}</span><strong>${value}${extra}</strong></div>`;
  $('#storeStats').innerHTML =
    stat('Reserved, waiting', i.reservedUnits, true) +
    stat('Available now', i.availableUnits, false, i.availableFreeUnits ? `<small>+${i.availableFreeUnits} free</small>` : '') +
    stat('Collected today', i.collectedUnits) +
    stat('Donated today', i.donatedUnits);
}

function renderStoreOrders() {
  $('#waitingCount').textContent = storeOrders.length;
  $('#storeOrders').innerHTML = storeOrders.length ? storeOrders.map((o) => `
    <div class="pickup-row">
      <div><strong>${esc(o.foodItemName)} × ${o.quantity}</strong>
        <p class="order-meta">${o.pickupOpensAt ? `Pickup ${storeClock(o.pickupOpensAt)}–${storeClock(o.pickupDeadline)}` : `Collect by ${storeClock(o.pickupDeadline)}`} · <span data-countdown="${o.pickupDeadline}">${untilText(o.pickupDeadline)}</span></p></div>
      ${isFree(o.totalPrice) ? '<span class="badge badge-sun plain">Free</span>' : `<strong>${money(o.totalPrice)}</strong>`}
    </div>`).join('')
    : '<p class="muted small" style="padding: 8px 0">No open reservations right now.</p>';
}

function offerActions(o) {
  const edit = `<button class="btn btn-sm" type="button" data-adjust="${o.id}" aria-label="Edit ${esc(o.name)}">${icon('edit')}<span>Edit</span></button>`;
  const remove = `<button class="btn btn-sm btn-danger btn-icon" type="button" data-delete="${o.id}" aria-label="Remove ${esc(o.name)}">${icon('trash')}</button>`;
  switch (o.state) {
    case 'DRAFT': return `<button class="btn btn-sm btn-primary" type="button" data-offer-status="ACTIVE"><span>Publish</span></button>${edit}${remove}`;
    case 'PAUSED': return `<button class="btn btn-sm btn-primary" type="button" data-offer-status="ACTIVE"><span>Resume</span></button>${edit}${remove}`;
    case 'EXPIRED': return '';
    default: return `${edit}<button class="btn btn-sm" type="button" data-offer-status="PAUSED"><span>Pause</span></button>${remove}`;
  }
}

function renderTodayOffers() {
  const tzNote = storeTz !== BROWSER_TZ ? ` · times in ${storeTz.replace(/_/g, ' ')}` : '';
  $('#todayDate').textContent = storeDateLabel() + tzNote;
  const box = $('#todayOffers');
  if (!storeItems.length) {
    box.innerHTML = `<div class="empty"><div class="empty-icon">${icon('box')}</div><h3>Nothing on offer today</h3>
      <p>${storeTemplates.length ? 'Pick from your saved products and enter today’s quantities. It takes about a minute.'
        : 'Save the products you sell often once, then publish them here each day in one go.'}</p>
      <button class="btn btn-primary" type="button" data-setup-today>${icon('plus')}<span>Set up today's offers</span></button></div>`;
    return;
  }
  const offers = [...storeItems].sort((a, b) => STATE_ORDER[a.state] - STATE_ORDER[b.state] || new Date(a.expiresAt) - new Date(b.expiresAt));
  box.innerHTML = offers.map((o, idx) => {
    const [label, cls] = STATE_BADGE[o.state];
    return `
    <article class="card listing${o.rescueDrop ? ' is-drop' : ''} is-${o.state.toLowerCase().replace('_', '-')}${o.state === 'EXPIRED' || o.state === 'SOLD_OUT' ? ' is-ended' : ''}" data-offer-id="${o.id}" style="--i:${Math.min(idx, 10)}">
      <div class="photo-frame">${photoHtml(o, { illustrative: false, sizes: '92px', emptyLabel: '' })}</div>
      <div class="listing-info">
        ${o.rescueDrop ? `<span class="drop-label">${icon('bolt')}Rescue Drop${o.kind === 'SURPRISE_BAG' ? ' · surprise bag' : ''}</span>`
          : o.kind === 'SURPRISE_BAG' ? `<span class="kind-label">${icon('box')}Surprise bag</span>` : ''}
        <div class="listing-title"><h3>${esc(o.name)}</h3><span class="badge ${cls}">${label}</span></div>
        <div class="listing-meta">
          <span><b>${o.quantityAvailable}</b> for sale</span>
          ${o.payItForwardQuantity ? `<span><b>${o.payItForwardQuantity}</b> free</span>` : ''}
          <span><b>${money(o.discountedPrice)}</b> <s>${money(o.originalPrice)}</s></span>
          ${o.rescueDrop && o.state === 'ACTIVE' ? `<span>Ends <b data-countdown="${o.expiresAt}">${untilText(o.expiresAt)}</b></span>` : `<span>Pickup <b>${offerWindow(o)}</b></span>`}
        </div>
      </div>
      <div class="listing-actions">${offerActions(o)}</div>
    </article>`;
  }).join('');
  settleImages(box);
}

function renderTemplates() {
  $('#templateCount').textContent = storeTemplates.length;
  const box = $('#templateList');
  if (!storeTemplates.length) {
    box.innerHTML = `<div class="empty" style="grid-column:1/-1"><div class="empty-icon">${icon('store')}</div><h3>No saved products yet</h3>
      <p>Add what you usually have left, like a loaf of bread or a surprise bag. Publishing it each day then takes seconds.</p>
      <button class="btn btn-primary" type="button" data-new-template>${icon('plus')}<span>Save your first product</span></button></div>`;
    return;
  }
  box.innerHTML = storeTemplates.map((t, idx) => `
    <article class="card template-card" style="--i:${Math.min(idx, 10)}">
      <div class="photo-frame">${photoHtml(t, { illustrative: false, sizes: '240px', emptyLabel: 'No photo yet' })}</div>
      <div class="template-body">
        ${t.kind === 'SURPRISE_BAG' ? `<span class="kind-label">${icon('box')}Surprise bag</span>` : ''}
        <h3>${esc(t.name)}</h3>
        <p><b>${money(t.discountedPrice)}</b> <s>${money(t.originalPrice)}</s> · pickup ${hhmm(t.pickupStart)}–${hhmm(t.pickupEnd)}</p>
        <div class="template-actions">
          <button class="btn btn-sm" type="button" data-edit-template="${t.id}">${icon('edit')}<span>Edit</span></button>
          <button class="btn btn-sm btn-danger btn-icon" type="button" data-archive-template="${t.id}" aria-label="Remove ${esc(t.name)} from your products">${icon('trash')}</button>
        </div>
      </div>
    </article>`).join('');
  settleImages(box);
}

function renderReminder() {
  const r = today.reminder;
  $('#reminderBanner').hidden = !r.due;
  $('#reminderOn').checked = r.enabled;
  $('#reminderTime').value = hhmm(r.time) || '15:00';
}

function openStoreForm(store) {
  const f = $('#storeForm');
  f.reset();
  for (const input of $$('input', f)) showFieldError(input, '');
  setAlert('#storeAlert', '');
  f.editId.value = store?.id ?? '';
  $('#storeDialogTitle').textContent = store ? 'Edit store details' : 'Register a store';
  $('#storeSubmit span').textContent = store ? 'Save changes' : 'Register store';
  $('#storeSuggestions').hidden = true;
  $('#storeSuggestions').innerHTML = '';
  $('#storeMap').hidden = true;
  $('#storeMapHint').hidden = true;
  storePinMoved = false;
  if (store) { f.name.value = store.name; f.address.value = store.address; }
  openDialog($('#storeDialog'));
  if (store) placeStorePin(store.latitude, store.longitude);
}

function placeStorePin(lat, lng) {
  if (!hasMaps) return;
  $('#storeMap').hidden = false;
  $('#storeMapHint').hidden = false;
  if (!storeMap) {
    storeMap = L.map('storeMap');
    tileLayer().addTo(storeMap);
  }
  storeMap.invalidateSize();
  storeMap.setView([lat, lng], 17);
  storePin?.remove();
  storePin = L.marker([lat, lng], {
    draggable: true, title: 'Your store', alt: 'Store location',
    icon: L.divIcon({ className: '', html: '<div class="pin"><span>✓</span></div>', iconSize: [34, 34], iconAnchor: [17, 40] }),
  }).addTo(storeMap);
  storePin.on('dragend', () => { storePinMoved = true; });
}

async function locateStoreAddress(button) {
  const input = $('#storeForm').address;
  const address = input.value.trim();
  if (address.length < 3) { showFieldError(input, 'Type the address first'); input.focus(); return; }
  showFieldError(input, '');
  await withLoading(button, async () => {
    try {
      const places = await api('/geocode?q=' + encodeURIComponent(address));
      if (!places.length) { showFieldError(input, 'Address not found. Add the city, or check the spelling.'); return; }
      const box = $('#storeSuggestions');
      if (places.length > 1) {
        box.places = places;
        box.innerHTML = '<div class="suggestions-title">Pick the right one</div>' + places.map((p, i) =>
          `<button type="button" data-store-place="${i}">${icon('pin')}<span>${esc(p.label)}</span></button>`).join('');
        box.hidden = false;
      } else {
        box.hidden = true;
      }
      storePinMoved = true; // the pin now reflects a confirmed lookup, so send its position
      placeStorePin(places[0].latitude, places[0].longitude);
    } catch (e) { setAlert('#storeAlert', e.message); }
  });
}

async function saveStore(event) {
  event.preventDefault();
  const f = event.target;
  setAlert('#storeAlert', '');
  if (!validateForm(f)) return;
  const body = { name: f.name.value.trim(), address: f.address.value.trim() };
  if (storePinMoved && storePin) {
    const { lat, lng } = storePin.getLatLng();
    body.latitude = lat; body.longitude = lng;
  }
  // New stores take this device's time zone; "Today" and pickup times are calculated in it.
  if (!f.editId.value && BROWSER_TZ?.includes('/')) body.timeZone = BROWSER_TZ;
  await withLoading($('#storeSubmit'), async () => {
    try {
      const id = f.editId.value;
      const saved = id
        ? await api(`/stores/${id}`, { method: 'PUT', body })
        : await api('/stores', { method: 'POST', body });
      $('#storeDialog').close();
      toast(id ? 'Store details saved' : `“${saved.name}” is registered. Time to add some food!`);
      currentStoreId = saved.id;
      loadStores();
    } catch (e) { setAlert('#storeAlert', e.message); }
  });
}

// ---------- store owner: pickups and listings ----------

async function confirmPickup(event) {
  event.preventDefault();
  const f = event.target;
  if (!validateForm(f)) return;
  await withLoading($('[type=submit]', f), async () => {
    try {
      const order = await api(`/stores/${currentStoreId}/pickups`, { method: 'POST', body: { pickupCode: f.pickupCode.value } });
      const free = isFree(order.totalPrice);
      $('#pickupResult').innerHTML = `<div class="pickup-result">
        <span class="check-small">${icon('check')}</span>
        <div><strong>Hand over ${order.quantity} × ${esc(order.foodItemName)}</strong>
        <p>${free ? 'Free Pay-It-Forward item: nothing to pay.' : `Collect <b>${money(order.totalPrice)}</b> from the customer.`}</p></div></div>`;
      f.reset();
      loadStoreDetails();
    } catch (e) {
      showFieldError(f.pickupCode, e.message);
      f.pickupCode.focus();
      f.pickupCode.select();
    }
  });
}

// ---------- store owner: photo picker shared by the offer and product editors ----------

/**
 * Wires a dropzone whose elements are named prefix + PhotoInput, PhotoPreview, DropzoneTitle,
 * ClearPhoto, RemovePhoto, PhotoError and Dropzone. Checks type and size before uploading (the
 * server checks again) and previews the chosen photo.
 */
function photoPicker(prefix, onRemove) {
  const el = (name) => document.getElementById(prefix + name);
  let objectUrl = null;
  let currentUrl = null;
  const preview = (html) => { el('PhotoPreview').innerHTML = html; settleImages(el('PhotoPreview')); };
  const picker = {
    reset(url) { currentUrl = url || null; el('PhotoInput').value = ''; picker.error(''); picker.showCurrent(); },
    /** Hides any photo suggestion: it belonged to the photo that was there before. */
    clearSuggestion() { el('Suggestion').hidden = true; el('Suggestion').innerHTML = ''; },
    setCurrent(url) { currentUrl = url || null; picker.showCurrent(); },
    showCurrent() {
      preview(currentUrl ? `<img src="${esc(currentUrl)}" alt="Current photo">` : fallbackTile(''));
      el('DropzoneTitle').textContent = currentUrl ? 'Replace the photo' : 'Add a photo';
      el('RemovePhoto').hidden = !currentUrl;
      el('ClearPhoto').hidden = true;
      el('Suggest').hidden = !currentUrl;
      picker.clearSuggestion();
    },
    choose(file) {
      const input = el('PhotoInput');
      picker.error('');
      if (!file) { picker.showCurrent(); return; }
      if (!PHOTO_TYPES.includes(file.type)) { input.value = ''; picker.error('Use a JPEG, PNG or WebP photo.'); picker.showCurrent(); return; }
      if (file.size > MAX_PHOTO_BYTES) { input.value = ''; picker.error('This photo is larger than 2 MB. Choose a smaller one.'); picker.showCurrent(); return; }
      picker.release();
      objectUrl = URL.createObjectURL(file);
      preview(`<img src="${objectUrl}" alt="Preview of the new photo">`);
      el('DropzoneTitle').textContent = 'New photo ready';
      el('ClearPhoto').hidden = false;
      el('RemovePhoto').hidden = true;
      el('Suggest').hidden = false;
      picker.clearSuggestion();
    },
    file: () => el('PhotoInput').files[0],
    error(message) { el('PhotoError').textContent = message || ''; },
    release() { if (objectUrl) { URL.revokeObjectURL(objectUrl); objectUrl = null; } },
  };
  el('PhotoInput').addEventListener('change', (e) => picker.choose(e.target.files[0]));
  el('ClearPhoto').addEventListener('click', () => { el('PhotoInput').value = ''; picker.choose(null); });
  el('RemovePhoto').addEventListener('click', (e) => onRemove(e.currentTarget));
  el('Suggest').addEventListener('click', (e) => suggestFromPhoto(prefix, e.currentTarget));
  el('Suggestion').addEventListener('click', (e) => {
    const t = e.target.closest('button');
    if (t?.dataset.useSuggestion !== undefined) useSuggestion(prefix);
    else if (t?.dataset.dismissSuggestion !== undefined) picker.clearSuggestion();
  });
  for (const type of ['dragenter', 'dragover']) el('Dropzone').addEventListener(type, () => el('Dropzone').classList.add('is-dragover'));
  for (const type of ['dragleave', 'drop']) el('Dropzone').addEventListener(type, () => el('Dropzone').classList.remove('is-dragover'));
  return picker;
}

// ---------- AI-assisted listings ----------
// A free image model (MobileNet) runs in the owner's browser; nothing is sent to a paid service.
// The server turns what it saw into a suggestion, and the owner decides whether to use it.

const TFJS_URL = 'https://cdn.jsdelivr.net/npm/@tensorflow/tfjs@4.22.0/dist/tf.min.js';
const MOBILENET_URL = 'https://cdn.jsdelivr.net/npm/@tensorflow-models/mobilenet@2.1.1/dist/mobilenet.min.js';
let imageModel = null;
const suggestions = {};

function loadScript(src) {
  return new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = src;
    script.crossOrigin = 'anonymous';
    script.onload = resolve;
    script.onerror = () => { script.remove(); reject(new Error('The image model could not be downloaded. Check your connection and try again.')); };
    document.head.append(script);
  });
}

/** Downloads the model once per page load (about 14 MB the first time, then from the browser cache). */
async function getImageModel() {
  imageModel ??= (async () => {
    if (!window.tf) await loadScript(TFJS_URL);
    if (!window.mobilenet) await loadScript(MOBILENET_URL);
    return window.mobilenet.load({ version: 2, alpha: 1.0 });
  })();
  try { return await imageModel; } catch (e) { imageModel = null; throw e; }
}

async function suggestFromPhoto(prefix, button) {
  const panel = $(`#${prefix}Suggestion`);
  const img = $(`#${prefix}PhotoPreview img`);
  if (!img) return;
  panel.hidden = false;
  panel.innerHTML = `<p class="ai-status">${imageModel ? 'Looking at the photo…' : 'Loading the free image model. The first time takes a few seconds (about 14 MB).'}</p>`;
  await withLoading(button, async () => {
    try {
      // The model reads an image at its displayed size, and the preview is a small thumbnail,
      // so it gets a full-size copy that is not on the page.
      const full = new Image();
      full.src = img.currentSrc || img.src;
      await full.decode();
      const model = await getImageModel();
      const guesses = await model.classify(full, 5);
      const s = await api('/stores/listing-suggestions', { method: 'POST', body: {
        name: $(`#${prefix}Name`).value,
        labels: guesses.map((g) => ({ className: g.className.slice(0, 200), probability: Math.min(1, Math.max(0, g.probability)) })) } });
      suggestions[prefix] = s;
      renderPhotoSuggestion(prefix, s);
    } catch (e) {
      panel.innerHTML = `<p class="form-alert">${esc(e.message)}</p>`;
    }
  });
}

function renderPhotoSuggestion(prefix, s) {
  const seen = s.seen.length ? `What the model saw: ${esc(s.seen.join(', '))}.` : 'The model was not sure about anything in this photo.';
  const head = `<div class="ai-head">${icon('spark')}<strong>Suggestion from the photo</strong><span class="badge badge-sun plain">Check before using</span></div>`;
  $(`#${prefix}Suggestion`).innerHTML = s.recognised ? `${head}
    <dl class="ai-fields">
      <div><dt>Name</dt><dd>${esc(s.name)}</dd></div>
      <div><dt>Food types</dt><dd>${s.tags.map((t) => `<span class="badge plain">${esc(TAG_LABEL[t] || t)}</span>`).join(' ')}</dd></div>
      <div><dt>Description</dt><dd>${esc(s.description)}</dd></div>
    </dl>
    <p class="ai-note">${seen} It can be wrong. It never fills in allergens or labels like vegetarian or vegan: add those yourself.</p>
    <div class="ai-actions">
      <button type="button" class="btn btn-sm btn-primary" data-use-suggestion>${icon('check')}<span>Use these</span></button>
      <button type="button" class="btn btn-sm" data-dismiss-suggestion>Dismiss</button>
    </div>`
    : `${head}<p>This doesn't look like a food the model knows. Fill in the details yourself.</p>
    <p class="ai-note">${seen}</p>
    <div class="ai-actions"><button type="button" class="btn btn-sm" data-dismiss-suggestion>Dismiss</button></div>`;
}

/** Fills the form only when the owner says so. Food types are added; ones already ticked stay ticked. */
function useSuggestion(prefix) {
  const s = suggestions[prefix];
  if (!s?.recognised) return;
  $(`#${prefix}Name`).value = s.name;
  $(`#${prefix}Description`).value = s.description;
  for (const tag of s.tags) {
    const box = $(`#${prefix}Tags input[value="${tag}"]`);
    if (box) box.checked = true;
  }
  $(`#${prefix}Suggestion`).innerHTML = `<p class="ai-status">${icon('check')}Filled in. Check the details and edit them before saving.</p>`;
  $(`#${prefix}Name`).focus();
}

async function uploadPhoto(path, file) {
  const form = new FormData();
  form.append('file', file);
  await api(path, { method: 'POST', form });
}

/** Live hint while typing prices, and the same rule the server checks: a real discount. */
function updateDiscountPreview(form = $('#itemForm'), out = $('#discountPreview')) {
  const orig = Number(form.originalPrice.value), now = Number(form.discountedPrice.value);
  form.discountedPrice.setCustomValidity('');
  if (!form.originalPrice.value || form.discountedPrice.value === '') { out.textContent = ''; out.classList.remove('is-bad'); return; }
  if (now >= orig) {
    out.textContent = 'Must be lower than the original price';
    out.classList.add('is-bad');
    form.discountedPrice.setCustomValidity('Your price must be lower than the original price');
    return;
  }
  out.classList.remove('is-bad');
  out.textContent = `${Math.round((orig - now) / orig * 100)}% off for customers`;
}

function clearForm(form, alertSelector) {
  form.reset();
  for (const input of $$('input', form)) showFieldError(input, '');
  setAlert(alertSelector, '');
}

// ---------- store owner: one-off offers ----------

let editingItem = null;

function openItemEditor(item) {
  const f = $('#itemForm');
  clearForm(f, '#itemAlert');
  editingItem = item || null;
  f.editId.value = item?.id ?? '';
  if (item) {
    for (const key of ['name', 'description', 'originalPrice', 'discountedPrice', 'quantityAvailable', 'payItForwardQuantity']) f[key].value = item[key] ?? '';
    f.expiresAt.value = toLocalInput(item.expiresAt);
    f.pickupFrom.value = item.pickupStartsAt ? toLocalInput(item.pickupStartsAt) : '';
  } else {
    f.quantityAvailable.value = 1;
    f.payItForwardQuantity.value = 0;
    f.expiresAt.value = toLocalInput(new Date(Date.now() + 4 * 3600 * 1000).toISOString());
  }
  tagChips($('#itemTags'), item?.tags ?? [], 'itemTag');
  f.unitWeightKg.value = item?.unitWeightGrams ? item.unitWeightGrams / 1000 : '';
  $('#itemDialogTitle').textContent = item ? 'Edit offer' : 'New one-off offer';
  $('#itemSubmit span').textContent = item ? 'Save changes' : 'Publish';
  itemPhoto.reset(item?.photoUrl);
  updateDiscountPreview();
  openDialog($('#itemDialog'));
}

async function saveItem(event) {
  event.preventDefault();
  const f = event.target;
  setAlert('#itemAlert', '');
  updateDiscountPreview();
  if (!validateForm(f)) return;
  if (f.pickupFrom.value && f.pickupFrom.value >= f.expiresAt.value) {
    showFieldError(f.pickupFrom, 'Must be before the end time');
    f.pickupFrom.focus();
    return;
  }
  const body = {
    name: f.name.value.trim(),
    description: f.description.value.trim() || null,
    originalPrice: Number(f.originalPrice.value),
    discountedPrice: Number(f.discountedPrice.value),
    quantityAvailable: Number(f.quantityAvailable.value),
    payItForwardQuantity: Number(f.payItForwardQuantity.value),
    expiresAt: new Date(f.expiresAt.value).toISOString(),
    pickupStartsAt: f.pickupFrom.value ? new Date(f.pickupFrom.value).toISOString() : null,
    tags: checkedTags($('#itemTags')),
    unitWeightGrams: f.unitWeightKg.value ? Math.round(Number(f.unitWeightKg.value) * 1000) : null,
  };
  const photo = itemPhoto.file();
  await withLoading($('#itemSubmit'), async () => {
    let saved;
    try {
      const id = f.editId.value;
      saved = id
        ? await api(`/stores/${currentStoreId}/food-items/${id}`, { method: 'PUT', body })
        : await api(`/stores/${currentStoreId}/food-items`, { method: 'POST', body });
    } catch (e) { setAlert('#itemAlert', e.message); return; }
    if (photo) {
      try {
        await uploadPhoto(`/stores/${currentStoreId}/food-items/${saved.id}/photo`, photo);
      } catch (e) {
        // The offer itself is saved; keep the dialog open on it so the photo can be retried.
        f.editId.value = saved.id;
        editingItem = saved;
        $('#itemSubmit span').textContent = 'Save changes';
        itemPhoto.error('The offer was saved, but the photo was not accepted: ' + e.message);
        loadStoreDetails();
        return;
      }
    }
    $('#itemDialog').close();
    toast(f.editId.value ? 'Changes saved' : 'Published! Customers nearby can see it now.');
    loadStoreDetails();
  });
}

async function removeCurrentPhoto(button) {
  if (!editingItem) return;
  const ok = await confirmDialog({ title: 'Remove this photo?', body: 'Customers will see an illustrative photo instead until you add a new one.', confirmLabel: 'Remove photo', danger: true });
  if (!ok) return;
  await withLoading(button, async () => {
    try {
      await api(`/stores/${currentStoreId}/food-items/${editingItem.id}/photo`, { method: 'DELETE' });
      editingItem = { ...editingItem, photoUrl: null };
      itemPhoto.setCurrent(null);
      toast('Photo removed');
      loadStoreDetails();
    } catch (e) { itemPhoto.error(e.message); }
  });
}

async function deleteItem(id) {
  const item = storeItems.find((i) => i.id === id);
  const ok = await confirmDialog({
    title: `Remove “${item?.name ?? 'this offer'}”?`,
    body: 'Customers will no longer see it. If someone already reserved it, their reservation stays valid and the offer stays in your list as sold out.',
    confirmLabel: 'Remove offer', danger: true,
  });
  if (!ok) return;
  try {
    await api(`/stores/${currentStoreId}/food-items/${id}`, { method: 'DELETE' });
    toast('Offer removed');
    loadStoreDetails();
  } catch (e) { fail(e); }
}

// ---------- store owner: today's offers from saved products ----------

function stepperInput(id, what) {
  return `<div class="stepper sm"><button type="button" data-bump="-1" data-target="${id}" aria-label="One less, ${what}">${icon('minus')}</button>`
    + `<input id="${id}" type="number" inputmode="numeric" min="0" max="10000" step="1" placeholder="0" aria-label="${what}">`
    + `<button type="button" data-bump="1" data-target="${id}" aria-label="One more, ${what}">${icon('plus')}</button></div>`;
}

function setupRow(t, offer, now) {
  const name = esc(t.name);
  const head = `<div class="photo-frame">${photoHtml(t, { illustrative: false, tag: false, sizes: '64px', emptyLabel: '' })}</div>
    <div class="setup-info"><strong>${name}</strong><span>${t.kind === 'SURPRISE_BAG' ? 'Surprise bag' : 'Product'} · ${money(t.discountedPrice)} <s>${money(t.originalPrice)}</s></span></div>`;
  if (offer) {
    const [label, cls] = STATE_BADGE[offer.state];
    return `<div class="setup-row is-live">${head}<p class="setup-live"><span class="badge ${cls}">${label}</span>Already set up today</p></div>`;
  }
  const start = hhmm(t.pickupStart), end = hhmm(t.pickupEnd);
  const over = end <= now;
  return `<div class="setup-row" data-setup-template="${t.id}">${head}
    <div class="setup-controls">
      <div class="setup-field"><span aria-hidden="true">For sale</span>${stepperInput(`qty-${t.id}`, `units for sale of ${name}`)}</div>
      <div class="setup-field"><span aria-hidden="true">Free</span>${stepperInput(`free-${t.id}`, `free units of ${name}`)}</div>
      <div class="setup-field setup-window"><span aria-hidden="true">Pickup</span><div class="time-range">
        <input class="input" type="time" name="start" value="${start}" aria-label="Pickup from, ${name}">
        <span aria-hidden="true">–</span>
        <input class="input" type="time" name="end" value="${over ? '' : end}" aria-label="Pickup until, ${name}">
      </div></div>
    </div>
    ${over ? `<p class="setup-warning">The usual pickup time (${start}–${end}) is already over today. Choose a new end time.</p>` : ''}
  </div>`;
}

function openSetup() {
  if (!today) return;
  const live = new Map(storeItems.filter((o) => o.templateId && o.state !== 'EXPIRED').map((o) => [o.templateId, o]));
  const list = $('#setupList');
  setAlert('#setupAlert', '');
  $('#setupConfirm').checked = false;
  $('#setupSub').textContent = `${storeDateLabel()}. Enter what is really left today; nothing is copied from yesterday.`;
  list.innerHTML = storeTemplates.length
    // Products still to set up first; those already on offer today after them.
    ? [...storeTemplates].sort((a, b) => live.has(a.id) - live.has(b.id)).map((t) => setupRow(t, live.get(t.id), nowInStore())).join('')
    : `<div class="setup-empty"><p>You have no saved products yet. Save the things you sell often once, then publish them here every day.</p>
        <button class="btn btn-primary" type="button" data-new-template>${icon('plus')}<span>Save a product</span></button></div>`;
  settleImages(list);
  recalcSetup();
  openDialog($('#setupDialog'));
  loadAutopilot();
}

function setupLines() {
  return $$('#setupList [data-setup-template]').map((row) => {
    const id = Number(row.dataset.setupTemplate);
    return {
      row, templateId: id,
      quantityAvailable: Number($(`#qty-${id}`).value) || 0,
      payItForwardQuantity: Number($(`#free-${id}`).value) || 0,
      pickupStart: $('[name=start]', row).value,
      pickupEnd: $('[name=end]', row).value,
      // A lower price is sent only when the owner applied the Autopilot's suggestion.
      discountedPrice: $('.price-chip[aria-pressed="true"]', row) ? Number($('.price-chip', row).dataset.price) : undefined,
    };
  });
}

function recalcSetup() {
  let products = 0, units = 0, free = 0, prices = 0;
  for (const l of setupLines()) {
    const on = l.quantityAvailable > 0 || l.payItForwardQuantity > 0;
    l.row.classList.toggle('is-selected', on);
    if (on) { products++; units += Math.max(0, l.quantityAvailable); free += Math.max(0, l.payItForwardQuantity); if (l.discountedPrice) prices++; }
  }
  $('#setupSummary').textContent = products
    ? `${plural(products, 'product', 'products')} · ${plural(units, 'unit', 'units')} for sale${free ? ` · ${free} free` : ''}${prices ? ` · ${plural(prices, 'lower price', 'lower prices')} for today` : ''}`
    : 'Enter quantities for the products you have today.';
  $('#setupPublish').disabled = $('#setupDraft').disabled = !products;
  $('#setupPublish span').textContent = products ? `Publish ${plural(products, 'offer', 'offers')}` : 'Publish';
}

function bump(button) {
  const input = document.getElementById(button.dataset.target);
  const min = Number(input.min || 0), max = Number(input.max || 10000);
  input.value = Math.max(min, Math.min(max, (Number(input.value) || 0) + Number(button.dataset.bump)));
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

function setupProblem(message, focus) {
  setAlert('#setupAlert', message);
  $('#setupAlert').scrollIntoView({ block: 'nearest' });
  focus?.focus();
}

async function submitSetup(publish) {
  setAlert('#setupAlert', '');
  const chosen = setupLines().filter((l) => l.quantityAvailable > 0 || l.payItForwardQuantity > 0);
  if (!chosen.length) return;
  for (const l of chosen) {
    const name = storeTemplates.find((t) => t.id === l.templateId)?.name;
    if (![l.quantityAvailable, l.payItForwardQuantity].every((n) => Number.isInteger(n) && n >= 0)) {
      return setupProblem(`Quantities for “${name}” must be whole numbers, 0 or more.`, $(`#qty-${l.templateId}`));
    }
    if (!l.pickupStart || !l.pickupEnd || l.pickupEnd <= l.pickupStart) {
      return setupProblem(`Check the pickup time for “${name}”: it must end after it starts, on the same day.`, $('[name=end]', l.row));
    }
  }
  if (publish && !$('#setupConfirm').checked) {
    return setupProblem('Please confirm that these quantities are really available today.', $('#setupConfirm'));
  }
  const button = publish ? $('#setupPublish') : $('#setupDraft');
  (publish ? $('#setupDraft') : $('#setupPublish')).disabled = true;
  await withLoading(button, async () => {
    try {
      const created = await api(`/stores/${currentStoreId}/offers`, {
        method: 'POST', body: { publish, offers: chosen.map(({ row, ...line }) => line) },
      });
      $('#setupDialog').close();
      toast(publish ? `${plural(created.length, 'offer', 'offers')} published. Customers nearby can reserve now.`
        : `${plural(created.length, 'draft', 'drafts')} saved. Publish when you're ready.`);
      loadStoreDetails();
    } catch (e) { setupProblem(e.message); }
  });
  recalcSetup();
}

// ---------- store owner: alerts, Autopilot and Rescue Drops ----------

const ALERT_ICON = { LOW_STOCK: 'alert', SOLD_OUT_EARLY: 'check', LEFTOVERS_ENDING: 'bolt', DRAFTS_WAITING: 'edit' };

/** Things to act on now, computed by the server from today's offers and orders. */
function renderStoreAlerts() {
  $('#storeAlerts').innerHTML = (today.alerts || []).map((a) => {
    const action = a.type === 'LEFTOVERS_ENDING'
      ? `<button class="btn btn-sm btn-drop-solid" type="button" data-open-drop="bundle">${icon('bolt')}<span>Bundle as a drop</span></button>`
      : a.type === 'LOW_STOCK' ? `<button class="btn btn-sm" type="button" data-adjust="${a.itemId}">${icon('plus')}<span>Add more</span></button>` : '';
    return `<div class="store-alert is-${a.level}">${icon(ALERT_ICON[a.type] || 'alert')}<p>${esc(a.message)}</p>${action}</div>`;
  }).join('');
}

let autopilot = null;
const CONFIDENCE = { HIGH: ['Based on 5+ days', 'badge-solid'], MEDIUM: ['Based on 3–4 days', ''], LOW: ['Little history', 'badge-grey'], NONE: ['No history yet', 'badge-grey'] };

/** Loads suggestions after the sheet is open, so the owner can start typing straight away. */
async function loadAutopilot() {
  const bar = $('#autopilotBar');
  bar.hidden = true;
  autopilot = null;
  if (!$('#setupList [data-setup-template]')) return;
  try { autopilot = await api(`/stores/${currentStoreId}/autopilot`); } catch { return; }
  if (!$('#setupDialog').open) return;
  const lines = autopilot.lines.filter((l) => $(`#setupList [data-setup-template="${l.templateId}"]`));
  lines.forEach(renderSuggestion);
  const ready = lines.filter((l) => l.quantity != null).length;
  $('#autopilotText').textContent = ready
    ? `Suggestions for ${plural(ready, 'product', 'products')}, from your last 4 weeks. You still check and confirm every number.`
    : 'Not enough history yet. Suggestions appear after a few days of offers.';
  $('#autopilotFill').hidden = !ready;
  bar.hidden = !lines.length;
}

function renderSuggestion(l) {
  const row = $(`#setupList [data-setup-template="${l.templateId}"]`);
  $('.autopilot-hint', row)?.remove();
  const [label, cls] = CONFIDENCE[l.confidence];
  const price = l.suggestedPrice
    ? `<button type="button" class="price-chip" data-apply-price data-price="${l.suggestedPrice}" aria-pressed="false">Try ${money(l.suggestedPrice)}</button>` : '';
  row.insertAdjacentHTML('beforeend', `<div class="autopilot-hint">${icon('spark')}
    <p>${l.quantity != null ? `<b>Suggested: ${l.quantity}</b> · ` : ''}${l.reasons.map(esc).join(' ')}</p>
    <span class="badge plain ${cls}">${label}</span>${price}</div>`);
}

/** Fills quantities and pickup times; prices are only changed by tapping a price suggestion. */
function fillSuggestions() {
  let n = 0;
  for (const l of autopilot?.lines ?? []) {
    const row = $(`#setupList [data-setup-template="${l.templateId}"]`);
    if (!row || l.quantity == null) continue;
    $(`#qty-${l.templateId}`).value = l.quantity;
    $(`#free-${l.templateId}`).value = l.payItForward || '';
    if (hhmm(l.pickupEnd) > nowInStore()) {
      $('[name=start]', row).value = hhmm(l.pickupStart);
      $('[name=end]', row).value = hhmm(l.pickupEnd);
    }
    n++;
  }
  recalcSetup();
  toast(`${plural(n, 'suggestion', 'suggestions')} filled in. Check them against your shelves, then confirm.`);
}

function togglePrice(chip) {
  const on = chip.getAttribute('aria-pressed') !== 'true';
  chip.setAttribute('aria-pressed', String(on));
  chip.textContent = on ? `Using ${money(chip.dataset.price)} today` : `Try ${money(chip.dataset.price)}`;
  recalcSetup();
}

// Rescue Drops

let dropMode = 'product';
let bundlePriceTouched = false;
let bundleBagsTouched = false;
const intOf = (sel) => Number($(sel).value);

function setDropMode(mode) {
  dropMode = mode;
  for (const tab of $$('#dropDialog [role=tab]')) {
    const on = tab.dataset.dropMode === mode;
    tab.setAttribute('aria-selected', String(on));
    tab.tabIndex = on ? 0 : -1;
  }
  $('#dropProduct').hidden = mode !== 'product';
  $('#dropBundle').hidden = mode !== 'bundle';
  setAlert('#dropAlert', '');
  updateDropSummary();
}

async function openDrop(mode) {
  if (!today) return;
  setAlert('#dropAlert', '');
  $('#dropConfirm').checked = false;
  $('#dropAlertCustomers').checked = true;
  $('#dropTemplate').innerHTML = storeTemplates.map((t) => `<option value="${t.id}">${esc(t.name)}</option>`).join('');
  $('#dropTemplate').disabled = !storeTemplates.length;
  $('#dropQty').value = 3;
  $('#dropFree').value = 0;
  fillDropPrice();
  renderBundleRows();
  setDropMode(mode === 'bundle' || !storeTemplates.length ? 'bundle' : 'product');
  $('#dropAudience').textContent = '';
  openDialog($('#dropDialog'));
  try {
    const { customers } = await api(`/stores/${currentStoreId}/drops/audience`);
    $('#dropAudience').textContent = customers ? `(${plural(customers, 'person has', 'people have')} alerts on near you)` : '(nobody near you has alerts on yet)';
  } catch { /* the number is only informative */ }
}

function fillDropPrice() {
  const t = storeTemplates.find((x) => x.id === Number($('#dropTemplate').value));
  $('#dropPrice').value = t ? Number(t.discountedPrice).toFixed(2) : '';
  $('#dropPriceHint').textContent = t ? `Usual price ${money(t.discountedPrice)}, full price ${money(t.originalPrice)}. A lower price moves stock faster.` : 'Save a product first, or bundle leftovers instead.';
}

/** Leftovers of today's offers that can go into surprise bags, pre-filled from the server's proposal. */
function renderBundleRows() {
  const proposal = new Map((today.bundleSuggestion?.components ?? []).map((c) => [c.offerId, c.units]));
  const offers = storeItems.filter((o) => !o.rescueDrop && o.quantityAvailable > 0 && (o.state === 'ACTIVE' || o.state === 'PAUSED'));
  $('#bundleList').innerHTML = offers.length ? offers.map((o) => `
    <div class="bundle-row" data-bundle-offer="${o.id}">
      <div><strong>${esc(o.name)}</strong><span>${o.quantityAvailable} left · ${money(o.originalPrice)} full price · ends ${storeClock(o.expiresAt)}</span></div>
      <div class="stepper sm"><button type="button" data-bump="-1" data-target="bundle-${o.id}" aria-label="One less ${esc(o.name)}">${icon('minus')}</button><input id="bundle-${o.id}" type="number" inputmode="numeric" min="0" max="${o.quantityAvailable}" step="1" value="${proposal.get(o.id) ?? 0}" aria-label="Units of ${esc(o.name)} for the bags"><button type="button" data-bump="1" data-target="bundle-${o.id}" aria-label="One more ${esc(o.name)}">${icon('plus')}</button></div>
    </div>`).join('')
    : '<p class="bundle-empty">No leftovers to bundle: none of today\'s offers has units left.</p>';
  const s = today.bundleSuggestion;
  $('#bundleBags').value = s?.bags ?? 1;
  $('#bundlePrice').value = s ? Number(s.price).toFixed(2) : '';
  $('#bundleName').value = '';
  bundlePriceTouched = false;
  bundleBagsTouched = false;
  updateBundle();
}

function bundleComponents() {
  return $$('#bundleList [data-bundle-offer]').map((row) => ({ offerId: Number(row.dataset.bundleOffer), units: intOf(`#bundle-${row.dataset.bundleOffer}`) }))
    .filter((c) => c.units > 0);
}

/** Same rule as the server's proposal: about 30% of the full value, at most 80% of the discounted value. */
function updateBundle() {
  let units = 0, value = 0, discounted = 0;
  for (const c of bundleComponents()) {
    const o = storeItems.find((x) => x.id === c.offerId);
    units += c.units; value += c.units * o.originalPrice; discounted += c.units * o.discountedPrice;
  }
  for (const row of $$('#bundleList [data-bundle-offer]')) row.classList.toggle('is-selected', intOf(`#bundle-${row.dataset.bundleOffer}`) > 0);
  // The bag count follows the units (about 3 per bag) until the owner sets it, and never exceeds them.
  if (!bundleBagsTouched && units) $('#bundleBags').value = Math.max(1, Math.floor(units / 3));
  if (units && intOf('#bundleBags') > units) $('#bundleBags').value = units;
  $('#bundleBags').max = Math.max(1, Math.min(100, units));
  const bags = Math.max(1, intOf('#bundleBags') || 1);
  const bagValue = value / bags;
  if (!bundlePriceTouched && units) {
    const price = Math.floor(Math.min(bagValue * 0.3, (discounted / bags) * 0.8) * 2) / 2;
    if (price > 0) $('#bundlePrice').value = price.toFixed(2);
  }
  $('#bundleValue').textContent = units
    ? `${plural(units, 'unit', 'units')} in ${plural(bags, 'bag', 'bags')}: about ${Math.round(units / bags * 10) / 10} per bag, worth ${money(Math.floor(bagValue * 100) / 100)} at full price.`
    : 'Add units from today\'s offers above.';
  updateDropSummary();
}

function updateDropSummary() {
  const minutes = Number($('input[name=dropMinutes]:checked')?.value || 60);
  const span = minutes < 60 ? `${minutes} min` : `${minutes / 60} ${minutes === 60 ? 'hour' : 'hours'}`;
  let text = '';
  if (dropMode === 'product') {
    const t = storeTemplates.find((x) => x.id === Number($('#dropTemplate').value));
    const qty = intOf('#dropQty') || 0, free = intOf('#dropFree') || 0;
    if (t && qty + free > 0) text = `${qty} × ${t.name}${qty ? ` at ${money($('#dropPrice').value || 0)}` : ''}${free ? ` + ${free} free` : ''} for ${span}`;
  } else {
    const units = bundleComponents().reduce((n, c) => n + c.units, 0);
    if (units) text = `${plural(intOf('#bundleBags') || 1, 'bag', 'bags')} at ${money($('#bundlePrice').value || 0)} for ${span}`;
  }
  $('#dropSummary').textContent = text || 'Choose what to drop.';
}

function dropProblem(message, focus) {
  setAlert('#dropAlert', message);
  $('#dropAlert').scrollIntoView({ block: 'nearest' });
  focus?.focus();
}

async function submitDrop(event) {
  event.preventDefault();
  setAlert('#dropAlert', '');
  const durationMinutes = Number($('input[name=dropMinutes]:checked').value);
  const alertCustomers = $('#dropAlertCustomers').checked;
  let path, body;
  if (dropMode === 'product') {
    const quantityAvailable = intOf('#dropQty'), payItForwardQuantity = intOf('#dropFree'), price = Number($('#dropPrice').value);
    if (!storeTemplates.length) return dropProblem('Save a product first, or bundle leftovers instead.');
    if (![quantityAvailable, payItForwardQuantity].every((n) => Number.isInteger(n) && n >= 0) || quantityAvailable + payItForwardQuantity < 1) {
      return dropProblem('Enter how many units are on the shelf now.', $('#dropQty'));
    }
    if (quantityAvailable && !(price > 0)) return dropProblem('Enter a drop price above 0.', $('#dropPrice'));
    path = 'drops';
    body = { templateId: Number($('#dropTemplate').value), quantityAvailable, payItForwardQuantity, discountedPrice: price || null, durationMinutes, alertCustomers };
  } else {
    const components = bundleComponents(), bags = intOf('#bundleBags'), price = Number($('#bundlePrice').value);
    if (!components.length) return dropProblem('Add at least one unit from today\'s offers.');
    if (!Number.isInteger(bags) || bags < 1) return dropProblem('Enter how many bags to make.', $('#bundleBags'));
    if (!(price > 0)) return dropProblem('Enter a price per bag above 0.', $('#bundlePrice'));
    path = 'drops/bundle';
    body = { components, bags, price, durationMinutes, name: $('#bundleName').value.trim() || null, alertCustomers };
  }
  if (!$('#dropConfirm').checked) return dropProblem('Please confirm these units are on the shelf now.', $('#dropConfirm'));
  await withLoading($('#dropSubmit'), async () => {
    try {
      const result = await api(`/stores/${currentStoreId}/${path}`, { method: 'POST', body });
      $('#dropDialog').close();
      const reach = !alertCustomers ? '' : result.notified ? ` ${plural(result.notified, 'customer', 'customers')} alerted.` : ' Nobody nearby was due an alert.';
      toast(`Rescue Drop is live until ${storeClock(result.offer.expiresAt)}.${reach}`);
      loadStoreDetails();
    } catch (e) { dropProblem(e.message); }
  });
}

// ---------- store owner: Smart Analytics (Insights tab) ----------

let insightDays = 28;
let insights = null;
const COLLECTED = '#16855a', LEFTOVER = '#d99a1e'; // validated pair: legend + table view cover the amber's contrast
const kgText = (kg) => `${kg >= 10 ? Math.round(kg) : kg.toLocaleString(undefined, { maximumFractionDigits: 1 })} kg`;
const pct = (n) => (n == null ? '—' : `${n}%`);
const dayLabel = (iso, opts = { day: 'numeric', month: 'short' }) => new Date(iso + 'T12:00:00').toLocaleDateString(undefined, opts);

function setStoreTab(tab) {
  for (const t of $$('.store-tabs [role=tab]')) {
    const on = t.dataset.storeTab === tab;
    t.setAttribute('aria-selected', String(on));
    t.tabIndex = on ? 0 : -1;
  }
  $('#todayPanel').hidden = tab !== 'today';
  $('#insightsPanel').hidden = tab !== 'insights';
  if (tab === 'insights') loadInsights();
}

async function loadInsights() {
  const body = $('#insightsBody');
  body.setAttribute('aria-busy', 'true');
  if (!insights) body.innerHTML = '<div class="skeleton" style="height:120px"></div><div class="skeleton" style="height:260px;margin-top:16px"></div>';
  try {
    insights = await api(`/stores/${currentStoreId}/insights?days=${insightDays}`);
    renderInsights(insights);
  } catch (e) {
    body.innerHTML = `<div class="empty"><div class="empty-icon">${icon('alert')}</div><h3>Couldn't load your insights</h3><p>${esc(e.message)}</p>
      <button class="btn" type="button" data-retry-insights>${icon('refresh')}<span>Try again</span></button></div>`;
  } finally { body.setAttribute('aria-busy', 'false'); }
}

function renderInsights(d) {
  const t = d.totals;
  $('#insightsNote').textContent = `Offers that ended ${dayLabel(d.from)} – ${dayLabel(d.to)}`
    + (d.timeZone !== BROWSER_TZ ? ` · days in ${d.timeZone.replace(/_/g, ' ')}` : '');
  if (!t.offers) {
    $('#insightsBody').innerHTML = `<div class="empty"><div class="empty-icon">${icon('spark')}</div><h3>No finished offers in this period</h3>
      <p>Insights appear once your offers have ended. The forecast needs a few days of history.</p></div>` + forecastCard(d.forecast);
    return;
  }
  const tile = (label, value, extra = '', accent = false) => `<div class="card stat${accent ? ' is-accent' : ''}"><span>${label}</span><strong>${value}${extra}</strong></div>`;
  const kg = t.kgRescued != null ? `<small>${kgText(t.kgRescued)}${t.weightCoverage < 100 ? '*' : ''}</small>` : '';
  $('#insightsBody').innerHTML = `
    <div class="stats insight-stats">
      ${tile('Food rescued', t.collected, kg, true)}
      ${tile('Collected of offered', pct(t.sellThroughPercent))}
      ${tile('Left over', t.leftover, '<small>units</small>')}
      ${tile('Not collected', pct(t.noShowPercent), t.noShows ? `<small>${t.noShows} units</small>` : '')}
      ${tile('Takings', money(t.takings))}
      ${tile('Customers saved', money(t.customerSavings))}
    </div>
    ${t.kgRescued != null && t.weightCoverage < 100 ? `<p class="insights-note">* Weight known for ${t.weightCoverage}% of collected units. Add weights to your products for the full figure.</p>` : ''}
    ${recommendationsCard(d.recommendations)}
    ${chartCard(d.daily)}
    ${forecastCard(d.forecast)}
    ${productsCard(d.products)}
    <div class="insight-grid">${timingCard(d.timing)}${dropsCard(d.drops, d.audience)}</div>`;
  wireChart();
}

function recommendationsCard(recs) {
  if (!recs.length) return '';
  const action = (r) => ({
    SETUP: `<button class="btn btn-sm" type="button" data-insight-action="SETUP">Set up today's offers</button>`,
    EDIT_PRODUCT: `<button class="btn btn-sm" type="button" data-insight-action="EDIT_PRODUCT" data-template="${r.templateId ?? ''}">Edit product</button>`,
    REMINDER: `<button class="btn btn-sm" type="button" data-insight-action="REMINDER">Daily reminder</button>`,
  }[r.action] ?? '');
  return `<section class="card panel insight-card" aria-labelledby="recsTitle">
    <div class="panel-head"><div><h2 id="recsTitle">What to try next</h2><p>Based on your own results. Nothing changes until you decide.</p></div></div>
    <ul class="recs">${recs.map((r) => `<li class="rec">${icon('spark')}<div><strong>${esc(r.title)}</strong><p>${esc(r.evidence)}</p><p class="muted">${esc(r.advice)}</p></div>${action(r)}</li>`).join('')}</ul>
  </section>`;
}

/** Collected (bottom) and left over (top) per day: stacked bars, one axis, hover and a table view. */
function chartCard(days) {
  // Drawn at the card's real width, so text keeps its size on phones.
  const W = Math.round(Math.max(300, Math.min(900, ($('#insightsBody').clientWidth || 720) - 48))), H = 220, L = 34, B = 24, T = 10;
  const max = Math.max(1, ...days.map((d) => d.collected + d.leftover));
  const nice = max <= 4 ? 4 : Math.ceil(max / 2) * 2; // even, so the middle gridline is a whole number
  const step = (W - L) / days.length, bar = Math.max(2, Math.min(28, step * 0.7));
  const y = (v) => H - B - (v / nice) * (H - B - T);
  const top = (x, yTop, w, h) => { // rounded top corners, square at the baseline
    const r = Math.min(4, w / 2, h);
    return `M${x},${yTop + h}V${yTop + r}Q${x},${yTop} ${x + r},${yTop}H${x + w - r}Q${x + w},${yTop} ${x + w},${yTop + r}V${yTop + h}Z`;
  };
  let marks = '', hits = '';
  days.forEach((d, i) => {
    const x = L + i * step + (step - bar) / 2;
    const yc = y(d.collected), yl = y(d.collected + d.leftover);
    if (d.leftover > 0) {
      const h = yc - yl - (d.collected > 0 ? 2 : 0);
      if (h > 0.5) marks += `<path d="${top(x, yl, bar, h)}" fill="${LEFTOVER}"/>`;
    }
    if (d.collected > 0) marks += d.leftover > 0
      ? `<rect x="${x}" y="${yc}" width="${bar}" height="${H - B - yc}" fill="${COLLECTED}"/>`
      : `<path d="${top(x, yc, bar, H - B - yc)}" fill="${COLLECTED}"/>`;
    hits += `<rect class="hit" x="${L + i * step}" y="${T}" width="${step}" height="${H - B - T}" data-tip="${esc(dayLabel(d.date, { weekday: 'short', day: 'numeric', month: 'short' }))}|${d.offered}|${d.collected}|${d.leftover}"/>`;
  });
  const grid = [0, nice / 2, nice].map((v) => `<line x1="${L}" x2="${W}" y1="${y(v)}" y2="${y(v)}"/><text x="${L - 6}" y="${y(v) + 4}" text-anchor="end">${v}</text>`).join('');
  const xs = [0, Math.floor((days.length - 1) / 2), days.length - 1].filter((v, i, a) => a.indexOf(v) === i)
    .map((i) => `<text x="${L + i * step + step / 2}" y="${H - 6}" text-anchor="middle">${esc(dayLabel(days[i].date))}</text>`).join('');
  const total = days.reduce((a, d) => [a[0] + d.collected, a[1] + d.leftover], [0, 0]);
  return `<section class="card panel insight-card" aria-labelledby="chartTitle">
    <div class="panel-head"><div><h2 id="chartTitle">Food per day</h2><p>Units collected and left over, by the day the offer ended.</p></div>
      <div class="legend" aria-hidden="true"><span><i style="background:${COLLECTED}"></i>Collected</span><span><i style="background:${LEFTOVER}"></i>Left over</span></div></div>
    <div class="chart-wrap">
      <svg class="chart" viewBox="0 0 ${W} ${H}" role="img" aria-label="Per day: ${total[0]} units collected and ${total[1]} left over in total. The table below has every day.">
        <g class="grid">${grid}</g><g>${marks}</g><g class="xlabels">${xs}</g><g class="hits">${hits}</g>
      </svg>
      <div class="chart-tip" id="chartTip" hidden></div>
    </div>
    <details class="table-view"><summary>Show as a table</summary>
      <table><thead><tr><th scope="col">Day</th><th scope="col">Offered</th><th scope="col">Collected</th><th scope="col">Left over</th></tr></thead>
      <tbody>${days.map((d) => `<tr><th scope="row">${esc(dayLabel(d.date, { weekday: 'short', day: 'numeric', month: 'short' }))}</th><td>${d.offered}</td><td>${d.collected}</td><td>${d.leftover}</td></tr>`).join('')}</tbody></table>
    </details>
  </section>`;
}

function wireChart() {
  const wrap = $('#insightsBody .chart-wrap');
  if (!wrap) return;
  const tip = $('#chartTip');
  const show = (e) => {
    const hit = e.target.closest?.('[data-tip]');
    if (!hit) { tip.hidden = true; return; }
    const [label, offered, collected, leftover] = hit.dataset.tip.split('|');
    tip.innerHTML = `<strong>${label}</strong><span><i style="background:${COLLECTED}"></i>Collected <b>${collected}</b></span>`
      + `<span><i style="background:${LEFTOVER}"></i>Left over <b>${leftover}</b></span><span class="muted">Offered ${offered}</span>`;
    tip.hidden = false;
    const box = wrap.getBoundingClientRect(), r = hit.getBoundingClientRect();
    const x = Math.min(Math.max(r.left + r.width / 2 - box.left, 70), box.width - 70);
    tip.style.left = `${x}px`;
    for (const h of $$('.hits rect', wrap)) h.classList.toggle('is-on', h === hit);
  };
  wrap.addEventListener('pointermove', show);
  wrap.addEventListener('pointerdown', show);
  wrap.addEventListener('pointerleave', () => { tip.hidden = true; for (const h of $$('.hits rect', wrap)) h.classList.remove('is-on'); });
}

function forecastCard(lines) {
  if (!lines?.length) return '';
  const ready = lines.find((l) => l.days.length);
  const head = ready ? ready.days.map((d) => `<th scope="col">${esc(dayLabel(d.date, { weekday: 'short' }))}<span>${new Date(d.date + 'T12:00:00').getDate()}</span></th>`).join('') : '';
  const row = (l) => {
    const name = `<th scope="row"><strong>${esc(l.name)}</strong><span>${l.days.length
      ? `${l.backtestDays ? `Right on ${l.backtestHits} of the last ${l.backtestDays} days` : 'New forecast'}${l.oftenSoldOut ? ' · often sold out, demand may be higher' : ''}`
      : `Not enough history yet (${l.historyDays} ${l.historyDays === 1 ? 'day' : 'days'})`}</span></th>`;
    return `<tr>${name}${l.days.length ? l.days.map((d) => `<td><b>${d.expected}</b><span>${d.low}–${d.high}</span></td>`).join('') : (ready ? `<td colspan="7" class="muted">—</td>` : '')}</tr>`;
  };
  return `<section class="card panel insight-card" aria-labelledby="forecastTitle">
    <div class="panel-head"><div><h2 id="forecastTitle">Expected demand, next 7 days</h2><p>Units customers are likely to reserve, with a likely range. Based on the last 8 weeks, weighted to the same weekday.</p></div></div>
    <div class="table-scroll"><table class="forecast"><thead><tr><th scope="col">Product</th>${head}</tr></thead><tbody>${lines.map(row).join('')}</tbody></table></div>
  </section>`;
}

function productsCard(products) {
  if (!products.length) return '';
  return `<section class="card panel insight-card" aria-labelledby="productsTitle">
    <div class="panel-head"><div><h2 id="productsTitle">By product</h2><p>Rescue Drops are counted separately below.</p></div></div>
    <div class="table-scroll"><table class="data-table"><thead><tr><th scope="col">Product</th><th scope="col">Days</th><th scope="col">Offered</th><th scope="col">Collected</th><th scope="col">Left over</th><th scope="col">Sold out</th><th scope="col">Not collected</th></tr></thead>
    <tbody>${products.map((p) => `<tr><th scope="row">${esc(p.name)}${p.flag ? `<span class="badge badge-sun plain">${esc(p.flag)}</span>` : ''}</th>
      <td>${p.days}</td><td>${p.offered}</td><td>${p.collected} <span class="muted">${pct(p.sellThroughPercent)}</span></td><td>${p.leftover}</td>
      <td>${p.soldOutDays} ${p.soldOutDays === 1 ? 'day' : 'days'}</td><td>${pct(p.noShowPercent)}</td></tr>`).join('')}</tbody></table></div>
  </section>`;
}

function timingCard(t) {
  const total = t.pickupsByQuarter.reduce((a, b) => a + b, 0);
  const labels = ['First quarter', 'Second quarter', 'Third quarter', 'Last quarter'];
  const hours = t.reservationsByHour;
  const sum = hours.reduce((a, b) => a + b, 0);
  let best = 0;
  for (let h = 1; h < 23; h++) if (hours[h] + hours[h + 1] > hours[best] + hours[best + 1]) best = h;
  return `<section class="card panel insight-card" aria-labelledby="timingTitle">
    <div class="panel-head"><div><h2 id="timingTitle">When customers come</h2><p>${total ? `${total} pickups with a pickup window.` : 'No pickups with a pickup window yet.'}</p></div></div>
    ${total ? `<div class="hbars">${t.pickupsByQuarter.map((n, i) => {
      const share = Math.round((100 * n) / total);
      return `<div class="hbar"><span>${labels[i]}</span><div class="hbar-track"><i style="width:${share}%"></i></div><b>${share}%</b></div>`;
    }).join('')}</div>` : ''}
    ${sum ? `<p class="muted small">Most reservations are made between ${String(best).padStart(2, '0')}:00 and ${String(best + 2).padStart(2, '0')}:00.</p>` : ''}
  </section>`;
}

function dropsCard(d, a) {
  const count = (n) => (n == null ? 'fewer than 5' : n);
  return `<section class="card panel insight-card" aria-labelledby="dropsTitle">
    <div class="panel-head"><div><h2 id="dropsTitle">Rescue Drops and audience</h2></div></div>
    <dl class="facts">
      <div><dt>Drops launched</dt><dd>${d.launched}</dd></div>
      ${d.launched ? `<div><dt>Collected</dt><dd>${d.collected} of ${d.offered} <span class="muted">${pct(d.sellThroughPercent)}</span></dd></div>
      <div><dt>Typical time to sell out</dt><dd>${d.minutesToSellOut == null ? '—' : `${d.minutesToSellOut} min`}</dd></div>
      <div><dt>Alerted customers who reserved</dt><dd>${d.alerted ? `${d.alertedWhoReserved} of ${d.alerted}` : '—'}</dd></div>` : ''}
      <div><dt>Follow your shop</dt><dd>${count(a.followers)}</dd></div>
      <div><dt>Have a favourite product here</dt><dd>${count(a.favouriteFans)}</dd></div>
      <div><dt>Get drop alerts near you</dt><dd>${count(a.dropAlertReach)}</dd></div>
    </dl>
    <p class="muted small">Audience numbers below 5 are hidden to protect customers' privacy.</p>
  </section>`;
}

function insightAction(button) {
  const a = button.dataset.insightAction;
  if (a === 'SETUP') { setStoreTab('today'); openSetup(); }
  else if (a === 'EDIT_PRODUCT') {
    const product = storeTemplates.find((t) => t.id === Number(button.dataset.template));
    if (product) openTemplateEditor(product);
    else { setStoreTab('today'); $('#templateList').scrollIntoView({ block: 'start' }); }
  }
  else if (a === 'REMINDER') { setStoreTab('today'); $('#reminderOn').scrollIntoView({ block: 'center' }); $('#reminderOn').focus(); }
}

async function exportCsv(button) {
  await withLoading(button, async () => {
    try {
      const res = await fetch(`${API}/stores/${currentStoreId}/insights/export.csv?days=${insightDays}`, { headers: { Authorization: `Bearer ${token}` } });
      if (!res.ok) throw new Error('The export failed. Please try again.');
      const url = URL.createObjectURL(await res.blob());
      const link = Object.assign(document.createElement('a'), { href: url, download: `food-rescue-${insightDays}-days.csv` });
      document.body.append(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (e) { fail(e); }
  });
}

// ---------- store owner: adjusting, pausing and publishing offers ----------

let adjusting = null;

function openOfferEditor(offer) {
  if (!offer) return;
  if (!offer.templateId) { openItemEditor(offer); return; } // one-off offers use the full editor
  adjusting = offer;
  const f = $('#offerForm');
  clearForm(f, '#offerAlert');
  $('#offerSub').textContent = `${offer.name} · times in your store's time zone`;
  f.quantityAvailable.value = offer.quantityAvailable;
  f.payItForwardQuantity.value = offer.payItForwardQuantity;
  f.pickupStart.value = offer.pickupStartsAt ? storeClock(offer.pickupStartsAt) : nowInStore();
  f.pickupEnd.value = storeClock(offer.expiresAt);
  openDialog($('#offerDialog'));
}

async function saveOffer(event) {
  event.preventDefault();
  const f = event.target;
  setAlert('#offerAlert', '');
  if (!validateForm(f)) return;
  if (f.pickupEnd.value <= f.pickupStart.value) { showFieldError(f.pickupEnd, 'Must be after the start time'); f.pickupEnd.focus(); return; }
  await withLoading($('#offerSubmit'), async () => {
    try {
      await api(`/stores/${currentStoreId}/offers/${adjusting.id}`, { method: 'PATCH', body: {
        quantityAvailable: Number(f.quantityAvailable.value), payItForwardQuantity: Number(f.payItForwardQuantity.value),
        pickupStart: f.pickupStart.value, pickupEnd: f.pickupEnd.value } });
      $('#offerDialog').close();
      toast('Offer updated');
      loadStoreDetails();
    } catch (e) { setAlert('#offerAlert', e.message); }
  });
}

async function setOfferStatus(id, status, button) {
  const before = storeItems.find((o) => o.id === id);
  await withLoading(button, async () => {
    try {
      const o = await api(`/stores/${currentStoreId}/offers/${id}/status`, { method: 'PUT', body: { status } });
      toast(status === 'PAUSED' ? `“${o.name}” is paused and hidden from customers`
        : before?.state === 'DRAFT' ? `“${o.name}” is published` : `“${o.name}” is on offer again`);
      loadStoreDetails();
    } catch (e) { fail(e); }
  });
}

// ---------- store owner: saved products ----------

let editingTemplate = null;

function openTemplateEditor(t) {
  $('#setupDialog').open && $('#setupDialog').close();
  const f = $('#templateForm');
  clearForm(f, '#templateAlert');
  editingTemplate = t || null;
  f.editId.value = t?.id ?? '';
  if (t) {
    f.name.value = t.name;
    f.description.value = t.description ?? '';
    f.originalPrice.value = t.originalPrice;
    f.discountedPrice.value = t.discountedPrice;
    f.pickupStart.value = hhmm(t.pickupStart);
    f.pickupEnd.value = hhmm(t.pickupEnd);
    f.kind.value = t.kind;
  }
  tagChips($('#tplTags'), t?.tags ?? [], 'tplTag');
  f.unitWeightKg.value = t?.unitWeightGrams ? t.unitWeightGrams / 1000 : '';
  $('#templateTitle').textContent = t ? 'Edit product' : 'New product';
  tplPhoto.reset(t?.photoUrl);
  updateDiscountPreview(f, $('#tplDiscountPreview'));
  openDialog($('#templateDialog'));
}

async function saveTemplate(event) {
  event.preventDefault();
  const f = event.target;
  setAlert('#templateAlert', '');
  updateDiscountPreview(f, $('#tplDiscountPreview'));
  if (!validateForm(f)) return;
  if (f.pickupEnd.value <= f.pickupStart.value) { showFieldError(f.pickupEnd, 'Must be after the start time'); f.pickupEnd.focus(); return; }
  const body = {
    name: f.name.value.trim(), description: f.description.value.trim() || null, kind: f.kind.value,
    originalPrice: Number(f.originalPrice.value), discountedPrice: Number(f.discountedPrice.value),
    pickupStart: f.pickupStart.value, pickupEnd: f.pickupEnd.value, tags: checkedTags($('#tplTags')),
    unitWeightGrams: f.unitWeightKg.value ? Math.round(Number(f.unitWeightKg.value) * 1000) : null,
  };
  const photo = tplPhoto.file();
  await withLoading($('#templateSubmit'), async () => {
    let saved;
    try {
      const id = f.editId.value;
      saved = id
        ? await api(`/stores/${currentStoreId}/templates/${id}`, { method: 'PUT', body })
        : await api(`/stores/${currentStoreId}/templates`, { method: 'POST', body });
    } catch (e) { setAlert('#templateAlert', e.message); return; }
    if (photo) {
      try {
        await uploadPhoto(`/stores/${currentStoreId}/templates/${saved.id}/photo`, photo);
      } catch (e) {
        f.editId.value = saved.id;
        editingTemplate = saved;
        tplPhoto.error('The product was saved, but the photo was not accepted: ' + e.message);
        loadStoreDetails();
        return;
      }
    }
    $('#templateDialog').close();
    toast(f.editId.value ? 'Product saved' : `“${saved.name}” saved. Use it when you set up today's offers.`);
    loadStoreDetails();
  });
}

async function removeTemplatePhoto(button) {
  if (!editingTemplate) return;
  const ok = await confirmDialog({ title: 'Remove this photo?', body: 'Offers you publish from now on will have no photo. Today\'s offers keep theirs.', confirmLabel: 'Remove photo', danger: true });
  if (!ok) return;
  await withLoading(button, async () => {
    try {
      await api(`/stores/${currentStoreId}/templates/${editingTemplate.id}/photo`, { method: 'DELETE' });
      tplPhoto.setCurrent(null);
      toast('Photo removed');
      loadStoreDetails();
    } catch (e) { tplPhoto.error(e.message); }
  });
}

async function archiveTemplate(id) {
  const t = storeTemplates.find((x) => x.id === id);
  const ok = await confirmDialog({
    title: `Remove “${t?.name ?? 'this product'}” from your products?`,
    body: 'It will no longer appear when you set up a day. Offers already published stay as they are.',
    confirmLabel: 'Remove product', danger: true,
  });
  if (!ok) return;
  try {
    await api(`/stores/${currentStoreId}/templates/${id}`, { method: 'DELETE' });
    toast('Product removed');
    loadStoreDetails();
  } catch (e) { fail(e); }
}

// ---------- store owner: daily reminder ----------

async function saveReminder(event) {
  event.preventDefault();
  const enabled = $('#reminderOn').checked;
  const time = $('#reminderTime').value;
  if (enabled && !time) { toast('Choose a time for the reminder', 'error'); $('#reminderTime').focus(); return; }
  await withLoading($('[type=submit]', event.target), async () => {
    try {
      today.reminder = await api(`/stores/${currentStoreId}/reminder`, { method: 'PUT', body: { enabled, time: enabled ? time : null } });
      renderReminder();
      toast(enabled ? `We'll remind you here after ${time} on days without offers` : 'Daily reminder turned off');
    } catch (e) { fail(e); }
  });
}

async function dismissReminder(button) {
  await withLoading(button, async () => {
    try {
      await api(`/stores/${currentStoreId}/reminder/dismiss`, { method: 'POST' });
      $('#reminderBanner').hidden = true;
      toast('Reminder hidden until tomorrow');
    } catch (e) { fail(e); }
  });
}

// ---------- administrator ----------

let adminRequests = [];
let adminStores = [];

async function loadAdmin() {
  try {
    [adminRequests, adminStores] = await Promise.all([api('/admin/eligibility-requests'), api('/admin/stores')]);
  } catch (e) { fail(e); return; }
  const active = adminStores.filter((s) => s.active).length;
  const stat = (label, value, accent) => `<div class="card stat${accent ? ' is-accent' : ''}"><span>${label}</span><strong>${value}</strong></div>`;
  $('#adminStats').innerHTML = stat('Waiting for a decision', adminRequests.length, true) + stat('Stores', adminStores.length)
    + stat('Active stores', active) + stat('Suspended stores', adminStores.length - active);
  $('#requestCount').textContent = adminRequests.length;
  loadAdminInsights();
  $('#storeCount').textContent = adminStores.length;

  $('#eligibilityRequests').innerHTML = adminRequests.length ? adminRequests.map((u) => `
    <div class="person-row">
      <span class="avatar" aria-hidden="true">${esc(initials(u.fullName))}</span>
      <div class="row-text"><strong>${esc(u.fullName)}</strong><span>${esc(u.email)} · member since ${new Date(u.createdAt).toLocaleDateString(undefined, { day: 'numeric', month: 'long', year: 'numeric' })}</span></div>
      <div class="row-actions">
        <button class="btn btn-sm btn-danger" type="button" data-reject="${u.id}">Reject</button>
        <button class="btn btn-sm btn-primary" type="button" data-approve="${u.id}">${icon('check')}<span>Approve</span></button>
      </div>
    </div>`).join('')
    : `<div class="empty"><div class="empty-icon">${icon('check')}</div><h3>All caught up</h3><p>No verification requests are waiting.</p></div>`;

  $('#adminStores').innerHTML = adminStores.length ? adminStores.map((s) => `
    <div class="store-row">
      <span class="store-icon" aria-hidden="true">${icon('store')}</span>
      <div class="row-text"><strong>${esc(s.name)} ${s.active ? '<span class="badge">Active</span>' : '<span class="badge badge-red">Suspended</span>'}</strong>
        <span>${esc(s.address)}</span><span>${esc(s.ownerName)} · ${esc(s.ownerEmail)}</span></div>
      <div class="row-actions">${s.active
        ? `<button class="btn btn-sm btn-danger" type="button" data-suspend="${s.id}">Suspend</button>`
        : `<button class="btn btn-sm" type="button" data-reactivate="${s.id}">Reactivate</button>`}</div>
    </div>`).join('')
    : `<div class="empty"><div class="empty-icon">${icon('store')}</div><h3>No stores yet</h3><p>Stores appear here once owners register them.</p></div>`;
}

/** Platform figures for the last 28 days, read with JDBC on the server. */
async function loadAdminInsights() {
  const box = $('#adminInsights');
  try {
    const d = await api('/admin/insights');
    const t = d.totals;
    const list = (rows, empty) => rows.length
      ? `<ul class="admin-list">${rows.map((r) => `<li><strong>${esc(r.name)}</strong><span>${esc(r.reason)}</span></li>`).join('')}</ul>`
      : `<p class="muted small">${empty}</p>`;
    box.innerHTML = `<div class="panel-head"><div><h2>Last 4 weeks</h2><p>Offers that ended in the last 28 days, across all stores.</p></div></div>
      <dl class="facts facts-row">
        <div><dt>Food rescued</dt><dd>${t.collected} units${t.kgRescued != null ? ` · ${kgText(t.kgRescued)}` : ''}</dd></div>
        <div><dt>Collected of offered</dt><dd>${pct(t.sellThroughPercent)}</dd></div>
        <div><dt>Not collected</dt><dd>${pct(t.noShowPercent)}</dd></div>
        <div><dt>Donated (Pay-It-Forward)</dt><dd>${t.donated}</dd></div>
        <div><dt>Customers who collected</dt><dd>${d.customers}</dd></div>
        <div><dt>Stores that handed food over</dt><dd>${d.stores}</dd></div>
      </dl>
      <div class="admin-columns">
        <div><h3>Quiet for 7 days</h3>${list(d.quiet, 'Every active store published this week.')}</div>
        <div><h3>Needs attention</h3>${list(d.attention, 'No store has unusual waste or no-shows.')}</div>
      </div>`;
  } catch (e) { box.innerHTML = `<p class="muted">Platform figures could not be loaded: ${esc(e.message)}</p>`; }
}

async function decideEligibility(userId, approved, button) {
  const person = adminRequests.find((u) => u.id === userId);
  if (!approved) {
    const ok = await confirmDialog({
      title: `Reject ${person?.fullName ?? 'this request'}?`,
      body: 'They will not be able to claim free items. They can ask again later.',
      confirmLabel: 'Reject request', danger: true,
    });
    if (!ok) return;
  }
  await withLoading(button, async () => {
    try {
      const user = await api(`/admin/users/${userId}/eligibility`, { method: 'PUT', body: { approved } });
      toast(`${user.fullName} ${approved ? 'can now claim free items' : 'was rejected'}`);
      loadAdmin();
    } catch (e) { fail(e); }
  });
}

async function setStoreActive(storeId, active, button) {
  const store = adminStores.find((s) => s.id === storeId);
  if (!active) {
    const ok = await confirmDialog({
      title: `Suspend ${store?.name ?? 'this store'}?`,
      body: 'Its food disappears from search for customers until you reactivate it. The owner can still sign in and see their dashboard.',
      confirmLabel: 'Suspend store', danger: true,
    });
    if (!ok) return;
  }
  await withLoading(button, async () => {
    try {
      const s = await api(`/admin/stores/${storeId}/status`, { method: 'PUT', body: { active } });
      toast(`${s.name} ${active ? 'is active again' : 'is suspended'}`);
      loadAdmin();
    } catch (e) { fail(e); }
  });
}

// ---------- event wiring ----------

function renderCredits() {
  $('#creditsList').innerHTML = Object.entries(PHOTO_CREDITS).map(([key, [title, author, license, url]]) => `
    <li><img src="photos/${key}-480.webp" alt="" loading="lazy">
      <div><a href="${url}" target="_blank" rel="noopener">${esc(title)}</a><span>${esc(author)} · ${esc(license)}</span></div></li>`).join('');
}

document.addEventListener('click', (e) => {
  // Clicking the dimmed area around a dialog closes it (but not clicks on gaps inside it).
  if (e.target instanceof HTMLDialogElement) {
    const r = e.target.getBoundingClientRect();
    if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) e.target.close();
    return;
  }
  if (!e.target.closest('.place-field')) closeSuggestions();
  const t = e.target.closest('button');
  if (!t) return;
  const d = t.dataset;
  if (d.close !== undefined) t.closest('dialog')?.close();
  else if (d.view) showView(d.view, { focus: true });
  else if (d.demo) { const f = $('#loginForm'); f.email.value = d.demo; f.password.value = 'demo1234'; f.requestSubmit(); }
  else if (d.togglePassword) togglePassword(t);
  else if (d.place) choosePlace($('#placeSuggestions').places[Number(d.place)]);
  else if (d.storePlace) { const p = $('#storeSuggestions').places[Number(d.storePlace)]; storePinMoved = true; placeStorePin(p.latitude, p.longitude); }
  else if (d.reserve) openReserve(Number(d.reserve), 'reserve');
  else if (d.claim) openReserve(Number(d.claim), 'claim');
  else if (d.step) stepQty(Number(d.step));
  else if (d.copy) copyCode(t);
  else if (d.gotoOrders !== undefined) { $('#reserveDialog').close(); showView('orders', { focus: true }); }
  else if (d.scrollTo) scrollToFood(Number(d.scrollTo));
  else if (d.radius) { radiusKm = Number(d.radius); save(PREFS_KEY, { radius: radiusKm }); search(); }
  else if (t.id === 'placeSummary') setSearchOpen($('#placeForm').classList.contains('is-collapsed'));
  else if (d.focusSearch !== undefined || t.id === 'heroSearch') { setSearchOpen(true); $('#placeQuery').focus(); $('#placeForm').scrollIntoView({ block: 'center' }); }
  else if (d.showAll !== undefined) { freeOnly = false; favOnly = false; $('#freeOnly').setAttribute('aria-pressed', 'false'); $('#favOnly').setAttribute('aria-pressed', 'false'); renderNearby(); }
  else if (d.retrySearch !== undefined) search();
  else if (d.cancel) cancelOrder(Number(d.cancel));
  else if (d.edit) openItemEditor(storeItems.find((i) => i.id === Number(d.edit)));
  else if (d.newItem !== undefined) openItemEditor(null);
  else if (d.openStoreForm !== undefined) openStoreForm(null);
  else if (d.delete) deleteItem(Number(d.delete));
  else if (d.setupToday !== undefined) openSetup();
  else if (d.bump) bump(t);
  else if (d.openDrop) openDrop(d.openDrop);
  else if (d.dropMode) setDropMode(d.dropMode);
  else if (d.applyPrice !== undefined) togglePrice(t);
  else if (d.openInbox !== undefined) openInbox(d.openInbox || 'list');
  else if (d.inboxTab) setInboxTab(d.inboxTab);
  else if (d.storeTab) setStoreTab(d.storeTab);
  else if (d.insightAction) insightAction(t);
  else if (d.retryInsights !== undefined) loadInsights();
  else if (d.fav) toggleFavourite(t);
  else if (d.unfav) unfavourite(t);
  else if (d.openAlert) openAlert(inbox.items.find((n) => n.id === Number(d.openAlert)));
  else if (d.adjust) openOfferEditor(storeItems.find((o) => o.id === Number(d.adjust)));
  else if (d.offerStatus) setOfferStatus(Number(t.closest('[data-offer-id]').dataset.offerId), d.offerStatus, t);
  else if (d.newTemplate !== undefined) openTemplateEditor(null);
  else if (d.editTemplate) openTemplateEditor(storeTemplates.find((x) => x.id === Number(d.editTemplate)));
  else if (d.archiveTemplate) archiveTemplate(Number(d.archiveTemplate));
  else if (d.approve) decideEligibility(Number(d.approve), true, t);
  else if (d.reject) decideEligibility(Number(d.reject), false, t);
  else if (d.suspend) setStoreActive(Number(d.suspend), false, t);
  else if (d.reactivate) setStoreActive(Number(d.reactivate), true, t);
  else if (t.id === 'requestEligibility') requestEligibility(t);
  else if (t.id === 'hideEligibility') hideEligibility();
  else if (t.id === 'historyMore') showMoreHistory();
});

// Photos fade in once loaded; a broken image becomes the branded tile instead.
document.addEventListener('load', (e) => {
  if (e.target.tagName === 'IMG' && e.target.closest('.photo-frame')) e.target.classList.add('is-loaded');
}, true);
function replaceBrokenImage(img) {
  const frame = img.closest('.photo-frame');
  if (frame) { frame.querySelector('.photo-tag')?.remove(); img.replaceWith(document.createRange().createContextualFragment(fallbackTile('Photo unavailable'))); }
  else if (img.closest('.hero-photo, .auth-visual')) { img.parentElement.querySelector('.photo-tag')?.remove(); img.remove(); }
}
document.addEventListener('error', (e) => { if (e.target.tagName === 'IMG') replaceBrokenImage(e.target); }, true);
// Images written in index.html can fail before this deferred script runs, so check those too.
for (const img of [...document.images]) if (img.complete && !img.naturalWidth && img.getAttribute('src')) replaceBrokenImage(img);

$('#tabLogin').addEventListener('click', () => setAuthTab('login'));
$('#tabRegister').addEventListener('click', () => setAuthTab('register'));
$('.auth-tabs').addEventListener('keydown', (e) => {
  if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
  const next = $('#tabLogin').getAttribute('aria-selected') === 'true' ? 'register' : 'login';
  setAuthTab(next);
  $(next === 'login' ? '#tabLogin' : '#tabRegister').focus();
});
$('#loginForm').addEventListener('submit', login);
$('#registerForm').addEventListener('submit', register);
// Clear a field's message as soon as the person edits it.
document.addEventListener('input', (e) => {
  if (e.target.matches('.input[aria-invalid="true"]')) showFieldError(e.target, '');
});
$('#logout').addEventListener('click', signOut);

$('#placeForm').addEventListener('submit', searchPlace);
$('#placeForm').addEventListener('keydown', (e) => { if (e.key === 'Escape') closeSuggestions(); });
$('#locate').addEventListener('click', (e) => useMyLocation(e.currentTarget));
$('#heroLocate').addEventListener('click', (e) => useMyLocation(e.currentTarget));
for (const r of $$('input[name="radius"]')) r.addEventListener('change', () => { radiusKm = Number(r.value); save(PREFS_KEY, { radius: radiusKm }); search(); });
$('#sortBy').addEventListener('change', (e) => { sortBy = e.target.value; if (nearbyItems.length) renderNearby(); });
$('#freeOnly').addEventListener('click', (e) => { freeOnly = !freeOnly; e.currentTarget.setAttribute('aria-pressed', String(freeOnly)); renderNearby(); });
$('#mapToggle').addEventListener('click', toggleMap);

$('#reserveDialog').addEventListener('submit', (e) => { if (e.target.id === 'reserveForm') submitReserve(e); });

$('#storeSelect').addEventListener('change', (e) => { currentStoreId = Number(e.target.value); today = null; insights = null; setStoreTab('today'); $('#pickupResult').innerHTML = ''; loadStoreDetails(); });
$('#editStore').addEventListener('click', () => openStoreForm(currentStore()));
$('#addStore').addEventListener('click', () => openStoreForm(null));
$('#storeForm').addEventListener('submit', saveStore);
$('#storeLocate').addEventListener('click', (e) => locateStoreAddress(e.currentTarget));
$('#newItem').addEventListener('click', () => openItemEditor(null));
$('#itemForm').addEventListener('submit', saveItem);
$('#itemForm').addEventListener('input', (e) => { if (e.target.name === 'originalPrice' || e.target.name === 'discountedPrice') updateDiscountPreview(); });
const itemPhoto = photoPicker('item', removeCurrentPhoto);
const tplPhoto = photoPicker('tpl', removeTemplatePhoto);
$('#itemDialog').addEventListener('close', () => itemPhoto.release());
$('#templateDialog').addEventListener('close', () => tplPhoto.release());
$('#newTemplate').addEventListener('click', () => openTemplateEditor(null));
$('#templateForm').addEventListener('submit', saveTemplate);
$('#templateForm').addEventListener('input', (e) => { if (e.target.name === 'originalPrice' || e.target.name === 'discountedPrice') updateDiscountPreview(e.currentTarget, $('#tplDiscountPreview')); });
$('#setupForm').addEventListener('submit', (e) => { e.preventDefault(); submitSetup(true); });
$('#setupDraft').addEventListener('click', () => submitSetup(false));
$('#setupList').addEventListener('input', recalcSetup);
$('#offerForm').addEventListener('submit', saveOffer);
$('#autopilotFill').addEventListener('click', fillSuggestions);
$('#dropForm').addEventListener('submit', submitDrop);
$('#dropForm').addEventListener('input', (e) => {
  if (e.target.id === 'bundlePrice') bundlePriceTouched = true;
  if (e.target.id === 'bundleBags') bundleBagsTouched = true; // typed or stepped by the owner; code sets it silently
  if (e.target.id === 'dropTemplate') fillDropPrice();
  if (dropMode === 'bundle') updateBundle(); else updateDropSummary();
});
$('#dropTemplate').addEventListener('change', () => { fillDropPrice(); updateDropSummary(); });
$('#dropDialog .segmented').addEventListener('keydown', (e) => {
  if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
  setDropMode(dropMode === 'product' ? 'bundle' : 'product');
  $(`#dropDialog [data-drop-mode="${dropMode}"]`).focus();
});
$('#inboxBtn').addEventListener('click', () => openInbox('list'));
$('#exportCsv').addEventListener('click', (e) => exportCsv(e.currentTarget));
for (const r of $$('input[name="insightDays"]')) r.addEventListener('change', () => { insightDays = Number(r.value); loadInsights(); });
$('.store-tabs').addEventListener('keydown', (e) => {
  if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
  const next = $('#storeTabToday').getAttribute('aria-selected') === 'true' ? 'insights' : 'today';
  setStoreTab(next);
  $(`.store-tabs [data-store-tab="${next}"]`).focus();
});
$('#pushEnable').addEventListener('click', (e) => enablePush(e.currentTarget));
$('#pushDisable').addEventListener('click', disablePush);
$('#pushTest').addEventListener('click', (e) => testPush(e.currentTarget));
$('#favOnly').addEventListener('click', (e) => { favOnly = !favOnly; e.currentTarget.setAttribute('aria-pressed', String(favOnly)); renderNearby(); });
$('#inboxDialog .segmented').addEventListener('keydown', (e) => {
  if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
  const next = $('#inboxTabList').getAttribute('aria-selected') === 'true' ? 'settings' : 'list';
  setInboxTab(next);
  $(`#inboxDialog [data-inbox-tab="${next}"]`).focus();
});
$('#alertsForm').addEventListener('submit', saveAlerts);
$('#alertsUseHere').addEventListener('click', () => { alertPlace = { ...location_ }; $('#alertsPlace').textContent = alertPlace.label; toast(`Alerts will use ${location_.label}. Save to keep it.`); });
$('#reminderForm').addEventListener('submit', saveReminder);
$('#dismissReminder').addEventListener('click', (e) => dismissReminder(e.currentTarget));
$('#pickupForm').addEventListener('submit', confirmPickup);
$('#pickupCode').addEventListener('input', (e) => { e.target.value = e.target.value.replace(/\D/g, '').slice(0, 6); });

$('#adminRefresh').addEventListener('click', (e) => withLoading(e.currentTarget, loadAdmin));
$('#openCredits').addEventListener('click', () => { renderCredits(); openDialog($('#creditsDialog')); });

window.addEventListener('scroll', () => $('#siteHeader').classList.toggle('is-scrolled', window.scrollY > 8), { passive: true });

// Keep "in 12 min" countdowns current.
setInterval(() => {
  for (const el of $$('[data-countdown]')) el.textContent = untilText(el.dataset.countdown);
}, 30000);

// While Rescue Drops are on screen, refresh their live availability every 30 seconds.
setInterval(() => {
  if (me?.role !== 'CONSUMER' || currentView !== 'shop' || document.hidden || $('#reserveDialog').open) return;
  if (nearbyItems.some((i) => i.rescueDrop)) search({ quiet: true });
}, 30000);

updateWhere();
api('/settings').then((s) => { maxPerReservation = s.maxQuantityPerReservation; }).catch(() => {});
token = restore(TOKEN_KEY);
if (token) startSession(); else signOut();
