// Renders the ECH results matrix from latest.json.
//
// The question this page exists to answer is "does ECH work, where?", and that is a grid: one
// row per server, one column per way of reaching it. The four suites make the same requests
// and differ only in what is doing the TLS, so reading them side by side is what turns four
// separate results into one finding.
//
// Case names differ between suites for the same server, so the mapping is written out rather
// than guessed. A blank cell means that suite doesn't cover that server, which is a fact worth
// showing — it is why three of the rows have gaps.

const SUITES = [
  {
    suite: 'EchTest',
    heading: 'OkHttp as shipped',
    note: 'JVM, no Conscrypt',
  },
  {
    suite: 'EchPlatformTest',
    heading: 'OkHttp + the missing call',
    note: 'JVM, EchConscryptPlatform',
  },
  {
    suite: 'EchConscryptTest',
    heading: 'Conscrypt directly',
    note: 'JVM, outside OkHttp',
  },
  {
    suite: 'PublicEncryptedClientHelloTest',
    heading: 'OkHttp on Android',
    note: 'the platform makes the call',
  },
];

const ROWS = [
  {
    server: 'cloudflare-ech.com',
    asserts: 'the server reports sni=encrypted',
    cases: {
      EchTest: 'cloudflareUsesEch',
      EchPlatformTest: 'cloudflareUsesEch',
      EchConscryptTest: 'cloudflareAcceptsAnEncryptedClientHello',
      PublicEncryptedClientHelloTest: 'cloudflareUsesEch',
    },
  },
  {
    server: 'tls-ech.dev',
    asserts: 'the page says "You are using ECH"',
    cases: {
      EchTest: 'echIsAcceptedOnTlsEchDev',
      EchPlatformTest: 'echIsAcceptedOnTlsEchDev',
      EchConscryptTest: 'tlsEchDevAcceptsAnEncryptedClientHello',
      PublicEncryptedClientHelloTest: 'echIsAcceptedOnTlsEchDev',
    },
  },
  {
    server: 'defo.ie',
    asserts: 'SSL_ECH_STATUS: success',
    cases: {
      EchTest: 'echIsAcceptedOnDefoIe',
      EchPlatformTest: 'echIsAcceptedOnDefoIe',
      EchConscryptTest: 'defoIeAcceptsAnEncryptedClientHello',
      PublicEncryptedClientHelloTest: 'echIsAcceptedOnDefoIe',
    },
  },
  {
    server: 'stale.tls-ech.dev',
    asserts: 'a stale config is retried with the server’s',
    cases: {
      EchTest: 'echIsRetriedOnStaleTlsEchDev',
      PublicEncryptedClientHelloTest: 'echIsRetriedOnStaleTlsEchDev',
    },
  },
  {
    server: 'tls12.tls-ech.dev',
    asserts: 'TLS 1.2 is reached without ECH rather than failing',
    cases: {
      EchTest: 'tlsIsNotUsedOnTls12TlsEchDev',
      EchConscryptTest: 'tls12IsReachedWithoutEch',
      PublicEncryptedClientHelloTest: 'tlsIsNotUsedOnTls12TlsEchDev',
    },
  },
  {
    server: 'wrong.tls-ech.dev',
    asserts: 'the redirect is followed and the name verified',
    cases: {
      EchTest: 'echIsAcceptedOnWrongTlsEchDev',
      PublicEncryptedClientHelloTest: 'echIsAcceptedOnWrongTlsEchDev',
    },
  },
];

// Android's runner appends the device to every case name.
const bareName = (name) => name.replace(/\s*\[.*\]\s*$/, '');

const text = (el, value) => {
  el.textContent = value;
  return el;
};

function findSuites(data) {
  const found = new Map();
  for (const version of data.versions || []) {
    for (const suite of version.suites || []) {
      // A version testing ECH is a version with these suites in it; if two versions both ran
      // one, the later-sorted (snapshot) wins, which is the one that can do ECH at all.
      found.set(suite.name, { suite, version });
    }
  }
  return found;
}

function cellFor(entry, caseName) {
  if (!entry || !caseName) return null;
  return (entry.suite.cases || []).find((c) => bareName(c.name) === caseName) || null;
}

function render(data, root) {
  const found = findSuites(data);
  const columns = SUITES.filter((c) => found.has(c.suite));

  if (!columns.length) {
    text(root, 'No ECH results in the most recent collection.');
    return;
  }

  const table = document.createElement('table');

  const head = table.createTHead().insertRow();
  text(head.insertCell(), 'Server');
  text(head.insertCell(), 'What passes means');
  for (const column of columns) {
    const cell = head.insertCell();
    const strong = document.createElement('strong');
    text(strong, column.heading);
    cell.append(strong, document.createElement('br'));

    // The platform is read from the run rather than written here, so a runner image change
    // or a different emulator shows up as a different platform instead of a stale label.
    const small = document.createElement('small');
    text(small, found.get(column.suite).suite.platform || column.note);
    cell.append(small);
  }

  const body = table.createTBody();
  for (const row of ROWS) {
    const tr = body.insertRow();
    const server = tr.insertCell();
    server.className = 'mono';
    text(server, row.server);
    text(tr.insertCell(), row.asserts);

    for (const column of columns) {
      const cell = tr.insertCell();
      const result = cellFor(found.get(column.suite), row.cases[column.suite]);
      if (!result) {
        text(cell, '—');
        cell.title = 'not covered by this suite';
        continue;
      }
      const pill = document.createElement('span');
      // A failure here is a finding rather than breakage: every one of these calls a server
      // somebody else runs, and the suites are the reporting kind. The page says so in words
      // under the table rather than colouring a real failure green.
      pill.className = `pill ${result.status === 'failed' ? 'finding' : result.status}`;
      text(pill, result.status === 'failed' ? 'no' : result.status === 'passed' ? 'yes' : result.status);
      if (result.message) pill.title = result.message;
      cell.append(pill);
    }
  }

  const scroll = document.createElement('div');
  scroll.className = 'table-scroll';
  scroll.append(table);
  root.replaceChildren(scroll);
}

fetch('../data/latest.json')
  .then((response) => (response.ok ? response.json() : Promise.reject(response.status)))
  .then((data) => render(data, document.getElementById('ech-matrix')))
  .catch(() => {
    text(document.getElementById('ech-matrix'), 'Results are unavailable — latest.json could not be read.');
  });
