const body = document.body;
const header = document.querySelector("[data-header]");
const nav = document.querySelector("[data-nav]");
const navToggle = document.querySelector("[data-nav-toggle]");
const progress = document.querySelector(".scroll-progress span");
const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
const sections = [...document.querySelectorAll("[data-section]")];
const navLinks = [...document.querySelectorAll(".primary-nav a")];

const updateActiveNav = () => {
  const marker = (header?.offsetHeight ?? 0) + Math.min(window.innerHeight * 0.22, 180);
  let activeId = sections[0]?.id;

  sections.forEach((section) => {
    if (section.getBoundingClientRect().top <= marker) activeId = section.id;
  });

  navLinks.forEach((link) => {
    link.classList.toggle("is-active", link.getAttribute("href") === `#${activeId}`);
  });
};

const updateScrollUi = () => {
  const scrollTop = window.scrollY;
  const maxScroll = Math.max(document.documentElement.scrollHeight - window.innerHeight, 1);
  progress.style.width = `${Math.min((scrollTop / maxScroll) * 100, 100)}%`;
  updateActiveNav();
};

updateScrollUi();
window.addEventListener("scroll", updateScrollUi, { passive: true });
window.addEventListener("resize", updateScrollUi);

navToggle?.addEventListener("click", () => {
  const open = body.classList.toggle("nav-open");
  navToggle.setAttribute("aria-expanded", String(open));
  navToggle.setAttribute("aria-label", open ? "关闭导航" : "打开导航");
});

nav?.addEventListener("click", (event) => {
  if (!(event.target instanceof HTMLAnchorElement)) return;
  body.classList.remove("nav-open");
  navToggle?.setAttribute("aria-expanded", "false");
  navToggle?.setAttribute("aria-label", "打开导航");
});

const revealElements = document.querySelectorAll(".reveal");
if (reduceMotion || !("IntersectionObserver" in window)) {
  revealElements.forEach((element) => element.classList.add("is-visible"));
} else {
  const revealObserver = new IntersectionObserver(
    (entries, observer) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;
        entry.target.classList.add("is-visible");
        observer.unobserve(entry.target);
      });
    },
    { threshold: 0.14, rootMargin: "0px 0px -7%" },
  );
  revealElements.forEach((element) => revealObserver.observe(element));
}

if (!reduceMotion && window.matchMedia("(pointer: fine)").matches) {
  document.querySelectorAll("[data-tilt]").forEach((card) => {
    card.addEventListener("pointermove", (event) => {
      const rect = card.getBoundingClientRect();
      const x = (event.clientX - rect.left) / rect.width - 0.5;
      const y = (event.clientY - rect.top) / rect.height - 0.5;
      card.style.setProperty("--rx", `${-y * 2.8}deg`);
      card.style.setProperty("--ry", `${x * 3.2}deg`);
    });

    card.addEventListener("pointerleave", () => {
      card.style.setProperty("--rx", "0deg");
      card.style.setProperty("--ry", "0deg");
    });
  });
}

const modelViewer = document.querySelector("[data-model-viewer]");
const modelStatus = document.querySelector("[data-model-status]");
const modelProgress = document.querySelector("[data-model-progress]");
const modelViewButtons = [...document.querySelectorAll("[data-model-view]")];

if (modelViewer) {
  if (reduceMotion) modelViewer.removeAttribute("auto-rotate");

  modelViewer.addEventListener("progress", (event) => {
    if (!modelProgress) return;
    const progressValue = Number(event.detail?.totalProgress ?? 0);
    modelProgress.textContent = `${Math.round(progressValue * 100)}%`;
  });

  modelViewer.addEventListener("load", () => {
    modelStatus?.classList.add("is-ready");
  });

  modelViewer.addEventListener("error", () => {
    if (!modelStatus) return;
    modelStatus.classList.add("is-error");
    const label = modelStatus.querySelector("strong");
    if (label) label.textContent = "3D 模型暂未加载";
  });

  modelViewButtons.forEach((button) => {
    button.addEventListener("click", () => {
      modelViewButtons.forEach((item) => item.classList.toggle("is-active", item === button));

      if (button.dataset.modelView === "auto") {
        modelViewer.setAttribute("auto-rotate", "");
        return;
      }

      modelViewer.removeAttribute("auto-rotate");
      const orbit = button.dataset.orbit;
      if (!orbit) return;
      modelViewer.setAttribute("camera-orbit", orbit);
      modelViewer.jumpCameraToGoal?.();
    });
  });
}
