/**
 * Injects Material 3 bottom NavigationBar into .app-body mockups.
 * active: "status" | "outcomes" | "settings"
 */
(function () {
  function navHtml(active) {
    const items = [
      { id: "status", href: "10-status.html", icon: "◉", label: "Status" },
      { id: "outcomes", href: "11-outcomes.html", icon: "☰", label: "Outcomes" },
      { id: "settings", href: "12-settings.html", icon: "⚙", label: "Settings" },
    ];
    return (
      '<nav class="nav-bar" aria-label="Main">' +
      items
        .map(
          (it) =>
            '<a class="nav-item' +
            (it.id === active ? " active" : "") +
            '" href="' +
            it.href +
            '">' +
            '<span class="nav-icon" aria-hidden="true">' +
            it.icon +
            "</span>" +
            '<span class="nav-label">' +
            it.label +
            "</span></a>"
        )
        .join("") +
      "</nav>"
    );
  }

  document.addEventListener("DOMContentLoaded", function () {
    const mount = document.querySelector("[data-nav]");
    if (!mount) return;
    const active = mount.getAttribute("data-nav") || "status";
    mount.insertAdjacentHTML("beforebegin", navHtml(active));
    // If data-nav is on a placeholder, remove empty node
    if (mount.hasAttribute("data-nav") && mount.tagName === "DIV" && !mount.innerHTML.trim()) {
      mount.remove();
    }
  });
})();
