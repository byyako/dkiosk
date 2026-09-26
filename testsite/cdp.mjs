// Talks to dKiosk's WebView over the DevTools protocol (debug builds only).
//
//   adb forward tcp:9222 localabstract:webview_devtools_remote_$(adb shell pidof com.tyllad.dkiosk)
//   node testsite/cdp.mjs "location.href"                     evaluate JS (with a user gesture)
//   node testsite/cdp.mjs "document.getElementById('tel').click()"
//   node testsite/cdp.mjs --method Page.crash                 send any protocol method
//   node testsite/cdp.mjs --timeout 2000 "while (true) {}"    give up waiting after 2 s
//
// Prints the result as JSON.

const args = process.argv.slice(2);
let method = "Runtime.evaluate";
let timeoutMs = 10_000;
let expression = null;
for (let i = 0; i < args.length; i++) {
  if (args[i] === "--method") method = args[++i];
  else if (args[i] === "--timeout") timeoutMs = Number(args[++i]);
  else expression = args[i];
}
if (method === "Runtime.evaluate" && !expression) {
  console.error('usage: node cdp.mjs [--method Name] [--timeout ms] "<js expression>"');
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

const timer = setTimeout(() => {
  console.log('"(no reply)"');
  process.exit(0);
}, timeoutMs);

ws.onmessage = (event) => {
  const message = JSON.parse(event.data);
  if (message.id !== 1) return;
  clearTimeout(timer);
  const result = message.result ?? {};
  if (message.error) console.error(message.error.message);
  else if (result.exceptionDetails) console.error(result.exceptionDetails.exception?.description ?? result.exceptionDetails.text);
  else console.log(JSON.stringify(result.result?.value ?? null));
  ws.close();
};

const params = method === "Runtime.evaluate"
  ? { expression, userGesture: true, returnByValue: true, awaitPromise: true }
  : {};
ws.send(JSON.stringify({ id: 1, method, params }));
