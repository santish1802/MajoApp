// Incluir una vez en la página principal de majocafeteria.com.
(() => {
  const pending = new Map();
  if (window.MajoPrinter) {
    window.MajoPrinter.onmessage = event => {
      let result;
      try { result = JSON.parse(event.data); } catch { return; }
      const job = pending.get(result.requestId);
      if (!job) return;
      clearTimeout(job.timer);
      pending.delete(result.requestId);
      if (result.ok) job.resolve(result);
      else job.reject(Object.assign(new Error(result.mensaje || "No se pudo completar la operación"), { codigo: result.codigo }));
    };
  }
  function request(accion, pedido) {
    return new Promise((resolve, reject) => {
      if (!window.MajoPrinter) {
        reject(new Error("Abre esta web desde MajoApp para usar la ticketera."));
        return;
      }
      const requestId = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
      const timer = setTimeout(() => {
        pending.delete(requestId);
        reject(new Error("No se recibió respuesta. Si estabas imprimiendo, revisa el papel antes de reintentar."));
      }, accion === "seleccionar" ? 120000 : 25000);
      pending.set(requestId, { resolve, reject, timer });
      try {
        window.MajoPrinter.postMessage(JSON.stringify({ requestId, accion, pedido }));
      } catch (error) {
        clearTimeout(timer); pending.delete(requestId); reject(error);
      }
    });
  }
  window.seleccionarTicketera = () => request("seleccionar");
  window.estadoTicketera = () => request("estado");
  window.imprimirPrueba = () => request("prueba");
  window.imprimirPedido = pedido => request("imprimir", pedido);
})();
