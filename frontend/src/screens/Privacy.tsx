/** Política de privacidad: pública (la pide Google para conectar el buzón de facturas). */
export function Privacy() {
  return (
    <main className="privacy">
      <h1 className="page-title">Privacidad de Casa A&amp;G</h1>
      <p>
        Casa A&amp;G es una aplicación privada para llevar las finanzas de un hogar. Solo la usan las personas que viven
        en él y solo se abre desde sus propios dispositivos, por una red privada. No tiene anuncios ni vende ni comparte
        datos con nadie.
      </p>

      <h2 className="section-title">Buzón de facturas</h2>
      <p>
        La aplicación se conecta, con permiso de solo lectura, a un buzón de Gmail creado únicamente para recibir
        facturas electrónicas. No tiene acceso a ningún otro correo. De ese buzón solo lee los comprobantes
        electrónicos adjuntos (XML) y la respuesta de Hacienda; no lee el texto de los correos, no envía correos, no
        borra nada y no cambia el buzón.
      </p>
      <p>
        De cada factura auténtica guarda solo lo necesario para anotarla: el comercio, la fecha, el monto, la moneda,
        la clave de Hacienda (para no repetirla) y la cédula del comercio (para sugerir la categoría la próxima vez).
        La cédula de quien compra nunca se guarda: solo una huella cifrada que sirve para compararla.
      </p>

      <h2 className="section-title">Dónde se guardan los datos</h2>
      <p>
        En un servidor propio, sin acceso desde internet, con respaldos cifrados. El permiso de Google se guarda
        cifrado y se puede revocar en cualquier momento desde la aplicación o desde
        {" "}<span className="mono">myaccount.google.com/permissions</span>.
      </p>

      <h2 className="section-title">Uso de los datos de Google</h2>
      <p>
        El uso de la información recibida de las API de Google se ajusta a la Política de Datos de Usuario de los
        Servicios de API de Google, incluidos los requisitos de uso limitado. Los datos no se usan para publicidad, no
        se transfieren a terceros y no se usan para entrenar modelos de inteligencia artificial.
      </p>
    </main>
  );
}
