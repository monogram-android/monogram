const RELEASE_URL = "https://github.com/monogram-android/monogram/releases";
const LATEST_RELEASE_URL =
  "https://github.com/monogram-android/monogram/releases/latest";
const RELEASES_API_URL =
  "https://api.github.com/repos/monogram-android/monogram/releases?per_page=10";
const RELEASE_CACHE_KEY = "monogram-site-releases-v1";
const RELEASE_CACHE_TTL = 60 * 60 * 1000;
const LANG_KEY = "monogram-site-language";
const THEME_KEY = "monogram-theme";
const LIGHT = "#eef6fa";
const DARK = "#0e1418";

const translations = window.MONOGRAM_TRANSLATIONS || {};
const root = document.documentElement;
const themeColorMeta = document.querySelector('meta[name="theme-color"]');
const systemThemeQuery = window.matchMedia
  ? window.matchMedia("(prefers-color-scheme: dark)")
  : null;
const latestReleaseNodes = document.querySelectorAll("[data-latest-release]");
const releasesBlock = document.querySelector("[data-releases]");
const releaseLoading = document.querySelector("[data-release-loading]");
const languageSelect = document.querySelector("[data-language]");
const themeToggle = document.querySelector("[data-theme-toggle]");
const lightbox = document.querySelector("[data-lightbox]");
const lightboxImg = document.querySelector("[data-lightbox-img]");

let currentLang = "en";
let themeChoice = "system";
let currentReleases = [];

function storageGet(key) {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function storageSet(key, value) {
  try {
    localStorage.setItem(key, value);
  } catch {
    /* private mode */
  }
}

function detectLanguage() {
  const value = (navigator.language || "en").toLowerCase();
  if (value.startsWith("ru")) return "ru";
  if (value.startsWith("zh")) return "zh";
  return "en";
}

function dictionary(lang) {
  return translations[lang] || translations.en || {};
}

function systemTheme() {
  return systemThemeQuery && systemThemeQuery.matches ? "dark" : "light";
}

function resolvedTheme() {
  return themeChoice === "light" || themeChoice === "dark"
    ? themeChoice
    : systemTheme();
}

function applyTheme() {
  const theme = resolvedTheme();
  root.dataset.theme = theme;
  root.style.colorScheme = theme;
  if (themeColorMeta) {
    const surface = getComputedStyle(document.body).backgroundColor;
    themeColorMeta.setAttribute(
      "content",
      surface || (theme === "dark" ? DARK : LIGHT),
    );
  }
  if (themeToggle) {
    const key = theme === "dark" ? "theme.toLight" : "theme.toDark";
    themeToggle.setAttribute("aria-label", dictionary(currentLang)[key] || key);
  }
}

function setupTheme() {
  const stored = storageGet(THEME_KEY);
  themeChoice = stored === "light" || stored === "dark" ? stored : "system";
  applyTheme();
  if (!systemThemeQuery) return;
  const onChange = () => {
    if (themeChoice === "system") applyTheme();
  };
  if ("addEventListener" in systemThemeQuery)
    systemThemeQuery.addEventListener("change", onChange);
}

function universalApk(release) {
  const assets = Array.isArray(release.assets) ? release.assets : [];
  return assets.find((asset) =>
    /^monogram-universal-.+-release\.apk$/i.test(asset.name || ""),
  );
}

function setLatestReleaseTargets(url, filename) {
  latestReleaseNodes.forEach((node) => {
    node.setAttribute("href", url || RELEASE_URL);
    if (filename) node.setAttribute("download", filename);
    else node.removeAttribute("download");
  });
}

function formatReleaseDate(value, lang) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  const locale = lang === "ru" ? "ru-RU" : lang === "zh" ? "zh-CN" : "en-US";
  return new Intl.DateTimeFormat(locale, {
    day: "numeric",
    month: "long",
    year: "numeric",
  }).format(date);
}

function applyTranslations(lang) {
  const dict = dictionary(lang);
  document.querySelectorAll("[data-i18n]").forEach((node) => {
    const key = node.getAttribute("data-i18n");
    if (key && dict[key]) node.textContent = dict[key];
  });
  document.querySelectorAll("[data-i18n-attr]").forEach((node) => {
    const raw = node.getAttribute("data-i18n-attr");
    if (!raw) return;
    raw.split(",").forEach((pair) => {
      const [attribute, key] = pair.split(":").map((part) => part.trim());
      if (attribute && key && dict[key])
        node.setAttribute(attribute, dict[key]);
    });
  });
  root.lang = lang === "zh" ? "zh-CN" : lang;
  document.title = dict["meta.title"] || "Monogram for Android";
  const description = document.querySelector('meta[name="description"]');
  if (description && dict["meta.description"])
    description.setAttribute("content", dict["meta.description"]);
}

function slotNodes(slot) {
  return {
    container: document.querySelector(`[data-release-slot="${slot}"]`),
    label: document.querySelector(`[data-release-slot-label="${slot}"]`),
    link: document.querySelector(`[data-release-slot-link="${slot}"]`),
    version: document.querySelector(`[data-release-slot-version="${slot}"]`),
    date: document.querySelector(`[data-release-slot-date="${slot}"]`),
  };
}

