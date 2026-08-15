// ECH is a matrix, not a single result: server, TLS mechanism, OkHttp version and platform all
// matter. latest.json carries JUnit results plus the ECHConfigLists captured beside them.

const ECH_SUITES = new Set([
  'EncryptedClientHelloTest',
  'PublicEncryptedClientHelloTest',
  'EchTest',
  'EchConscryptTest',
  'EchClientHelloTest',
  'EchGreaseTest',
  'ClientHelloExtensionsTest',
]);

const PUBLIC_ROWS = [
  ['cloudflare-ech.com', 'the server reports sni=encrypted', {
    EchTest: 'cloudflareUsesEch',
    EchConscryptTest: 'cloudflareAcceptsAnEncryptedClientHello',
    PublicEncryptedClientHelloTest: 'cloudflareUsesEch',
  }],
  ['tls-ech.dev', 'the page says “You are using ECH”', {
    EchTest: 'echIsAcceptedOnTlsEchDev',
    EchConscryptTest: 'tlsEchDevAcceptsAnEncryptedClientHello',
    PublicEncryptedClientHelloTest: 'echIsAcceptedOnTlsEchDev',
  }],
  ['defo.ie', 'SSL_ECH_STATUS: success', {
    EchTest: 'echIsAcceptedOnDefoIe',
    EchConscryptTest: 'defoIeAcceptsAnEncryptedClientHello',
    PublicEncryptedClientHelloTest: 'echIsAcceptedOnDefoIe',
  }],
  ['stale.tls-ech.dev', 'stale DNS config is replaced by the server retry config', {
    EchTest: 'echIsRetriedOnStaleTlsEchDev',
    PublicEncryptedClientHelloTest: 'echIsRetriedOnStaleTlsEchDev',
  }],
  ['tls12.tls-ech.dev', 'TLS 1.2 is reached without ECH', {
    EchTest: 'tlsIsNotUsedOnTls12TlsEchDev',
    EchConscryptTest: 'tls12IsReachedWithoutEch',
    PublicEncryptedClientHelloTest: 'tlsIsNotUsedOnTls12TlsEchDev',
  }],
  ['wrong.tls-ech.dev', '302 is returned and the inner hostname is verified', {
    EchTest: 'echIsAcceptedOnWrongTlsEchDev',
    PublicEncryptedClientHelloTest: 'echIsAcceptedOnWrongTlsEchDev',
  }],
];

const FIXTURE_ROWS = [
  ['green.secret.test', 'accepted on the first handshake', 'greenPathAcceptsEncryptedClientHello'],
  ['retry.secret.test', 'rejected, retried with the server config, then accepted', 'rejectedConfigIsRetriedWithServerConfig'],
  ['disabled.secret.test', 'rejected, then retried without ECH', 'rejectedConfigWithoutServerConfigIsRetriedWithoutEch'],
];

const baseName = (suite) => suite.name.split(' · ')[0];
const variantOf = (suite) => suite.name.includes(' · ') ? suite.name.split(' · ').slice(1).join(' · ') : '';
const text = (node, value) => { node.textContent = value; return node; };

function entries(data, wanted) {
  return (data.versions || []).flatMap((version) =>
    (version.suites || [])
      .filter((suite) => !wanted || wanted.has(baseName(suite)))
      .map((suite) => ({ version, suite, base: baseName(suite), variant: variantOf(suite) })),
  );
}

function resultPill(result) {
  const pill = document.createElement('span');
  const status = result?.status || 'unknown';
  pill.className = `pill ${status === 'failed' ? 'failed' : status}`;
  text(pill,
    status === 'passed' ? 'passed'
    : status === 'expected' ? 'expected'
    : status === 'failed' ? 'failed'
    : status === 'skipped' ? 'not run'
    : '—');
  const why = result?.expectedReason || result?.message;
  if (why) pill.title = why;
  return pill;
}

function evidenceFor(entry, caseName) {
  return (entry.version.echResults || []).find((record) =>
    record.suite === entry.base &&
    record.case === caseName &&
    (!record.variant || !entry.variant || record.variant === entry.variant),
  );
}

function evidence(record) {
  if (!record) return null;
  const attempts = record.attempts || [];
  const detail = document.createElement('details');
  detail.className = 'ech-record';
  const summary = document.createElement('summary');
  text(summary, attempts.map((attempt) =>
    attempt.echConfigList ? `${attempt.source} config` : `${attempt.source} (no config)`,
  ).join(' → ') || 'no attempts');
  detail.append(summary);
  for (const attempt of attempts) {
    const line = document.createElement('div');
    const label = document.createElement('strong');
    text(label, `${attempt.source}: `);
    const value = document.createElement('code');
    text(value, attempt.echConfigList || 'none');
    line.append(label, value);
    detail.append(line);
  }
  return detail;
}

function resultCell(entry, caseName) {
  const td = document.createElement('td');
  if (!entry || !caseName) {
    text(td, '—');
    td.title = 'not covered on this platform';
    return td;
  }
  const result = (entry.suite.cases || []).find((item) => item.name === caseName);
  if (!result) return text(td, '—');
  td.append(resultPill(result));
  const details = evidence(evidenceFor(entry, caseName));
  if (details) td.append(details);
  return td;
}

