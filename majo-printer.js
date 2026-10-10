// Cargar una vez desde tu web, dentro del WebView de MajoTicket.
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
      else job.reject(new Error(result.mensaje || "No se pudo imprimir"));
    };
  }
  window.imprimirPedido = pedido => new Promise((resolve, reject) => {
    if (!window.MajoPrinter) {
      reject(new Error("Abre esta web desde la app MajoTicket para imprimir."));
      return;
    }
    const requestId = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
    const timer = setTimeout(() => {
      pending.delete(requestId);
      reject(new Error("No se recibió respuesta. Revisa la ticketera antes de reintentar para evitar duplicados."));
    }, 25000);
    pending.set(requestId, { resolve, reject, timer });
    try {
      window.MajoPrinter.postMessage(JSON.stringify({ requestId, pedido }));
    } catch (error) {
      clearTimeout(timer); pending.delete(requestId); reject(error);
    }
  });
})();
