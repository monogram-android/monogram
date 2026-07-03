document.documentElement.classList.add("motion-ready");

const RELEASE_URL = "https://github.com/monogram-android/monogram/releases";
const RELEASES_API_URL = "https://api.github.com/repos/monogram-android/monogram/releases?per_page=10";
const LANG_KEY = "monogram-site-language";
const translations = window.MONOGRAM_TRANSLATIONS || {};

const root = document.documentElement;
const topBar = document.querySelector(".top-bar");
const revealNodes = document.querySelectorAll("[data-reveal]");
const latestReleaseNodes = document.querySelectorAll("[data-latest-release]");
const releaseMeta = document.querySelector(".release-meta");
const releaseVersionNodes = document.querySelectorAll("[data-release-version]");
const releaseDateNodes = document.querySelectorAll("[data-release-date]");
const releaseChannelNodes = document.querySelectorAll("[data-release-channel]");
const langButtons = document.querySelectorAll("[data-lang]");

let currentLang = "en";
let currentRelease = null;
let hasAnimatedRelease = false;

function safeStorageGet(key) {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function safeStorageSet(key, value) {
  try {
    localStorage.setItem(key, value);
  } catch {
    // Ignore storage failures.
  }
}

function detectPreferredLanguage() {
  const browserLanguage = (navigator.language || navigator.userLanguage || "en").toLowerCase();

  if (browserLanguage.startsWith("ru")) {
    return "ru";
  }

  if (browserLanguage.startsWith("zh")) {
    return "zh";
  }

  return "en";
}

function getDictionary(lang) {
  return translations[lang] || translations.en;
}

function updateTopBar() {
  if (!topBar) {
    return;
  }

  topBar.dataset.elevated = window.scrollY > 10 ? "true" : "false";
}

function setLatestReleaseTargets(url) {
  latestReleaseNodes.forEach((node) => {
    if (node instanceof HTMLAnchorElement) {
      node.href = url || RELEASE_URL;
    }
  });
}

function formatReleaseDate(value, lang) {
  const date = new Date(value);

  if (Number.isNaN(date.getTime())) {
    return "";
  }

  const localeMap = {
    en: "en-US",
    ru: "ru-RU",
    zh: "zh-CN"
  };

  return new Intl.DateTimeFormat(localeMap[lang] || "en-US", {
    day: "numeric",
    month: "long",
    year: "numeric"
  }).format(date);
}

function applyTranslations(lang) {
  const dictionary = getDictionary(lang);

  document.querySelectorAll("[data-i18n]").forEach((node) => {
    const key = node.getAttribute("data-i18n");

    if (key && dictionary[key]) {
      node.textContent = dictionary[key];
    }
  });

  document.querySelectorAll("[data-i18n-attr]").forEach((node) => {
    const pairs = node.getAttribute("data-i18n-attr").split(",");

    pairs.forEach((pair) => {
      const [attribute, key] = pair.split(":").map((part) => part.trim());

      if (attribute && key && dictionary[key]) {
        node.setAttribute(attribute, dictionary[key]);
      }
    });
  });

  root.lang = lang === "zh" ? "zh-CN" : lang;
  document.title = dictionary["meta.title"];
}

function renderRelease() {
  if (!releaseMeta || !currentRelease) {
    return;
  }

  const dictionary = getDictionary(currentLang);
  const formattedDate = formatReleaseDate(currentRelease.publishedAt, currentLang);
  const stateKey = currentRelease.prerelease ? "release.preview" : "release.latest";

  releaseVersionNodes.forEach((node) => {
    node.textContent = currentRelease.version;
  });

  releaseDateNodes.forEach((node) => {
    node.textContent = formattedDate;
    node.setAttribute("datetime", currentRelease.publishedAt);
  });

  releaseChannelNodes.forEach((node) => {
    node.textContent = dictionary[stateKey];
  });
}

function setReleaseVisibility(isVisible) {
  if (!releaseMeta) {
    return;
  }

  releaseMeta.hidden = !isVisible;

  if (isVisible) {
    releaseMeta.classList.add("is-visible");

    if (!hasAnimatedRelease) {
      releaseMeta.classList.remove("is-appearing");
      void releaseMeta.offsetWidth;
      releaseMeta.classList.add("is-appearing");
      hasAnimatedRelease = true;
    }
  }
}

function updateLanguageButtons(lang) {
  langButtons.forEach((button) => {
    const isActive = button.dataset.lang === lang;
    button.classList.toggle("is-active", isActive);
    button.setAttribute("aria-pressed", isActive ? "true" : "false");
  });
}

function applyLanguage(lang) {
  currentLang = translations[lang] ? lang : "en";
  applyTranslations(currentLang);
  renderRelease();
  updateLanguageButtons(currentLang);
  safeStorageSet(LANG_KEY, currentLang);
}

function setupLanguageSwitcher() {
  langButtons.forEach((button) => {
    button.addEventListener("click", () => {
      applyLanguage(button.dataset.lang || "en");
    });
  });
}

function setupRevealAnimations() {
  if (!("IntersectionObserver" in window) || window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
    revealNodes.forEach((node) => node.classList.add("is-visible"));
    return;
  }

  const observer = new IntersectionObserver(
    (entries, activeObserver) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) {
          return;
        }

        entry.target.classList.add("is-visible");
        activeObserver.unobserve(entry.target);
      });
    },
    {
      threshold: 0.18,
      rootMargin: "0px 0px -8% 0px"
    }
  );

  revealNodes.forEach((node) => observer.observe(node));
}

async function loadLatestRelease() {
  try {
    const response = await fetch(RELEASES_API_URL, {
      headers: {
        Accept: "application/vnd.github+json",
        "X-GitHub-Api-Version": "2026-03-10"
      }
    });

    if (!response.ok) {
      throw new Error(`GitHub API returned ${response.status}`);
    }

    const releases = await response.json();

    if (!Array.isArray(releases)) {
      throw new Error("GitHub API returned an unexpected payload.");
    }

    const release = releases
      .filter((item) => item && !item.draft && item.published_at)
      .sort((left, right) => new Date(right.published_at) - new Date(left.published_at))[0];

    if (!release) {
      throw new Error("No published releases were found.");
    }

    currentRelease = {
      version: (release.tag_name || release.name || "").replace(/^v/i, ""),
      publishedAt: release.published_at,
      prerelease: Boolean(release.prerelease)
    };

    setLatestReleaseTargets(release.html_url || RELEASE_URL);
    setReleaseVisibility(true);
    renderRelease();
  } catch (error) {
    currentRelease = null;
    setLatestReleaseTargets(RELEASE_URL);
    setReleaseVisibility(false);
    console.warn("Unable to load latest release metadata.", error);
  }
}

updateTopBar();
setupLanguageSwitcher();
setupRevealAnimations();
setLatestReleaseTargets(RELEASE_URL);
setReleaseVisibility(false);
applyLanguage(safeStorageGet(LANG_KEY) || detectPreferredLanguage());
loadLatestRelease();

window.addEventListener("scroll", updateTopBar, { passive: true });
