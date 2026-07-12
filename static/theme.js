(function () {
  var STORAGE_KEY = "theme";
  var THEMES = ["light", "dark", "amoled"];

  function applyTheme(name) {
    if (THEMES.indexOf(name) === -1) name = "light";
    document.documentElement.setAttribute("data-theme", name);
    document.querySelectorAll("[data-theme-btn]").forEach(function (button) {
      button.setAttribute("aria-pressed", String(button.dataset.themeBtn === name));
    });
  }

  function setTheme(name) {
    try {
      window.localStorage.setItem(STORAGE_KEY, name);
    } catch (error) {
      /* localStorage unavailable, theme just won't persist */
    }
    applyTheme(name);
  }

  window.setAppTheme = setTheme;

  document.addEventListener("DOMContentLoaded", function () {
    var stored = "light";
    try {
      stored = window.localStorage.getItem(STORAGE_KEY) || "light";
    } catch (error) {
      stored = "light";
    }
    applyTheme(stored);

    document.querySelectorAll("[data-theme-btn]").forEach(function (button) {
      button.addEventListener("click", function () {
        setTheme(button.dataset.themeBtn);
      });
    });
  });
})();
