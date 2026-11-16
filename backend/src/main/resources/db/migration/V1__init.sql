-- V1: esquema inicial. Lo ejecuta Flyway como casaayg_owner.
-- La BD se defiende sola: cada regla de negocio importante es también un CHECK, UNIQUE o FK,
-- para que un bug en la app no pueda guardar datos inválidos.
--
-- Modelo de ESPACIOS (ADR-005): los datos pertenecen a un espacio, no a una persona.
--   PERSONAL: un solo miembro, privado.   FAMILIA: los dos miembros de la pareja.
-- Cada fila de negocio guarda space_id (de quién es) y created_by (quién la registró).

CREATE TABLE app_user (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    display_name  text        NOT NULL CHECK (length(display_name) BETWEEN 1 AND 100),
    created_at    timestamptz NOT NULL DEFAULT now(),
    last_login_at timestamptz
);

CREATE TABLE space (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    kind        text        NOT NULL CHECK (kind IN ('PERSONAL', 'FAMILIA')),
    name        text        NOT NULL CHECK (length(name) BETWEEN 1 AND 60),
    created_at  timestamptz NOT NULL DEFAULT now(),
    -- Al fusionarse en el espacio familiar, el personal se archiva (queda vacío).
    archived_at timestamptz,
    merged_into uuid        REFERENCES space (id),
    CHECK (merged_into IS NULL OR (kind = 'PERSONAL' AND archived_at IS NOT NULL))
);

CREATE TABLE space_member (
    space_id  uuid        NOT NULL REFERENCES space (id),
    user_id   uuid        NOT NULL REFERENCES app_user (id),
    joined_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (space_id, user_id)
);

CREATE INDEX space_member_user_idx ON space_member (user_id);

-- Un espacio PERSONAL tiene exactamente un miembro: nadie puede "colarse" en el de otro.
-- (Se bloquea la tabla space_member para que dos inserciones simultáneas no se salten la regla.)
CREATE FUNCTION space_member_personal_guard() RETURNS trigger
    LANGUAGE plpgsql SET search_path = casaayg, pg_temp AS $$
BEGIN
    LOCK TABLE space_member IN SHARE ROW EXCLUSIVE MODE;
    IF (SELECT kind FROM space WHERE id = NEW.space_id) = 'PERSONAL'
       AND EXISTS (SELECT 1 FROM space_member
                   WHERE space_id = NEW.space_id AND user_id <> NEW.user_id) THEN
        RAISE EXCEPTION 'un espacio personal tiene un solo miembro' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER space_member_personal_guard
    BEFORE INSERT OR UPDATE ON space_member
    FOR EACH ROW EXECUTE FUNCTION space_member_personal_guard();

CREATE TABLE category (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id       uuid        NOT NULL REFERENCES space (id),
    created_by     uuid        NOT NULL REFERENCES app_user (id),
    name           text        NOT NULL CHECK (length(name) BETWEEN 1 AND 50),
    category_group text        NOT NULL CHECK (category_group IN ('INGRESO', 'NECESIDAD', 'DESEO', 'AHORRO')),
    active         boolean     NOT NULL DEFAULT true,
    created_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (space_id, name),
    -- Permite FKs compuestas (id, space_id): ver transaction y budget_line.
    UNIQUE (id, space_id)
);

CREATE TABLE budget (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id   uuid        NOT NULL REFERENCES space (id),
    created_by uuid        NOT NULL REFERENCES app_user (id),
    year       smallint    NOT NULL CHECK (year BETWEEN 2000 AND 2100),
    month      smallint    NOT NULL CHECK (month BETWEEN 1 AND 12),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (space_id, year, month),
    UNIQUE (id, space_id)
);

CREATE TABLE budget_line (
    id             uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id       uuid          NOT NULL,
    budget_id      uuid          NOT NULL,
    category_id    uuid          NOT NULL,
    planned_amount numeric(14,2) NOT NULL CHECK (planned_amount >= 0),
    UNIQUE (budget_id, category_id),
    -- FKs compuestas: la línea, su presupuesto y su categoría son del MISMO espacio.
    -- La BD rechaza mezclar datos de dos espacios aunque la app se equivoque (threat model 5.1-T).
    FOREIGN KEY (budget_id, space_id)   REFERENCES budget (id, space_id) ON DELETE CASCADE,
    FOREIGN KEY (category_id, space_id) REFERENCES category (id, space_id)
);

CREATE TABLE transaction (
    id              uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id        uuid          NOT NULL REFERENCES space (id),
    created_by      uuid          NOT NULL REFERENCES app_user (id),
    category_id     uuid          NOT NULL,
    tx_date         date          NOT NULL CHECK (tx_date >= DATE '2000-01-01'),
    description     text          NOT NULL CHECK (length(description) BETWEEN 1 AND 200),
    -- Siempre positivo: el signo lo da el grupo de la categoría (ingreso vs gasto).
    amount          numeric(14,2) NOT NULL CHECK (amount >= 0),
    currency        char(3)       NOT NULL DEFAULT 'CRC' CHECK (currency IN ('CRC', 'USD')),
    payment_method  text          CHECK (length(payment_method) <= 30),
    source          text          NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL', 'CSV', 'FACTURA')),
    -- Facturas: la clave numérica de 50 dígitos de Hacienda. Otras fuentes: un hash.
    dedup_hash      text          CHECK (length(dedup_hash) <= 128),
    -- Al fusionar espacios se conserva de dónde vino la fila ("antes de la boda").
    origin_space_id uuid          REFERENCES space (id),
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (space_id, dedup_hash),
    -- ON DELETE RESTRICT (por defecto): no se borra historial al borrar una categoría.
    FOREIGN KEY (category_id, space_id) REFERENCES category (id, space_id)
);

-- La vista del mes filtra por espacio y rango de fechas.
CREATE INDEX transaction_space_date_idx ON transaction (space_id, tx_date);

-- Registro de eventos de seguridad. Append-only para la app (ver V2).
CREATE TABLE audit_log (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- NULL permitido: un login fallido puede no tener usuario.
    user_id     uuid        REFERENCES app_user (id),
    space_id    uuid        REFERENCES space (id),
    event_type  text        NOT NULL CHECK (length(event_type) BETWEEN 1 AND 50),
    -- Tope de tamaño: nadie puede llenar el disco con un solo evento.
    details     jsonb       NOT NULL DEFAULT '{}'::jsonb CHECK (pg_column_size(details) <= 4096),
    ip          inet,
    occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX audit_log_user_time_idx ON audit_log (user_id, occurred_at);
