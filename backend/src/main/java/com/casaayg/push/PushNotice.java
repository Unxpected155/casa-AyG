package com.casaayg.push;

import java.util.List;
import java.util.UUID;

/**
 * Un aviso que nace de algo que hizo una persona (cerrar el mes, proponer unir los espacios).
 * Se publica como evento y se envía DESPUÉS de confirmar la transacción: si falla el envío,
 * lo que hizo la persona ya quedó guardado. El texto nunca lleva montos.
 */
public record PushNotice(List<UUID> to, String title, String body, String url) {}
