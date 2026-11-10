const state = {
  token: sessionStorage.getItem('token'),
  user: null,
  officers: [],
  officerId: '',
  page: 0,
  size: 25,
  totalPages: 0,
  entries: [],
  editingId: null,
  editingAccountId: null,
  register: false,
};
const $ = selector => document.querySelector(selector);
const $$ = selector => document.querySelectorAll(selector);
const api = async (path, options = {}) => {
  const headers = new Headers(options.headers || {});
  if (state.token) headers.set('Authorization', `Bearer ${state.token}`);
  if (options.body && !(options.body instanceof FormData)) headers.set('Content-Type', 'application/json');
  const response = await fetch(path, { ...options, headers });
  if (response.status === 401) {
    logout();
    throw new Error('Please sign in again.');
  }
  let data;
  if (response.status !== 204) {
    const type = response.headers.get('content-type') || '';
    data = type.includes('application/json') ? await response.json() : await response.text();
  }
  if (!response.ok) throw new Error(data?.detail || data?.message || data || `Request failed (${response.status})`);
  return { data, response };
};
const todayKampala = () => {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Africa/Kampala', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date());
  const value = name => parts.find(part => part.type === name).value;
  return `${value('year')}-${value('month')}-${value('day')}`;
};
const formatUGX = amount => `UGX ${BigInt(amount || 0).toLocaleString('en-UG')}`;
const escapeHTML = value => {
  const node = document.createElement('span');
  node.textContent = value ?? '';
  return node.innerHTML;
};
function flash(message) {
  const toast = $('#toast');
  toast.textContent = message;
  toast.classList.remove('hidden');
  window.setTimeout(() => toast.classList.add('hidden'), 4000);
}
function logout() {
  sessionStorage.removeItem('token');
  state.token = null;
  state.user = null;
  $('#app-screen').classList.add('hidden');
  $('#login-screen').classList.remove('hidden');
}
function setAuthMode(register) {
  state.register = register;
  $('#auth-title').textContent = register ? 'Create officer account' : 'Sign in';
  $('#auth-submit').textContent = register ? 'Create account' : 'Sign in';
  $('#register-name-wrap').classList.toggle('hidden', !register);
  $('#register-name-wrap input').required = register;
  $('#register-toggle').textContent = register ? 'Already have an account? Sign in' : 'Create an officer account';
  $('#login-form [name="password"]').autocomplete = register ? 'new-password' : 'current-password';
  $('#login-error').textContent = '';
}
async function showApp() {
  if (!state.token) return;
  $('#login-screen').classList.add('hidden');
  $('#app-screen').classList.remove('hidden');
  try {
    await initialize();
  } catch (error) {
    flash(error.message);
  }
}
async function initialize() {
  const { data: user } = await api('/api/auth/me');
  state.user = user;
  $('#identity').textContent = user.displayName;
  $('#avatar').textContent = user.displayName.slice(0, 1).toUpperCase();
  const isAdmin = user.role === 'ADMIN';
  $('#officers-link').classList.toggle('hidden', !isAdmin);
  $('#officer-filter-wrap').classList.toggle('hidden', !isAdmin);
  $('#entry-officer-wrap').classList.toggle('hidden', !isAdmin);
  $('#import-officer-wrap').classList.toggle('hidden', !isAdmin);
  $('#forced-change').classList.toggle('hidden', !user.mustChangePassword);
  $('#workspace').classList.toggle('hidden', user.mustChangePassword);
  $('#daily-date').value = todayKampala();
  $('#daily-date').max = todayKampala();
  $('[name="date"]').value = todayKampala();
  $('[name="date"]').max = todayKampala();
  const theme = localStorage.getItem('theme');
  if (theme === 'dark') document.documentElement.dataset.theme = 'dark';
  updateThemeButton();
  if (isAdmin) await loadAccounts();
  updateReportAvailability();
  updateMonthBanner();
  if (!user.mustChangePassword) await refreshDashboard();
}
function activeOfficerParam() {
  return state.user?.role === 'ADMIN' && state.officerId ? `&officerId=${encodeURIComponent(state.officerId)}` : '';
}
async function refreshDashboard() {
  if (!state.user || state.user.mustChangePassword) return;
  const params = new URLSearchParams({ date: $('#daily-date').value || todayKampala() });
  if (state.user.role === 'ADMIN' && state.officerId) params.set('officerId', state.officerId);
  const { data } = await api(`/api/dashboard?${params}`);
  $('#daily-total').textContent = formatUGX(data.daily.amount);
  $('#daily-count').textContent = data.daily.count;
  $('#weekly-total').textContent = formatUGX(data.weekly.amount);
  $('#weekly-count').textContent = data.weekly.count;
  $('#week-range').textContent = `${data.weekly.start} to ${data.weekly.end}`;
  $('#monthly-total').textContent = formatUGX(data.monthly.amount);
  $('#monthly-count').textContent = data.monthly.count;
  $('#month-range').textContent = `${data.monthly.start} to ${data.monthly.end}`;
  $('#week-breakdown').replaceChildren(buildBreakdownTable(data.weekBreakdown));
  $('#month-breakdown').replaceChildren(buildBreakdownTable(data.monthBreakdown));
  $('#weekly-period').textContent = `Friday ${data.weekly.start} through Thursday ${data.weekly.end} · ${scopeLabel()}`;
}
function buildBreakdownTable(days) {
  const table = document.createElement('table');
  table.className = 'breakdown-table';
  table.innerHTML = '<thead><tr><th>Date</th><th>Records</th><th>Total</th></tr></thead>';
  const body = document.createElement('tbody');
  for (const day of days) {
    const row = document.createElement('tr');
    row.innerHTML = `<td>${escapeHTML(day.date)}</td><td>${day.count}</td><td>${escapeHTML(formatUGX(day.amount))}</td>`;
    body.append(row);
  }
  table.append(body);
  return table;
}
function recordFilters() {
  const params = new URLSearchParams({ page: state.page, size: state.size });
  if ($('#search').value.trim()) params.set('q', $('#search').value.trim());
  if ($('#records-from').value) params.set('from', $('#records-from').value);
  if ($('#records-to').value) params.set('to', $('#records-to').value);
  if (state.user.role === 'ADMIN' && state.officerId) params.set('officerId', state.officerId);
  return params;
}
async function refreshRecords() {
  const params = recordFilters();
  const { data: page } = await api(`/api/entries?${params}`);
  state.totalPages = page.totalPages;
  state.entries = page.content;
  const totalParams = new URLSearchParams();
  if ($('#search').value.trim()) totalParams.set('q', $('#search').value.trim());
  if ($('#records-from').value) totalParams.set('from', $('#records-from').value);
  if ($('#records-to').value) totalParams.set('to', $('#records-to').value);
  if (state.user.role === 'ADMIN' && state.officerId) totalParams.set('officerId', state.officerId);
  const { data: total } = await api(`/api/entries/totals?${totalParams}`);
  $('#filtered-total').textContent = formatUGX(total.amount);
  $('#page-info').textContent = page.totalElements
    ? `Showing ${page.number * page.size + 1}–${page.number * page.size + page.numberOfElements} of ${page.totalElements}`
    : 'No records';
  $('#prev-page').disabled = state.page === 0;
  $('#next-page').disabled = !page.content.length || state.page >= page.totalPages - 1;
  renderEntries();
}
async function refreshDashboardAndRecords() {
  await Promise.all([refreshDashboard(), refreshRecords()]);
}
function renderEntries() {
  const body = $('#records-body');
  body.replaceChildren();
  if (!state.entries.length) {
    const empty = document.createElement('tr');
    empty.innerHTML = `<td colspan="6" class="muted">No collection records found for these filters.</td>`;
    body.append(empty);
    return;
  }
  for (const entry of state.entries) {
    const row = document.createElement('tr');
    row.innerHTML = `<td>${escapeHTML(entry.date)}</td><td>${escapeHTML(entry.trackingNumber)}</td>` +
      `<td>${escapeHTML(entry.prn)}</td><td class="amount">${escapeHTML(formatUGX(entry.amount))}</td>` +
      `<td class="officer-column">${escapeHTML(entry.officer)}</td>` +
      `<td><div class="actions"><button class="secondary" type="button" data-edit="${entry.id}">Edit</button>` +
      `<button class="secondary danger" type="button" data-delete="${entry.id}">Delete</button></div></td>`;
    body.append(row);
  }
  $$('.officer-column').forEach(cell => cell.classList.toggle('hidden', state.user.role !== 'ADMIN'));
}
function showTab(id) {
  $$('.tab-panel').forEach(panel => panel.classList.toggle('hidden', panel.id !== id));
  $$('.tab-link').forEach(link => link.classList.toggle('active', link.dataset.tab === id));
  if (id === 'records-panel') refreshRecords().catch(error => flash(error.message));
}
async function download(path, filename) {
  try {
    const { response } = await api(path);
    const blob = await response.blob();
    const anchor = document.createElement('a');
    anchor.href = URL.createObjectURL(blob);
    anchor.download = filename;
    anchor.click();
    window.setTimeout(() => URL.revokeObjectURL(anchor.href), 1000);
  } catch (error) {
    flash(error.message);
  }
}
function scopeLabel() {
  if (state.user?.role !== 'ADMIN') return state.user?.displayName || 'My collections';
  const selected = state.officers.find(officer => String(officer.id) === state.officerId);
  return selected ? selected.displayName : 'All officers';
}
function periodParams(period, from, to) {
  const params = new URLSearchParams({ period });
  if (from) params.set('from', from);
  if (to) params.set('to', to);
  if (state.user?.role === 'ADMIN' && state.officerId) params.set('officerId', state.officerId);
  return params;
}
async function fetchReportEntries(from, to) {
  const rows = [];
  for (let page = 0; ; page++) {
    const params = new URLSearchParams({ page, size: 100, from, to });
    if (state.user.role === 'ADMIN' && state.officerId) params.set('officerId', state.officerId);
    const { data } = await api(`/api/entries?${params}`);
    rows.push(...data.content);
    if (!data.totalPages || page >= data.totalPages - 1) return rows;
  }
}
function dateList(from, to) {
  const dates = [];
  const current = new Date(`${from}T00:00:00Z`);
  const last = new Date(`${to}T00:00:00Z`);
  for (; current <= last; current.setUTCDate(current.getUTCDate() + 1)) dates.push(current.toISOString().slice(0, 10));
  return dates;
}
function weekStart(dateText) {
  const date = new Date(`${dateText}T00:00:00Z`);
  const daysSinceFriday = (date.getUTCDay() - 5 + 7) % 7;
  date.setUTCDate(date.getUTCDate() - daysSinceFriday);
  return date.toISOString().slice(0, 10);
}
async function makeBrowserReport(from, to, title) {
  const message = title === 'Weekly report' ? $('#weekly-message') : $('#custom-message');
  message.textContent = '';
  if (!from || !to || from > to) {
    message.textContent = 'Choose a valid date range: From must not be after To.';
    return;
  }
  if (to > todayKampala()) {
    message.textContent = 'Report dates cannot be in the future.';
    return;
  }
  try {
    const entries = await fetchReportEntries(from, to);
    if (!entries.length) {
      message.textContent = 'No collection records exist in this date range.';
      return;
    }
    const JsPDF = window.jspdf?.jsPDF;
    if (!JsPDF) throw new Error('The PDF library could not be loaded. Check your internet connection and try again.');
    const doc = new JsPDF({ orientation: 'landscape' });
    const days = dateList(from, to);
    const totals = new Map(days.map(day => [day, { count: 0, amount: 0n }]));
    for (const entry of entries) {
      const current = totals.get(entry.date);
      if (current) {
        current.count += 1;
        current.amount += BigInt(entry.amount);
      }
    }
    const grandTotal = entries.reduce((sum, entry) => sum + BigInt(entry.amount), 0n);
    const sortedDays = [...totals.entries()];
    const best = sortedDays.reduce((winner, value) => value[1].amount > winner[1].amount ? value : winner);
    const lowest = sortedDays.reduce((winner, value) => value[1].amount < winner[1].amount ? value : winner);
    doc.setFontSize(18);
    doc.text('Collections Performance Report', 14, 18);
    doc.setFontSize(10);
    doc.text(`Period: ${from} to ${to}`, 14, 26);
    doc.text(`Scope: ${scopeLabel()} · Generated: ${todayKampala()}`, 14, 32);
    const average = (grandTotal + BigInt(days.length) / 2n) / BigInt(days.length);
    doc.text(`Grand total: ${formatUGX(grandTotal)} · Transactions: ${entries.length} · Daily average: ${formatUGX(average)}`, 14, 39);
    doc.text(`Best day: ${best[0]} (${formatUGX(best[1].amount)}) · Lowest day: ${lowest[0]} (${formatUGX(lowest[1].amount)})`, 14, 46);
    doc.autoTable({
      startY: 52,
      head: [['Date', 'Transactions', 'Total collected']],
      body: sortedDays.map(([day, value]) => [day, value.count, formatUGX(value.amount)]),
      theme: 'grid',
      styles: { fontSize: 8 },
      headStyles: { fillColor: [24, 59, 86] },
    });
    let startY = doc.lastAutoTable.finalY + 10;
    if (startY + 72 > doc.internal.pageSize.height - 12) {
      doc.addPage();
      startY = 16;
    }
    doc.setFontSize(11);
    doc.text('Daily collections chart', 14, startY);
    const chartX = 20;
    const chartY = startY + 7;
    const chartWidth = doc.internal.pageSize.width - 40;
    const chartHeight = 42;
    const barGap = 2;
    const barWidth = Math.max(2, (chartWidth - barGap * days.length) / days.length);
    const maxDayTotal = sortedDays.reduce((max, [, value]) => value.amount > max ? value.amount : max, 0n);
    doc.setFillColor(19, 138, 131);
    sortedDays.forEach(([day, value], index) => {
      const barHeight = maxDayTotal ? Number((value.amount * BigInt(chartHeight)) / maxDayTotal) : 0;
      const x = chartX + index * (barWidth + barGap);
      doc.rect(x, chartY + chartHeight - barHeight, barWidth, barHeight, 'F');
      if (days.length <= 15) {
        doc.setFontSize(6);
        doc.text(day.slice(5), x + barWidth / 2, chartY + chartHeight + 5, { align: 'center' });
      }
    });
    startY = chartY + chartHeight + 13;
    if (title !== 'Weekly report') {
      const weeks = new Map();
      for (const entry of entries) {
        const start = weekStart(entry.date);
        const value = weeks.get(start) || { count: 0, amount: 0n };
        value.count += 1;
        value.amount += BigInt(entry.amount);
        weeks.set(start, value);
      }
      doc.autoTable({
        startY,
        head: [['Week beginning (Friday)', 'Transactions', 'Subtotal']],
        body: [...weeks.entries()].sort(([a], [b]) => a.localeCompare(b))
          .map(([start, value]) => [start, value.count, formatUGX(value.amount)]),
        theme: 'grid',
        styles: { fontSize: 8 },
        headStyles: { fillColor: [24, 59, 86] },
      });
      startY = doc.lastAutoTable.finalY + 10;
    }
    doc.autoTable({
      startY,
      head: [['Date', 'Tracking number', 'PRN', 'Amount (UGX)', ...(state.user.role === 'ADMIN' ? ['Officer'] : [])]],
      body: entries.map(entry => [
        entry.date, entry.trackingNumber, entry.prn,         BigInt(entry.amount).toLocaleString('en-UG'),
        ...(state.user.role === 'ADMIN' ? [entry.officer] : []),
      ]),
      theme: 'grid',
      styles: { fontSize: 7 },
      headStyles: { fillColor: [24, 59, 86] },
      didDrawPage: () => {
        doc.setFontSize(8);
        doc.text('Collections Performance Tracker · UGX', 14, doc.internal.pageSize.height - 8);
      },
    });
    doc.save(`collections-report-${from}-to-${to}.pdf`);
    message.textContent = 'PDF report generated.';
  } catch (error) {
    message.textContent = error.message;
  }
}
function updateReportAvailability() {
  const thursday = new Intl.DateTimeFormat('en-US', { timeZone: 'Africa/Kampala', weekday: 'long' })
    .format(new Date()) === 'Thursday';
  $('#weekly-pdf').disabled = !thursday;
  $('#weekly-python-pdf').disabled = !thursday;
  $('#weekly-availability').textContent = thursday ? 'This week’s report is available.' : 'Available every Thursday';
  const today = new Date(`${todayKampala()}T00:00:00Z`);
  const day = today.getUTCDay();
  const start = new Date(today);
  start.setUTCDate(start.getUTCDate() - ((day - 5 + 7) % 7));
  const end = new Date(start);
  end.setUTCDate(end.getUTCDate() + 6);
  $('#weekly-period').textContent = `Friday ${start.toISOString().slice(0, 10)} through Thursday ${end.toISOString().slice(0, 10)} · ${scopeLabel()}`;
}
function updateMonthBanner() {
  const now = new Date(`${todayKampala()}T00:00:00Z`);
  const day = now.getUTCDate();
  const lastDay = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 0)).getUTCDate();
  $('#month-banner').classList.toggle('hidden', !(day <= 3 || day > lastDay - 3));
}
function setMonthRange(offset) {
  const today = new Date(`${todayKampala()}T00:00:00Z`);
  const now = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth() + offset, 1));
  const first = now.toISOString().slice(0, 10);
  const last = offset === 0 ? todayKampala() : new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 0)).toISOString().slice(0, 10);
  $('#report-from').value = first;
  $('#report-to').value = last;
}
function updateThemeButton() {
  $('#theme-toggle').textContent = document.documentElement.dataset.theme === 'dark' ? 'Light mode' : 'Dark mode';
}
function updateEntryError(name, message) {
  const element = $(`[data-error="${name}"]`);
  element.textContent = message || '';
  element.closest('label').classList.toggle('invalid', Boolean(message));
}
function validateEntryField(name) {
  const input = $(`[name="${name}"]`);
  const value = input.value.trim();
  let error = '';
  if (name === 'date') {
    if (!value) error = 'Date is required.';
    else if (value > todayKampala()) error = 'Date cannot be in the future.';
  } else if (name === 'trackingNumber') {
    if (!/^G\d{6}-\d{4}$/.test(value.toUpperCase())) error = 'Use G, six digits, a hyphen, and four digits.';
  } else if (name === 'prn') {
    if (!/^\d{13}$/.test(value)) error = 'PRN must contain exactly 13 digits.';
  } else if (name === 'amount') {
    const amount = value.replace(/[,\s]/g, '');
    if (!/^\d+$/.test(amount) || BigInt(amount || '0') <= 0n || BigInt(amount || '0') > 9223372036854775807n)
      error = 'Enter a positive whole number of UGX within the supported range.';
  }
  updateEntryError(name, error);
  return !error;
}
function validateEntryForm() {
  return ['date', 'trackingNumber', 'prn', 'amount'].map(validateEntryField).every(Boolean);
}
function formatAmountInput(input) {
  const digits = input.value.replace(/[^\d]/g, '');
  input.value = digits ? Number(digits).toLocaleString('en-US', { maximumFractionDigits: 0 }) : '';
  validateEntryField('amount');
}
async function loadAccounts() {
  const { data } = await api('/api/accounts');
  state.officers = data.filter(account => account.role === 'OFFICER' && account.enabled);
  if (!state.officers.some(officer => String(officer.id) === state.officerId)) state.officerId = '';
  const options = ['<option value="">All officers</option>', ...state.officers.map(officer =>
    `<option value="${officer.id}">${escapeHTML(officer.displayName)} (${escapeHTML(officer.username)})</option>`)];
  $('#officer-filter').innerHTML = options.join('');
  const officerOptions = state.officers.map(officer =>
    `<option value="${officer.id}">${escapeHTML(officer.displayName)} (${escapeHTML(officer.username)})</option>`);
  $('#entry-officer').innerHTML = officerOptions.join('');
  $('#import-officer').innerHTML = officerOptions.join('');
  $('#entry-officer').required = state.officers.length > 0;
  $('#import-officer').required = state.officers.length > 0;
  const list = $('#accounts-list');
  list.replaceChildren();
  for (const account of data) {
    const row = document.createElement('div');
    row.className = 'account-row';
    row.innerHTML = `<div><strong>${escapeHTML(account.displayName)}</strong><span class="subtext">${escapeHTML(account.username)} · ${escapeHTML(account.role)}${account.mustChangePassword ? ' · password change required' : ''}</span></div>` +
      `<div class="account-actions"><button class="secondary" type="button" data-edit-account="${account.id}">Edit details</button>` +
      `<button class="secondary" type="button" data-reset="${account.id}">Reset password</button>` +
      `<button class="secondary" type="button" data-account="${account.id}" data-enabled="${!account.enabled}">${account.enabled ? 'Deactivate' : 'Activate'}</button></div>`;
    list.append(row);
  }
}

