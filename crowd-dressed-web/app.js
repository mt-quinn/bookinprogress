// ── Configuration ────────────────────────────────────────────────────────────
// Fill these in after creating your Supabase project.
const SUPABASE_URL     = 'https://YOUR_PROJECT.supabase.co';
const SUPABASE_ANON_KEY = 'YOUR_ANON_KEY';

// Equipment slots the plugin supports, in display order
const SLOTS = [
  { key: 'HEAD',   label: 'Head'    },
  { key: 'CAPE',   label: 'Cape'    },
  { key: 'AMULET', label: 'Amulet'  },
  { key: 'WEAPON', label: 'Weapon'  },
  { key: 'TORSO',  label: 'Body'    },
  { key: 'SHIELD', label: 'Shield'  },
  { key: 'LEGS',   label: 'Legs'    },
  { key: 'HANDS',  label: 'Gloves'  },
  { key: 'BOOTS',  label: 'Boots'   },
];

// ── State ─────────────────────────────────────────────────────────────────────
let allItems     = [];   // [{id, name, icon}, …] from prices.runescape.wiki
let sessionId    = null;
let sessionCode  = null;
let activeSlot   = SLOTS[0].key;
let myVotes      = {};   // { HEAD: itemId, CAPE: itemId, … }
let voterId      = getOrCreateVoterId();
let searchTimer  = null;

// ── Boot ─────────────────────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
  const params = new URLSearchParams(window.location.search);
  const codeParam = (params.get('code') || '').toUpperCase().trim();

  wireCodeEntry(codeParam);
  loadItemCatalogue();
});

// ── Code entry ────────────────────────────────────────────────────────────────
function wireCodeEntry(prefilled) {
  const input  = document.getElementById('code-input');
  const btn    = document.getElementById('code-submit');
  const errMsg = document.getElementById('code-error');

  if (prefilled) {
    input.value = prefilled;
    submitCode(prefilled, errMsg);
    return;
  }

  input.addEventListener('input', () => {
    input.value = input.value.toUpperCase().replace(/[^A-Z0-9]/g, '');
    errMsg.classList.add('hidden');
  });
  input.addEventListener('keydown', e => { if (e.key === 'Enter') btn.click(); });
  btn.addEventListener('click', () => {
    const code = input.value.trim();
    if (code.length < 4) return;
    submitCode(code, errMsg);
  });

  document.getElementById('change-code').addEventListener('click', () => {
    showScreen('screen-enter');
    input.value = '';
    errMsg.classList.add('hidden');
  });
}

async function submitCode(code, errMsg) {
  const result = await fetchSession(code);
  if (!result) {
    if (errMsg) errMsg.classList.remove('hidden');
    return;
  }
  sessionId   = result.id;
  sessionCode = code;

  document.getElementById('header-code').textContent = code;
  history.replaceState(null, '', '?code=' + code);

  buildSlotTabs();
  await loadMyVotes();
  await refreshStandings();
  showScreen('screen-vote');
}

// ── Supabase helpers ──────────────────────────────────────────────────────────
function sbHeaders() {
  return {
    'apikey':        SUPABASE_ANON_KEY,
    'Authorization': 'Bearer ' + SUPABASE_ANON_KEY,
    'Content-Type':  'application/json',
  };
}

async function fetchSession(code) {
  const url = `${SUPABASE_URL}/rest/v1/sessions?code=eq.${encodeURIComponent(code)}&select=id,code&limit=1`;
  const res = await fetch(url, { headers: sbHeaders() });
  if (!res.ok) return null;
  const rows = await res.json();
  return rows.length > 0 ? rows[0] : null;
}

async function loadMyVotes() {
  // Retrieve this viewer's existing votes for the session so we can show
  // which slots they already voted in
  const url = `${SUPABASE_URL}/rest/v1/votes?session_id=eq.${sessionId}&voter_id=eq.${voterId}&select=slot,item_id`;
  const res = await fetch(url, { headers: sbHeaders() });
  if (!res.ok) return;
  const rows = await res.json();
  myVotes = {};
  rows.forEach(r => { myVotes[r.slot] = r.item_id; });
  updateTabBadges();
}

async function castVote(slot, itemId, itemName) {
  const body = JSON.stringify({
    session_id: sessionId,
    voter_id:   voterId,
    slot:       slot,
    item_id:    itemId,
    item_name:  itemName,
  });

  const res = await fetch(`${SUPABASE_URL}/rest/v1/votes`, {
    method: 'POST',
    headers: {
      ...sbHeaders(),
      'Prefer': 'resolution=merge-duplicates,return=minimal',
    },
    body,
  });

  if (res.ok) {
    myVotes[slot] = itemId;
    updateTabBadges();
    await refreshStandings();
  } else {
    console.error('Vote failed', res.status, await res.text());
  }
}

