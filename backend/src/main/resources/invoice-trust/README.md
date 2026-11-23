# Autoridades de confianza para las facturas electrónicas

Certificados **públicos** (PEM) de las autoridades que emiten las llaves con que se firman los
comprobantes y las respuestas de Hacienda. El verificador solo acepta firmas que encadenen a
uno de estos archivos `*.pem`.

Vacía a propósito hasta confirmarlas con una factura real: el lector del buzón anota en
`mailbox_message.detail` la autoridad que emitió cada firma rechazada.