$('#register-toggle').addEventListener('click', () => setAuthMode(!state.register));
$('#login-form').addEventListener('submit', async event => {
  event.preventDefault();
  $('#login-error').textContent = '';
  const form = new FormData(event.currentTarget);
  const body = state.register
    ? { username: form.get('username'), displayName: form.get('displayName'), password: form.get('password') }
    : { username: form.get('username'), password: form.get('password') };
  try {
    const { data } = await api(state.register ? '/api/auth/register' : '/api/auth/login', {
      method: 'POST', body: JSON.stringify(body),
    });
    if (state.register) {
      setAuthMode(false);
      $('#login-error').textContent = 'Account created. You can now sign in.';
      event.currentTarget.reset();
      return;
    }
    state.token = data.token;
    sessionStorage.setItem('token', data.token);
    await showApp();
  } catch (error) {
    $('#login-error').textContent = error.message;
  }
});
$('#logout').addEventListener('click', logout);
$('#password-form').addEventListener('submit', async event => {
  event.preventDefault();
  const password = new FormData(event.currentTarget).get('newPassword');
  try {
    await api('/api/auth/password', { method: 'POST', body: JSON.stringify({ newPassword: password }) });
    flash('Password updated. Please sign in again.');
    logout();
  } catch (error) {
    flash(error.message);
  }
});
$('#entry-form').addEventListener('input', event => {
  const name = event.target.name;
  if (name === 'trackingNumber') {
    event.target.value = event.target.value.toUpperCase();
    validateEntryField(name);
  } else if (name === 'amount') formatAmountInput(event.target);
  else if (['date', 'prn'].includes(name)) validateEntryField(name);
});
$('#entry-form').addEventListener('submit', async event => {
  event.preventDefault();
  $('#entry-message').textContent = '';
  if (!validateEntryForm()) return;
  const form = new FormData(event.currentTarget);
  const body = {
    date: form.get('date'),
    trackingNumber: String(form.get('trackingNumber')).toUpperCase(),
    prn: form.get('prn'),
    amount: String(form.get('amount')).replace(/[,\s]/g, ''),
  };
  try {
    if (state.editingId) {
      await api(`/api/entries/${state.editingId}`, { method: 'PUT', body: JSON.stringify(body) });
      $('#entry-message').textContent = 'Collection updated successfully.';
    } else {
      const params = new URLSearchParams();
      if (state.user.role === 'ADMIN' && form.get('officerId')) params.set('officerId', form.get('officerId'));
      await api(`/api/entries?${params}`, { method: 'POST', body: JSON.stringify(body) });
      $('#entry-message').textContent = 'Collection saved successfully.';
    }
    resetEntryForm();
    state.page = 0;
    await refreshDashboardAndRecords();
  } catch (error) {
    $('#entry-message').textContent = error.message;
  }
});
$$('.tab-link').forEach(link => link.addEventListener('click', event => {
  event.preventDefault();
  showTab(link.dataset.tab);
}));
$$('[data-open-tab]').forEach(button => button.addEventListener('click', () => {
  showTab(button.dataset.openTab);
  location.hash = button.dataset.openTab;
}));
$('#daily-date').addEventListener('change', () => refreshDashboard().catch(error => flash(error.message)));
$('#officer-filter').addEventListener('change', async event => {
  state.officerId = event.target.value;
  state.page = 0;
  updateReportAvailability();
  await refreshDashboardAndRecords();
});
$('#records-body').addEventListener('click', async event => {
  const editId = event.target.dataset.edit;
  if (editId) {
    const entry = state.entries.find(item => String(item.id) === editId);
    if (!entry) return;
    state.editingId = entry.id;
    $('[name="date"]').value = entry.date;
    $('[name="trackingNumber"]').value = entry.trackingNumber;
    $('[name="prn"]').value = entry.prn;
    $('[name="amount"]').value = BigInt(entry.amount).toLocaleString('en-US');
    $('#entry-heading').textContent = 'Edit collection';
    $('#save-entry').textContent = 'Save changes';
    $('#cancel-edit').classList.remove('hidden');
    $('#entry-officer').disabled = true;
    if (state.user.role === 'ADMIN') $('#entry-officer').value = entry.officerId;
    validateEntryForm();
    showTab('entry-panel');
    $('[name="date"]').focus();
    return;
  }
  const id = event.target.dataset.delete;
  if (!id || !window.confirm('Delete this collection record? This action is recorded in the audit log.')) return;
  try {
    await api(`/api/entries/${id}`, { method: 'DELETE' });
    flash('Record deleted. The tracking number and PRN are available for reuse.');
    await refreshDashboardAndRecords();
  } catch (error) {
    flash(error.message);
  }
});
function resetEntryForm() {
  state.editingId = null;
  $('#entry-form').reset();
  $('[name="date"]').value = todayKampala();
  $('#entry-heading').textContent = 'Record a collection';
  $('#save-entry').textContent = 'Save collection';
  $('#cancel-edit').classList.add('hidden');
  $('#entry-officer').disabled = false;
  ['date', 'trackingNumber', 'prn', 'amount'].forEach(name => updateEntryError(name, ''));
}
$('#cancel-edit').addEventListener('click', () => {
  resetEntryForm();
  $('#entry-message').textContent = '';
});
let searchTimer;
$('#search').addEventListener('input', () => {
  window.clearTimeout(searchTimer);
  searchTimer = window.setTimeout(() => { state.page = 0; refreshRecords().catch(error => flash(error.message)); }, 250);
});
['records-from', 'records-to'].forEach(id => $(`#${id}`).addEventListener('change', () => {
  state.page = 0;
  refreshRecords().catch(error => flash(error.message));
}));
$('#clear-record-filters').addEventListener('click', () => {
  $('#search').value = '';
  $('#records-from').value = '';
  $('#records-to').value = '';
  state.page = 0;
  refreshRecords().catch(error => flash(error.message));
});
$('#prev-page').addEventListener('click', () => {
  if (state.page > 0) { state.page--; refreshRecords().catch(error => flash(error.message)); }
});
$('#next-page').addEventListener('click', () => {
  if (state.page < state.totalPages - 1) { state.page++; refreshRecords().catch(error => flash(error.message)); }
});
function exportQuery(period) {
  const params = new URLSearchParams({ period });
  if ($('#records-from').value && $('#records-to').value) {
    params.set('period', 'custom');
    params.set('from', $('#records-from').value);
    params.set('to', $('#records-to').value);
  }
  if (state.user.role === 'ADMIN' && state.officerId) params.set('officerId', state.officerId);
  return params;
}
$('#csv-export').addEventListener('click', () => download(`/api/entries/export?${exportQuery('week')}`, 'collections.csv'));
$('#xlsx-export').addEventListener('click', () => download(`/api/entries/export.xlsx?${exportQuery('week')}`, 'collections.xlsx'));
$('#weekly-pdf').addEventListener('click', () => {
  const today = todayKampala();
  const start = weekStart(today);
  const endDate = new Date(`${start}T00:00:00Z`);
  endDate.setUTCDate(endDate.getUTCDate() + 6);
  makeBrowserReport(start, endDate.toISOString().slice(0, 10), 'Weekly report');
});
$('#weekly-python-pdf').addEventListener('click', () => download(`/api/reports/pdf?${periodParams('week')}`, 'weekly-collections-report.pdf'));
$('#this-month').addEventListener('click', () => setMonthRange(0));
$('#last-month').addEventListener('click', () => setMonthRange(-1));
$('#generate-custom').addEventListener('click', () =>
  makeBrowserReport($('#report-from').value, $('#report-to').value, 'Custom report'));