async function refreshStandings() {
  const url = `${SUPABASE_URL}/rest/v1/top_votes_per_slot?code=eq.${sessionCode}&select=slot,item_id,item_name,vote_count`;
  const res = await fetch(url, { headers: sbHeaders() });
  if (!res.ok) return;
  const rows = await res.json();

  const bySlot = {};
  rows.forEach(r => { bySlot[r.slot] = r; });

  const list = document.getElementById('standings-list');
  list.innerHTML = '';
  SLOTS.forEach(({ key, label }) => {
    const r = bySlot[key];
    const row = document.createElement('div');
    row.className = 'standing-row';
    row.innerHTML = `
      <span class="standing-slot">${label}</span>
      <span class="standing-item">${r ? escHtml(r.item_name) : '—'}</span>
      <span class="standing-votes">${r ? r.vote_count + ' vote' + (r.vote_count === 1 ? '' : 's') : ''}</span>
    `;
    list.appendChild(row);
  });
}

// ── Item catalogue ────────────────────────────────────────────────────────────
async function loadItemCatalogue() {
  try {
    const res = await fetch('https://prices.runescape.wiki/api/v1/osrs/mapping', {
      headers: { 'User-Agent': 'CrowdDressed-voter-page' },
    });
    if (!res.ok) throw new Error('catalogue fetch failed');
    const data = await res.json();
    // The mapping contains all items; we only care about equippable ones.
    // The API doesn't include a slot field, so we rely on the viewer picking
    // the slot and the plugin's ItemManager validating slot/item compatibility.
    allItems = data.map(item => ({
      id:   item.id,
      name: item.name,
      icon: `https://oldschool.runescape.wiki/images/${encodeURIComponent(item.name.replace(/ /g, '_'))}_detail.png`,
    }));
  } catch (e) {
    console.warn('Could not load item catalogue:', e);
  }
}

// ── Slot tabs ─────────────────────────────────────────────────────────────────
function buildSlotTabs() {
  const nav = document.getElementById('slot-tabs');
  nav.innerHTML = '';
  SLOTS.forEach(({ key, label }) => {
    const btn = document.createElement('button');
    btn.className = 'slot-tab' + (key === activeSlot ? ' active' : '');
    btn.textContent = label;
    btn.dataset.slot = key;
    btn.addEventListener('click', () => selectSlot(key));
    nav.appendChild(btn);
  });
  wireSearch();
}

function selectSlot(slotKey) {
  activeSlot = slotKey;
  document.querySelectorAll('.slot-tab').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.slot === slotKey);
  });
  // Re-run the current search for the new slot
  renderSearch(document.getElementById('item-search').value.trim());
}

function updateTabBadges() {
  document.querySelectorAll('.slot-tab').forEach(btn => {
    btn.classList.toggle('voted', myVotes[btn.dataset.slot] != null);
  });
}

// ── Item search ───────────────────────────────────────────────────────────────
function wireSearch() {
  const input = document.getElementById('item-search');
  input.value = '';
  input.addEventListener('input', () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => renderSearch(input.value.trim()), 220);
  });
  renderSearch('');
}

function renderSearch(query) {
  const grid    = document.getElementById('item-results');
  const hint    = document.getElementById('search-hint');
  const noRes   = document.getElementById('no-results');

  if (!query) {
    grid.innerHTML = '';
    hint.classList.remove('hidden');
    noRes.classList.add('hidden');
    return;
  }

  hint.classList.add('hidden');

  const q = query.toLowerCase();
  const matches = allItems
    .filter(item => item.name.toLowerCase().includes(q))
    .slice(0, 60); // cap to keep DOM fast

  if (matches.length === 0) {
    grid.innerHTML = '';
    noRes.classList.remove('hidden');
    return;
  }

  noRes.classList.add('hidden');
  const currentVote = myVotes[activeSlot];

  grid.innerHTML = '';
  matches.forEach(item => {
    const card = document.createElement('div');
    card.className = 'item-card' + (item.id === currentVote ? ' selected' : '');
    card.innerHTML = `
      <img src="${escHtml(item.icon)}" alt="" loading="lazy"
           onerror="this.style.display='none'" />
      <span class="item-name">${escHtml(item.name)}</span>
    `;
    card.addEventListener('click', () => {
      castVote(activeSlot, item.id, item.name);
      // Optimistically mark selected
      document.querySelectorAll('#item-results .item-card').forEach(c => c.classList.remove('selected'));
      card.classList.add('selected');
    });
    grid.appendChild(card);
  });
}

// ── Utilities ─────────────────────────────────────────────────────────────────
function showScreen(id) {
  document.querySelectorAll('.screen').forEach(s => s.classList.remove('active'));
  document.getElementById(id).classList.add('active');
}

function getOrCreateVoterId() {
  const key = 'crowd_dressed_voter_id';
  let id = localStorage.getItem(key);
  if (!id) {
    id = crypto.randomUUID ? crypto.randomUUID() : Math.random().toString(36).slice(2) + Date.now();
    localStorage.setItem(key, id);
  }
  return id;
}

function escHtml(str) {
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}