function heading(entry, mechanism) {
  const th = document.createElement('th');
  const strong = document.createElement('strong');
  text(strong, mechanism || entry.base);
  const small = document.createElement('small');
  text(small, `${entry.version.okhttpVersion} · ${entry.suite.platform || entry.variant}`);
  th.append(strong, document.createElement('br'), small);
  return th;
}

function publicColumns(data) {
  return entries(data, new Set(['EchTest', 'EchConscryptTest', 'PublicEncryptedClientHelloTest']))
    .flatMap((entry) => entry.base === 'EchTest'
      ? [
          { ...entry, param: 'JDK', mechanism: 'OkHttp as shipped' },
          { ...entry, param: 'CONSCRYPT_ECH', mechanism: 'OkHttp + ECH call' },
        ]
      : [{
          ...entry,
          param: '',
          mechanism: entry.base === 'EchConscryptTest' ? 'Conscrypt directly' : 'OkHttp on Android',
        }]);
}

const caseFor = (column, baseCase) => baseCase && column.param ? `${baseCase} ${column.param}` : baseCase;

function tableIn(root, headers, rows) {
  const table = document.createElement('table');
  const head = table.createTHead().insertRow();
  for (const header of headers) {
    head.append(typeof header === 'string' ? text(document.createElement('th'), header) : header);
  }
  const body = table.createTBody();
  for (const cells of rows) {
    const row = body.insertRow();
    for (const item of cells) {
      if (item instanceof Node) row.append(item);
      else text(row.insertCell(), item);
    }
  }
  const scroll = document.createElement('div');
  scroll.className = 'table-scroll';
  scroll.append(table);
  root.replaceChildren(scroll);
}

function renderPublic(data) {
  const root = document.getElementById('ech-public-results');
  const columns = publicColumns(data);
  if (!columns.length) return text(root, 'No public ECH results in the most recent collection.');
  const rows = PUBLIC_ROWS.map(([server, meaning, cases]) => {
    const serverCell = document.createElement('td');
    serverCell.className = 'mono';
    text(serverCell, server);
    return [serverCell, meaning, ...columns.map((column) => resultCell(column, caseFor(column, cases[column.base])))];
  });
  tableIn(root, ['Server', 'What passes means', ...columns.map((column) => heading(column, column.mechanism))], rows);
}

function renderFixture(data) {
  const root = document.getElementById('ech-fixture-results');
  const columns = entries(data, new Set(['EncryptedClientHelloTest']));
  if (!columns.length) return text(root, 'No fixture ECH results in the most recent collection.');
  const rows = FIXTURE_ROWS.map(([server, meaning, caseName]) => {
    const serverCell = document.createElement('td');
    serverCell.className = 'mono';
    text(serverCell, server);
    return [serverCell, meaning, ...columns.map((column) => resultCell(column, caseName))];
  });
  tableIn(root, ['Test server', 'Expected path', ...columns.map((column) => heading(column, column.variant || 'Android'))], rows);
}

function allColumns(data) {
  return entries(data, ECH_SUITES).flatMap((entry) => {
    if (entry.base !== 'EchTest') return [{ ...entry, param: '', mechanism: entry.base }];
    return [
      { ...entry, param: 'JDK', mechanism: 'EchTest · shipped' },
      { ...entry, param: 'CONSCRYPT_ECH', mechanism: 'EchTest · ECH call' },
    ];
  });
}

function renderAll(data) {
  const root = document.getElementById('ech-all-results');
  const columns = allColumns(data);
  if (!columns.length) return text(root, 'No ECH cases in the most recent collection.');

  const keys = new Map();
  for (const column of columns) {
    for (const result of column.suite.cases || []) {
      if (column.param && !result.name.endsWith(` ${column.param}`)) continue;
      if (!column.param && column.base === 'EchTest') continue;
      const display = column.param ? result.name.slice(0, -column.param.length - 1) : result.name;
      keys.set(`${column.base}|${display}`, { suite: column.base, display });
    }
  }

  const rows = [...keys.values()]
    .sort((a, b) => `${a.suite}.${a.display}`.localeCompare(`${b.suite}.${b.display}`))
    .map((result) => {
      const name = document.createElement('td');
      name.className = 'suite';
      text(name, `${result.suite}.${result.display}`);
      return [name, ...columns.map((column) =>
        column.base === result.suite
          ? resultCell(column, caseFor(column, result.display))
          : resultCell(null, null),
      )];
    });
  tableIn(root, ['Test case', ...columns.map((column) => heading(column, column.mechanism))], rows);
}

fetch('../data/latest.json')
  .then((response) => response.ok ? response.json() : Promise.reject(response.status))
  .then((data) => {
    renderPublic(data);
    renderFixture(data);
    renderAll(data);
  })
  .catch(() => {
    for (const id of ['ech-public-results', 'ech-fixture-results', 'ech-all-results']) {
      text(document.getElementById(id), 'Results are unavailable — latest.json could not be read.');
    }
  });