$('#custom-python-pdf').addEventListener('click', () => {
  const from = $('#report-from').value;
  const to = $('#report-to').value;
  if (!from || !to || from > to) {
    $('#custom-message').textContent = 'Choose a valid date range: From must not be after To.';
    return;
  }
  download(`/api/reports/pdf?${periodParams('custom', from, to)}`, 'custom-collections-report.pdf');
});
$('#account-form').addEventListener('submit', async event => {
  event.preventDefault();
  const form = new FormData(event.currentTarget);
  try {
    await api('/api/accounts', {
      method: 'POST',
      body: JSON.stringify({
        username: form.get('username'), displayName: form.get('displayName'),
        role: form.get('role'), password: form.get('password'),
      }),
    });
    $('#account-message').textContent = 'Account created; the user must change the temporary password.';
    event.currentTarget.reset();
    await loadAccounts();
  } catch (error) {
    $('#account-message').textContent = error.message;
  }
});
$('#accounts-list').addEventListener('click', async event => {
  const accountId = event.target.dataset.account;
  const resetId = event.target.dataset.reset;
  const editId = event.target.dataset.editAccount;
  try {
    if (editId) {
      const { data } = await api('/api/accounts');
      const account = data.find(item => String(item.id) === editId);
      if (!account) throw new Error('Account not found. Refresh the account list and try again.');
      state.editingAccountId = account.id;
      const form = $('#edit-account-form');
      form.elements.username.value = account.username;
      form.elements.displayName.value = account.displayName;
      form.elements.role.value = account.role;
      form.elements.role.disabled = account.id === state.user.id;
      $('#edit-account-message').textContent = '';
      $('#edit-account-dialog').showModal();
    } else if (accountId) {
      const enabled = event.target.dataset.enabled === 'true';
      if (!enabled && !window.confirm('Deactivate this account?')) return;
      await api(`/api/accounts/${accountId}/enabled`, {
        method: 'PATCH', body: JSON.stringify({ enabled }),
      });
    } else if (resetId) {
      const temporaryPassword = window.prompt('Enter a temporary password of at least 12 characters:');
      if (!temporaryPassword) return;
      await api(`/api/accounts/${resetId}/password`, {
        method: 'POST', body: JSON.stringify({ temporaryPassword }),
      });
      flash('Password reset. The user must change it at next sign-in.');
    } else return;
    await loadAccounts();
  } catch (error) {
    flash(error.message);
  }
});
$('#cancel-account-edit').addEventListener('click', () => $('#edit-account-dialog').close());
$('#edit-account-form').addEventListener('submit', async event => {
  event.preventDefault();
  const form = event.currentTarget;
  const body = {
    username: form.elements.username.value.trim(),
    displayName: form.elements.displayName.value.trim(),
    role: form.elements.role.value,
  };
  try {
    const { data: updated } = await api(`/api/accounts/${state.editingAccountId}`, {
      method: 'PATCH', body: JSON.stringify(body),
    });
    if (updated.token) {
      state.token = updated.token;
      sessionStorage.setItem('token', updated.token);
    }
    if (updated.id === state.user.id) {
      state.user = { ...state.user, ...updated };
      $('#identity').textContent = updated.displayName;
      $('#avatar').textContent = updated.displayName.slice(0, 1).toUpperCase();
    }
    $('#edit-account-dialog').close();
    await loadAccounts();
    await refreshDashboardAndRecords();
    flash('Account details updated.');
  } catch (error) {
    $('#edit-account-message').textContent = error.message;
  }
});
$('#import-form').addEventListener('submit', async event => {
  event.preventDefault();
  const form = new FormData(event.currentTarget);
  const params = new URLSearchParams();
  if (state.user.role === 'ADMIN') params.set('officerId', form.get('officerId'));
  const upload = new FormData();
  upload.append('file', form.get('file'));
  $('#import-result').textContent = 'Importing and validating…';
  try {
    const { data } = await api(`/api/imports?${params}`, { method: 'POST', body: upload });
    const result = document.createElement('div');
    result.textContent = `Imported ${data.imported} row(s); rejected ${data.skipped.length}.`;
    if (data.skipped.length) {
      const list = document.createElement('ul');
      for (const skipped of data.skipped.slice(0, 50)) {
        const item = document.createElement('li');
        item.textContent = `Row ${skipped.row}: ${skipped.reason}`;
        list.append(item);
      }
      result.append(list);
    }
    $('#import-result').replaceChildren(result);
    await refreshDashboardAndRecords();
  } catch (error) {
    $('#import-result').textContent = error.message;
  }
});
$('#theme-toggle').addEventListener('click', () => {
  const dark = document.documentElement.dataset.theme !== 'dark';
  if (dark) document.documentElement.dataset.theme = 'dark';
  else delete document.documentElement.dataset.theme;
  localStorage.setItem('theme', dark ? 'dark' : 'light');
  updateThemeButton();
});
if (state.token) showApp();