function renderReleaseRows() {
  const slots = [
    {
      key: "latest",
      release: currentReleases[0] || null,
      labelKey: "release.latestCard",
    },
    { key: "previous", release: currentReleases[1] || null },
    { key: "earlier", release: currentReleases[2] || null },
  ];
  slots.forEach(({ key, release, labelKey }) => {
    const nodes = slotNodes(key);
    if (!nodes.container) return;
    nodes.container.hidden = !release;
    if (!release) return;
    if (nodes.label && labelKey)
      nodes.label.textContent = dictionary(currentLang)[labelKey] || "";
    if (nodes.link) nodes.link.setAttribute("href", release.url || RELEASE_URL);
    if (nodes.version) nodes.version.textContent = release.version;
    if (nodes.date) {
      nodes.date.textContent = formatReleaseDate(
        release.publishedAt,
        currentLang,
      );
      nodes.date.setAttribute("datetime", release.publishedAt);
    }
  });
}

function showReleases(visible) {
  if (releaseLoading) releaseLoading.hidden = true;
  if (!releasesBlock) return;
  releasesBlock.hidden = !visible;
}

function applyLanguage(lang) {
  currentLang = translations[lang] ? lang : "en";
  applyTranslations(currentLang);
  renderReleaseRows();
  languageSelect.value = currentLang;
  applyTheme();
  storageSet(LANG_KEY, currentLang);
}

function setupThemeToggle() {
  if (!themeToggle) return;
  themeToggle.addEventListener("click", () => {
    themeChoice = resolvedTheme() === "dark" ? "light" : "dark";
    storageSet(THEME_KEY, themeChoice);
    applyTheme();
  });
}

function closeLightbox() {
  if (!lightbox) return;
  lightbox.hidden = true;
  document.body.style.overflow = "";
  if (lightboxImg) {
    lightboxImg.removeAttribute("src");
    lightboxImg.alt = "";
  }
}

function setupLightbox() {
  document.querySelectorAll("[data-shot]").forEach((button) => {
    button.addEventListener("click", () => {
      const image = button.querySelector("img");
      if (!image || !lightbox || !lightboxImg) return;
      lightboxImg.src = image.currentSrc || image.src;
      lightboxImg.alt = image.alt;
      lightbox.hidden = false;
      document.body.style.overflow = "hidden";
      const close = lightbox.querySelector("[data-lightbox-close]");
      if (close) close.focus();
    });
  });
  if (!lightbox) return;
  lightbox.addEventListener("click", (event) => {
    if (event.target === lightbox) closeLightbox();
  });
  const close = lightbox.querySelector("[data-lightbox-close]");
  if (close) close.addEventListener("click", closeLightbox);
  window.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && lightbox && !lightbox.hidden) closeLightbox();
  });
}

function cachedReleases() {
  try {
    const { savedAt, releases } = JSON.parse(storageGet(RELEASE_CACHE_KEY));
    const age = Date.now() - savedAt;
    if (!Number.isFinite(savedAt) || age < 0 || age >= RELEASE_CACHE_TTL)
      return null;
    if (!Array.isArray(releases) || !releases.length || releases.length > 3)
      return null;
    return releases.every(
      (r) =>
        r &&
        typeof r.version === "string" &&
        r.version &&
        typeof r.publishedAt === "string" &&
        Number.isFinite(Date.parse(r.publishedAt)) &&
        typeof r.url === "string" &&
        /^https:\/\//i.test(r.url) &&
        typeof r.apkUrl === "string" &&
        (!r.apkUrl || /^https:\/\//i.test(r.apkUrl)) &&
        typeof r.apkName === "string",
    )
      ? releases
      : null;
  } catch {
    return null;
  }
}

async function fetchReleases() {
  const response = await fetch(RELEASES_API_URL, {
    headers: {
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
    },
  });
  if (!response.ok) throw new Error(String(response.status));
  const payload = await response.json();
  if (!Array.isArray(payload)) throw new Error("payload");
  const releases = payload
    .filter((item) => item && !item.draft && item.published_at)
    .sort((a, b) => new Date(b.published_at) - new Date(a.published_at))
    .slice(0, 3)
    .map((release) => {
      const apk = universalApk(release);
      return {
        version: String(release.tag_name || release.name || "").replace(
          /^v/i,
          "",
        ),
        publishedAt: release.published_at,
        url: release.html_url || RELEASE_URL,
        apkUrl: apk && apk.browser_download_url ? apk.browser_download_url : "",
        apkName: apk && apk.name ? apk.name : "",
      };
    })
    .filter((release) => release.version);
  if (!releases.length) throw new Error("empty");
  storageSet(
    RELEASE_CACHE_KEY,
    JSON.stringify({ savedAt: Date.now(), releases }),
  );
  return releases;
}

async function loadReleases() {
  try {
    currentReleases = cachedReleases() || (await fetchReleases());
    const latest = currentReleases[0];
    setLatestReleaseTargets(latest.apkUrl || latest.url, latest.apkName);
    renderReleaseRows();
    showReleases(true);
  } catch (error) {
    currentReleases = [];
    setLatestReleaseTargets(LATEST_RELEASE_URL);
    showReleases(false);
    console.warn("Unable to load releases.", error);
  }
}

setupTheme();
setupThemeToggle();
languageSelect.addEventListener("change", () =>
  applyLanguage(languageSelect.value),
);
setupLightbox();
setLatestReleaseTargets(LATEST_RELEASE_URL);
applyLanguage(storageGet(LANG_KEY) || detectLanguage());
loadReleases();
