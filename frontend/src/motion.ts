// Movimiento con GSAP: solo para comunicar estado (DESIGN.md → Motion).
// Todo pasa por gsap.matchMedia: con "reducir movimiento" no se anima nada y se ve el
// estado final de inmediato. Solo se animan transform y opacity.
import gsap from "gsap";
import { useGSAP } from "@gsap/react";

gsap.registerPlugin(useGSAP);
gsap.defaults({ ease: "power3.out", duration: 0.26 });

export const MOTION_OK = "(prefers-reduced-motion: no-preference)";

export { gsap, useGSAP };

/**
 * Ejecuta animaciones solo si el usuario no pidió reducir el movimiento y la página está
 * visible: en una pestaña oculta el navegador pausa requestAnimationFrame y una animación
 * de entrada dejaría el contenido invisible hasta volver a la pestaña.
 */
export function whenMotionAllowed(setup: () => void): () => void {
  if (document.visibilityState !== "visible") return () => {};
  const mm = gsap.matchMedia();
  mm.add(MOTION_OK, setup);
  return () => mm.revert();
}

export function prefersReducedMotion(): boolean {
  return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

/** Cuenta un número de 0 a su valor (una vez), escribiendo el texto con format. */
export function countUp(el: HTMLElement, to: number, format: (n: number) => string): void {
  if (prefersReducedMotion() || to === 0 || document.visibilityState !== "visible") {
    el.textContent = format(to);
    return;
  }
  const state = { value: 0 };
  gsap.to(state, {
    value: to,
    duration: 0.6,
    ease: "expo.out",
    onUpdate: () => {
      el.textContent = format(state.value);
    },
    onComplete: () => {
      el.textContent = format(to);
    },
  });
}
