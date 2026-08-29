/* Build local navigation from the page itself, so headings and links cannot drift apart. */
(() => {
  const main = document.querySelector("main");
  if (!main) return;

  const headings = [...main.querySelectorAll(":scope > h2, :scope > section > h2")]
    .filter((heading) => !heading.closest("details"));
  if (headings.length < 3) return;

  const usedIds = new Set([...document.querySelectorAll("[id]")].map((node) => node.id));
  for (const heading of headings) {
    if (!heading.id) {
      const base = heading.textContent.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "section";
      let candidate = base;
      let suffix = 2;
      while (usedIds.has(candidate)) candidate = `${base}-${suffix++}`;
      heading.id = candidate;
      usedIds.add(candidate);
    }
  }

  const nav = document.createElement("nav");
  nav.className = "section-nav";
  nav.setAttribute("aria-label", "On this page");
  const label = document.createElement("span");
  label.className = "section-nav-label";
  label.textContent = "On this page";
  nav.append(label);
  for (const heading of headings) {
    const link = document.createElement("a");
    link.href = `#${heading.id}`;
    link.textContent = heading.dataset.navLabel || heading.textContent;
    nav.append(link);
  }

  const lede = main.querySelector(":scope > .lede");
  (lede || main.querySelector(":scope > h1")).after(nav);
})();
