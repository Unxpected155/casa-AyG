-- V30: el tema de la app (decisión del 2026-11-27): «Automático» sigue al teléfono; «Claro» y
-- «Oscuro» fijan uno. Se guarda por persona (no en el navegador: la app no guarda nada en
-- localStorage) y vale en todos sus dispositivos.
ALTER TABLE app_user ADD COLUMN theme text NOT NULL DEFAULT 'AUTO' CHECK (theme IN ('AUTO', 'CLARO', 'OSCURO'));
GRANT UPDATE (theme) ON app_user TO casaayg_app;
