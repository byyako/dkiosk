// Runs JavaScript inside dKiosk's WebView over the DevTools protocol (debug builds only).
//
//   adb forward tcp:9222 localabstract:webview_devtools_remote_$(adb shell pidof com.tyllad.dkiosk)
//   node testsite/cdp.mjs "location.href"
//   node testsite/cdp.mjs "document.getElementById('tel').click()"
//
// Evaluates with a user gesture, like a real tap, and prints the result as JSON.

const expression = process.argv[2];
if (!expression) {
  console.error('usage: node cdp.mjs "<js expression>"');
  process.exit(2);
}

const targets = await (await fetch("http://127.0.0.1:9222/json")).json();
const page = targets.find((t) => t.type === "page");
if (!page) throw new Error("no WebView page to attach to; is the kiosk showing a page?");

const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  ws.onopen = resolve;
  ws.onerror = reject;
});

ws.onmessage = (event) => {
  const message = JSON.parse(event.data);
  if (message.id !== 1) return;
  const { result, exceptionDetails } = message.result;
  if (exceptionDetails) console.error(exceptionDetails.exception?.description ?? exceptionDetails.text);
  else console.log(JSON.stringify(result.value ?? null));
  ws.close();
};
ws.send(JSON.stringify({
  id: 1,
  method: "Runtime.evaluate",
  params: { expression, userGesture: true, returnByValue: true, awaitPromise: true },
}));
